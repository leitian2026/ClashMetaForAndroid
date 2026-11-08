// Package ipdelay is a CUSTOM addition (not part of upstream), see CUSTOM.md #1 and #7.
//
//  1. Per-IP test: measures the delay of every IPv4 address behind a node's `server`
//     (taken from the profile's `hosts:` entry, or resolved from the domain when there is no
//     such entry) and, through the same temporary node, asks a "what is my country" service
//     where the traffic exits. The last result per proxy is kept for the UI.
//  2. Current IP: remembers which node IP the core most recently dialed (observed through
//     the socket hook), so the home page can show it.
//
// Power: nothing here runs on its own. Tests only start when the user taps the URL-test
// button (TestGroup, called from the exported healthCheck); the socket hook only does a map
// lookup when the core opens a socket anyway; the country of the current IP is looked up at
// most once per IP (and retried at most every few minutes) when the home page asks.
package ipdelay

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// IPDelay is one tested address. Delay > 0: milliseconds, 0: failed / timeout.
// Country is the exit country code (e.g. "US") seen through that address, "" if unknown.
type IPDelay struct {
	IP      string `json:"ip"`
	Delay   int    `json:"delay"`
	Country string `json:"country,omitempty"`
}

const (
	maxIPs         = 8
	testTimeout    = 5 * time.Second
	geoTimeout     = 4 * time.Second
	retryAfter     = 5 * time.Minute
	defaultTestURL = "https://www.gstatic.com/generate_204"
)

type used struct {
	node string
	ip   string
}

var (
	lock      sync.RWMutex
	proxies   = map[string]map[string]any{} // proxy name -> raw proxy mapping
	hosts     = map[string]any{}            // raw `hosts:` of the profile
	results   = map[string][]IPDelay{}      // proxy name -> last result
	tracked   = map[string]string{}         // server ip -> proxy name (for the socket hook)
	countries = map[string]string{}         // node + "|" + ip -> country code
	probing   = map[string]bool{}           // node + "|" + ip -> lookup running
	failedAt  = map[string]time.Time{}      // node + "|" + ip -> last failed lookup
	lastUsed  used

	// > 0 while our own temporary nodes dial, so that those sockets are not mistaken for
	// real traffic by the socket hook.
	selfDialing int32

	hookOnce sync.Once
)

// CaptureConfig stores the raw `proxies:` and `hosts:` of the loaded profile and
// clears old results. Arguments are `any` on purpose so it does not depend on
// the exact field types of the core's RawConfig.
func CaptureConfig(rawProxies any, rawHosts any) {
	newProxies := map[string]map[string]any{}

	if list, ok := normalize(rawProxies).([]any); ok {
		for _, item := range list {
			m, ok := item.(map[string]any)
			if !ok {
				continue
			}

			if name, _ := m["name"].(string); name != "" {
				newProxies[name] = m
			}
		}
	}

	newHosts := map[string]any{}

	if m, ok := normalize(rawHosts).(map[string]any); ok {
		newHosts = m
	}

	// every address the core may dial for a node: literal server IPs and `hosts:` entries
	newTracked := map[string]string{}

	for name, raw := range newProxies {
		server, _ := raw["server"].(string)
		if server == "" {
			continue
		}

		var values []string

		if _, err := netip.ParseAddr(server); err == nil {
			values = []string{server}
		} else if entry, found := newHosts[server]; found {
			values = toStrings(entry)
		}

		for _, v := range values {
			if a, err := netip.ParseAddr(v); err == nil {
				ip := a.Unmap().String()
				if _, exists := newTracked[ip]; !exists {
					newTracked[ip] = name
				}
			}
		}
	}

	lock.Lock()
	proxies = newProxies
	hosts = newHosts
	results = map[string][]IPDelay{}
	tracked = newTracked
	countries = map[string]string{}
	probing = map[string]bool{}
	failedAt = map[string]time.Time{}
	lastUsed = used{}
	lock.Unlock()
}

// InstallSocketHook wraps the core's socket hook (set in delegate.Init) so that the node IP
// of the most recent connection is remembered. The original hook is always called first.
func InstallSocketHook() {
	hookOnce.Do(func() {
		prev := dialer.DefaultSocketHook

		dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
			if prev != nil {
				if err := prev(network, address, conn); err != nil {
					return err
				}
			}

			record(address)

			return nil
		}
	})
}

func record(address string) {
	if atomic.LoadInt32(&selfDialing) > 0 {
		return
	}

	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return
	}

	a, err := netip.ParseAddr(host)
	if err != nil {
		return
	}

	ip := a.Unmap().String()

	lock.RLock()
	node, ok := tracked[ip]
	lock.RUnlock()

	if !ok {
		return
	}

	lock.Lock()
	lastUsed = used{node: node, ip: ip}
	lock.Unlock()
}

// Current returns the node IP the core dialed most recently and, if already known, the exit
// country behind it. When the country is unknown a single lookup is started in the background;
// ask again later (the UI does that when the user refreshes).
func Current() (node, ip, country string) {
	lock.Lock()
	defer lock.Unlock()

	if lastUsed.ip == "" {
		return "", "", ""
	}

	node, ip = lastUsed.node, lastUsed.ip
	key := node + "|" + ip
	country = countries[key]

	if country == "" && !probing[key] && time.Since(failedAt[key]) > retryAfter {
		if raw := proxies[node]; raw != nil {
			probing[key] = true

			go probeCountry(key, raw, ip)
		}
	}

	return node, ip, country
}

func probeCountry(key string, raw map[string]any, ip string) {
	defer func() {
		if r := recover(); r != nil {
			log.Warnln("IP country probe %s: %v", ip, r)
		}
	}()

	atomic.AddInt32(&selfDialing, 1)
	defer atomic.AddInt32(&selfDialing, -1)

	var country string

	if px := newVariant(raw, ip); px != nil {
		country = lookupCountry(px)
		closeProxy(px)
	}

	lock.Lock()
	defer lock.Unlock()

	delete(probing, key)

	if country != "" {
		countries[key] = country
		delete(failedAt, key)
	} else {
		failedAt[key] = time.Now()
	}
}

// Get returns the last result of a proxy (nil if none).
func Get(name string) []IPDelay {
	lock.RLock()
	defer lock.RUnlock()

	src := results[name]
	if len(src) == 0 {
		return nil
	}

	out := make([]IPDelay, len(src))
	copy(out, src)

	return out
}

// TestGroup tests every IPv4 address of every node in the group. It blocks until
// all tests finished (or timed out), like the core's own health check.
func TestGroup(groupName string) {
	defer func() {
		if r := recover(); r != nil {
			log.Warnln("IP delay test `%s`: %v", groupName, r)
		}
	}()

	p := tunnel.Proxies()[groupName]
	if p == nil {
		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return
	}

	atomic.AddInt32(&selfDialing, 1)
	defer atomic.AddInt32(&selfDialing, -1)

	wg := &sync.WaitGroup{}

	for _, px := range g.Proxies() {
		if _, isGroup := px.Adapter().(outboundgroup.ProxyGroup); isGroup {
			continue
		}

		name := px.Name()

		lock.RLock()
		raw := proxies[name]
		lock.RUnlock()

		if raw == nil {
			continue
		}

		testURL := testURLOf(px)

		wg.Add(1)

		go func(name string, raw map[string]any, testURL string) {
			defer wg.Done()

			testProxy(name, raw, testURL)
		}(name, raw, testURL)
	}

	wg.Wait()
}

func testURLOf(px C.Proxy) string {
	// same rule the core bridge uses to pick the URL shown as delay
	for k := range px.ExtraDelayHistories() {
		if len(k) > 0 {
			return k
		}
	}

	return defaultTestURL
}

func testProxy(name string, raw map[string]any, testURL string) {
	ips := candidateIPs(raw)

	if len(ips) == 0 {
		lock.Lock()
		delete(results, name)
		lock.Unlock()

		return
	}

	// addresses found by resolving the domain are tracked too, so the home page can show them
	lock.Lock()
	for _, ip := range ips {
		if _, exists := tracked[ip]; !exists {
			tracked[ip] = name
		}
	}
	lock.Unlock()

	list := make([]IPDelay, len(ips))

	wg := &sync.WaitGroup{}

	for i, ip := range ips {
		list[i].IP = ip

		wg.Add(1)

		go func(i int, ip string) {
			defer wg.Done()

			defer func() {
				if r := recover(); r != nil {
					log.Warnln("IP delay test `%s` %s: %v", name, ip, r)
				}
			}()

			list[i].Delay, list[i].Country = testIP(raw, ip, testURL)
		}(i, ip)
	}

	wg.Wait()

	lock.Lock()
	results[name] = list

	for _, item := range list {
		if item.Country != "" {
			countries[name+"|"+item.IP] = item.Country
		}
	}
	lock.Unlock()
}

// candidateIPs returns the IPv4 addresses behind the node's server, or nil when
// there is nothing to compare (server is already an IP, node is ipv6 only, ...).
func candidateIPs(raw map[string]any) []string {
	server, _ := raw["server"].(string)
	if server == "" {
		return nil
	}

	if v, _ := raw["ip-version"].(string); v == "ipv6" {
		return nil
	}

	if _, err := netip.ParseAddr(server); err == nil {
		return nil // literal IP: a single address, nothing to compare
	}

	lock.RLock()
	entry, found := hosts[server]
	lock.RUnlock()

	if found {
		return onlyIPv4(toStrings(entry))
	}

	return onlyIPv4(lookup(server))
}

func lookup(host string) []string {
	ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
	defer cancel()

	addrs, err := resolver.LookupIP(ctx, host)
	if err != nil {
		log.Warnln("IP delay resolve %s: %s", host, err.Error())

		return nil
	}

	out := make([]string, 0, len(addrs))

	for _, a := range addrs {
		out = append(out, fmt.Sprint(a))
	}

	return out
}

func onlyIPv4(values []string) []string {
	out := make([]string, 0, len(values))
	seen := map[string]bool{}

	for _, s := range values {
		a, err := netip.ParseAddr(s)
		if err != nil {
			continue
		}

		a = a.Unmap()
		if !a.Is4() || seen[a.String()] {
			continue
		}

		seen[a.String()] = true

		out = append(out, a.String())

		if len(out) >= maxIPs {
			break
		}
	}

	return out
}

func toStrings(v any) []string {
	switch t := v.(type) {
	case string:
		return []string{t}
	case []any:
		out := make([]string, 0, len(t))

		for _, e := range t {
			if s, ok := e.(string); ok {
				out = append(out, s)
			}
		}

		return out
	}

	return nil
}

// newVariant builds a temporary copy of the node pointing at `ip`. It is never registered
// anywhere; call closeProxy when done.
func newVariant(raw map[string]any, ip string) C.Proxy {
	m := make(map[string]any, len(raw))
	for k, v := range raw {
		m[k] = v
	}

	m["server"] = ip

	px, err := adapter.ParseProxy(m)
	if err != nil {
		log.Warnln("IP delay %s: %s", ip, err.Error())

		return nil
	}

	return px
}

// release pooled connections of the temporary node (e.g. xhttp reuse)
func closeProxy(px C.Proxy) {
	if c, ok := any(px).(interface{ Close() error }); ok {
		_ = c.Close()
	}
}

// testIP measures the delay through the node at `ip` and, when that works, the exit country.
func testIP(raw map[string]any, ip, rawURL string) (int, string) {
	px := newVariant(raw, ip)
	if px == nil {
		return 0, ""
	}
	defer closeProxy(px)

	status, _, elapsed, err := requestVia(px, rawURL, testTimeout, false)
	if err != nil || status < 200 || status >= 400 {
		return 0, ""
	}

	delay := int(elapsed.Milliseconds())
	if delay <= 0 {
		delay = 1
	}

	return delay, lookupCountry(px)
}

// geo services that answer with the country of the requester
var geoServices = []struct {
	url   string
	parse func(body []byte) string
}{
	{"https://api.country.is/", func(body []byte) string {
		var v struct {
			Country string `json:"country"`
		}

		if json.Unmarshal(body, &v) != nil {
			return ""
		}

		return v.Country
	}},
	{"https://ipinfo.io/country", func(body []byte) string {
		return strings.TrimSpace(string(body))
	}},
}

// lookupCountry asks the geo services, through px, where the traffic exits. "" if unknown.
func lookupCountry(px C.Proxy) string {
	for _, svc := range geoServices {
		status, body, _, err := requestVia(px, svc.url, geoTimeout, true)
		if err != nil || status != http.StatusOK {
			continue
		}

		if code := strings.ToUpper(strings.TrimSpace(svc.parse(body))); validCountry(code) {
			return code
		}
	}

	return ""
}

func validCountry(code string) bool {
	return len(code) == 2 && code[0] >= 'A' && code[0] <= 'Z' && code[1] >= 'A' && code[1] <= 'Z'
}

// requestVia sends a GET to rawURL through the proxy and returns the status, optionally the
// (size limited) body, and the time from dialing until the response headers arrived.
func requestVia(px C.Proxy, rawURL string, timeout time.Duration, wantBody bool) (int, []byte, time.Duration, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return 0, nil, 0, err
	}

	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}

	portNum, err := strconv.ParseUint(port, 10, 16)
	if err != nil {
		return 0, nil, 0, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	start := time.Now()

	conn, err := px.DialContext(ctx, &C.Metadata{
		Host:    u.Hostname(),
		DstPort: uint16(portNum),
	})
	if err != nil {
		return 0, nil, 0, err
	}
	defer conn.Close()

	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives: true,
		// Latency / country probe only: nothing secret is sent, and the system CA store is
		// not available inside the core on Android.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: u.Hostname()},
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, nil, 0, err
	}

	req.Header.Set("User-Agent", "curl/8.5.0")

	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, 0, err
	}
	defer resp.Body.Close()

	elapsed := time.Since(start)

	var body []byte

	if wantBody {
		body, _ = io.ReadAll(io.LimitReader(resp.Body, 4096))
	}

	return resp.StatusCode, body, elapsed, nil
}

// normalize converts whatever the yaml decoder produced into plain
// map[string]any / []any trees.
func normalize(v any) any {
	switch t := v.(type) {
	case map[string]any:
		m := make(map[string]any, len(t))
		for k, e := range t {
			m[k] = normalize(e)
		}

		return m
	case map[any]any:
		m := make(map[string]any, len(t))
		for k, e := range t {
			m[fmt.Sprint(k)] = normalize(e)
		}

		return m
	case map[string]string:
		m := make(map[string]any, len(t))
		for k, e := range t {
			m[k] = e
		}

		return m
	case []any:
		s := make([]any, len(t))
		for i, e := range t {
			s[i] = normalize(e)
		}

		return s
	case []map[string]any:
		s := make([]any, len(t))
		for i, e := range t {
			s[i] = normalize(e)
		}

		return s
	default:
		return v
	}
}
