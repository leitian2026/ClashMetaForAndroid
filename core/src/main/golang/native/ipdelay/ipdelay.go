// Package ipdelay is a CUSTOM addition (not part of upstream), see CUSTOM.md #1 and #7.
//
//  1. Per-IP test: measures the delay of every IPv4 address behind a node's `server`
//     (taken from the profile's `hosts:` entry, or resolved from the domain when there is no
//     such entry) and, through the same temporary node, asks a "what is my country" service
//     where the traffic exits. The last result per proxy is kept for the UI.
//  2. Current IP: tells the home page which node IP is in use. The node is the one that carries
//     the active connections (their chains); its IP is the one the core dialed most recently
//     (observed through the socket hook), or its only address.
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
	"sort"
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
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// IPDelay is one tested address. Delay > 0: milliseconds, 0: failed / timeout.
// Country is the exit country code (e.g. "US") seen through that address, "" if unknown.
type IPDelay struct {
	IP      string `json:"ip"`
	Delay   int    `json:"delay"`
	Country string `json:"country,omitempty"`
	At      int64  `json:"at,omitempty"` // CUSTOM (CUSTOM.md #12): when the test finished, unix milliseconds
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
	lock       sync.RWMutex
	proxies    = map[string]map[string]any{} // proxy name -> raw proxy mapping
	hosts      = map[string]any{}            // raw `hosts:` of the profile
	results    = map[string][]IPDelay{}      // proxy name -> last result
	tracked    = map[string]string{}         // server ip -> proxy name (for the socket hook)
	nodeIPs    = map[string][]string{}       // proxy name -> every address its server may use
	ambiguous  = map[string]bool{}           // ips that other `hosts:` entries (e.g. DoH) also map to
	lastByNode = map[string]string{}         // proxy name -> ip dialed most recently
	countries  = map[string]string{}         // node + "|" + ip -> country code
	probing    = map[string]bool{}           // node + "|" + ip -> lookup running
	failedAt   = map[string]time.Time{}      // node + "|" + ip -> last failed lookup
	lastUsed   used

	// exit countries learned by a running test, written to disk once the test is over (CUSTOM.md #11)
	pending = map[string]string{}

	// > 0 while our own temporary nodes dial, so that those sockets are not mistaken for
	// real traffic by the socket hook.
	selfDialing int32

	hookOnce sync.Once

	// change notification (see SetOnChange); guarded by notifyLock, never held together with `lock`
	notifyLock  sync.Mutex
	onChange    func()
	notifyTimer *time.Timer
	notified    string // node|ip|country that the listener was last told about

	// CUSTOM.md #7, #10: the set the listener was last told about, and the last address put in the history
	notifiedNode string
	notifiedIPs  map[string]bool
	histLast     string
)

// notifyDelay lets the new connection register itself (so that Current sees it) and merges bursts
// of dials into one notification.
const notifyDelay = 800 * time.Millisecond

// SetOnChange registers the listener that is told when what Current() returns has changed
// (another node IP in use, or its exit country became known). It is event driven: a single
// delayed call is scheduled only when a dial to a different node IP is observed; nothing runs
// while the node IP stays the same.
func SetOnChange(f func()) {
	notifyLock.Lock()
	onChange = f
	notified = ""
	notifiedNode, notifiedIPs, histLast = "", nil, ""
	notifyLock.Unlock()
}

func scheduleNotify() {
	notifyLock.Lock()
	defer notifyLock.Unlock()

	if onChange == nil || notifyTimer != nil {
		return
	}

	notifyTimer = time.AfterFunc(notifyDelay, fireNotify)
}

func fireNotify() {
	notifyLock.Lock()
	notifyTimer = nil
	f := onChange
	notifyLock.Unlock()

	if f == nil {
		return
	}

	node, ips, more := CurrentSet() // also starts the country lookups that are still missing

	key := setKey(node, ips, more)

	notifyLock.Lock()
	same := key == notified
	notified = key
	prevNode, prevIPs := notifiedNode, notifiedIPs
	notifiedNode, notifiedIPs = node, make(map[string]bool, len(ips))

	for _, e := range ips {
		notifiedIPs[e.IP] = true
	}

	last := histLast
	notifyLock.Unlock()

	if same {
		return
	}

	// CUSTOM.md #10: the history gets an address when it first shows up in the set (or the node changed),
	// not every time the addresses take turns as the most recent one.
	for i := len(ips) - 1; i >= 0; i-- {
		e := ips[i]

		if node != prevNode || !prevIPs[e.IP] || node+"|"+e.IP == last {
			recordHistory(node, e.IP, e.Country)

			notifyLock.Lock()
			histLast = node + "|" + e.IP
			notifyLock.Unlock()
		}
	}

	f()
}

// RawProxies returns a copy of the raw `proxies:` mappings of the loaded profile, keyed by name
// (CUSTOM.md #22: the config check reads the fields the user wrote).
func RawProxies() map[string]map[string]any {
	lock.RLock()
	defer lock.RUnlock()

	out := make(map[string]map[string]any, len(proxies))
	for k, v := range proxies {
		out[k] = v
	}

	return out
}

// NodeAddresses returns every address the core may dial for a node (its literal server IP, or the
// `hosts:` entry of its server name); empty when the server is a plain domain.
func NodeAddresses(name string) []string {
	lock.RLock()
	defer lock.RUnlock()

	return append([]string(nil), nodeIPs[name]...)
}

var dohServers []string // DoH addresses written in the profile's `dns:` section

// CaptureDNS remembers the `https://` DNS servers of the profile's `dns:` section (CUSTOM.md #22: the
// config check asks them for ECH configs). The argument is `any` and is walked through JSON, so this does
// not depend on the field names of the core's RawDNS.
func CaptureDNS(rawDNS any) {
	var found []string
	var parsed any
	var policy []PolicyEntry // CUSTOM.md #26

	if data, err := json.Marshal(rawDNS); err == nil {
		var tree any

		if json.Unmarshal(data, &tree) == nil {
			collectHTTPS(tree, &found)
			parsed = tree // CUSTOM.md #25: the DNS check reads the whole section
			policy = parsePolicy(data)
		}
	}

	setPolicy(policy) // CUSTOM.md #26: nameserver-policy with its order

	lock.Lock()
	dohServers = found
	dnsTree = parsed
	lock.Unlock()
}

// DoHServers returns the DoH servers captured by CaptureDNS, `#options` suffixes removed.
func DoHServers() []string {
	lock.RLock()
	defer lock.RUnlock()

	return append([]string(nil), dohServers...)
}

func collectHTTPS(v any, out *[]string) {
	switch t := v.(type) {
	case string:
		if strings.HasPrefix(t, "https://") {
			if i := strings.IndexByte(t, '#'); i >= 0 {
				t = t[:i]
			}

			for _, e := range *out {
				if e == t {
					return
				}
			}

			*out = append(*out, t)
		}
	case []any:
		for _, e := range t {
			collectHTTPS(e, out)
		}
	case map[string]any:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}

		sort.Strings(keys) // same order every time

		for _, k := range keys {
			collectHTTPS(t[k], out)
		}
	}
}

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
	newNodeIPs := map[string][]string{}
	servers := map[string]bool{}

	for name, raw := range newProxies {
		server, _ := raw["server"].(string)
		if server == "" {
			continue
		}

		servers[server] = true

		var values []string

		if _, err := netip.ParseAddr(server); err == nil {
			values = []string{server}
		} else if entry, found := newHosts[server]; found {
			values = toStrings(entry)
		}

		for _, v := range values {
			if a, err := netip.ParseAddr(v); err == nil {
				newNodeIPs[name] = append(newNodeIPs[name], a.Unmap().String())
			}
		}
	}

	// `hosts:` entries that are not a node's server (e.g. the DoH domain of the dns section) make
	// the core open sockets to their addresses itself. Those sockets say nothing about which node
	// IP is in use, so such addresses are not watched by the socket hook.
	newAmbiguous := map[string]bool{}

	for key, entry := range newHosts {
		if servers[key] {
			continue
		}

		for _, v := range toStrings(entry) {
			if a, err := netip.ParseAddr(v); err == nil {
				newAmbiguous[a.Unmap().String()] = true
			}
		}
	}

	newTracked := map[string]string{}

	for name, list := range newNodeIPs {
		for _, ip := range list {
			if _, exists := newTracked[ip]; !exists && !newAmbiguous[ip] {
				newTracked[ip] = name
			}
		}
	}

	// CUSTOM.md #11: exit countries remembered from earlier runs, so no probe is needed for them
	names := make(map[string]bool, len(newProxies))
	for name := range newProxies {
		names[name] = true
	}

	saved := savedCountries(names)

	lock.Lock()
	proxies = newProxies
	hosts = newHosts
	results = map[string][]IPDelay{}
	tracked = newTracked
	nodeIPs = newNodeIPs
	ambiguous = newAmbiguous
	lastByNode = map[string]string{}
	recent = map[string][]useRec{} // CUSTOM (CUSTOM.md #7)
	shown = map[string]string{}
	countries = saved
	pending = map[string]string{}
	probing = map[string]bool{}
	failedAt = map[string]time.Time{}
	lastUsed = used{}
	lock.Unlock()
}

// InstallSocketHook wraps the core's socket hook (set in delegate.Init) so that the node IP
// of the most recent connection is remembered. The original hook is always called first.
// `home` is the core's home directory; it only tells where the persistent files go.
func InstallSocketHook(home string) {
	setDataDir(home) // CUSTOM.md #10, #11: where the small persistent files live

	installUseHook() // CUSTOM.md #7: which address each request really goes through

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
	changed := lastUsed.node != node || lastUsed.ip != ip
	lastUsed = used{node: node, ip: ip}
	lastByNode[node] = ip
	lock.Unlock()

	if changed {
		scheduleNotify()
	}
}

// activeNodes counts the active connections per node, by looking for node names in the chains.
func activeNodes() map[string]int {
	lock.RLock()
	names := make(map[string]bool, len(proxies))
	for name := range proxies {
		names[name] = true
	}
	lock.RUnlock()

	counts := map[string]int{}

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		for _, name := range c.Chains() {
			if names[name] {
				counts[name]++
			}
		}

		return true
	})

	return counts
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

	delete(probing, key)

	if country != "" {
		countries[key] = country
		delete(failedAt, key)
	} else {
		failedAt[key] = time.Now()
	}

	lock.Unlock()

	if country != "" {
		rememberCountries(map[string]string{key: country}) // CUSTOM.md #11
		scheduleNotify()                                   // the flag can now be shown
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

	defer flushPending() // CUSTOM.md #11: write the countries learned by this test once

	MarkTested() // CUSTOM.md #24: the core's own latency test of this group has just finished (the caller ran it first)

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
	if len(nodeIPs[name]) == 0 {
		nodeIPs[name] = append(nodeIPs[name], ips...)
	}

	for _, ip := range ips {
		if _, exists := tracked[ip]; !exists && !ambiguous[ip] {
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

	now := time.Now().UnixMilli()

	lock.Lock()

	for i := range list {
		list[i].At = now // CUSTOM.md #12
	}

	results[name] = list

	for _, item := range list {
		if item.Country != "" {
			key := name + "|" + item.IP

			countries[key] = item.Country
			pending[key] = item.Country
		}
	}
	lock.Unlock()
}

// flushPending writes the exit countries learned during a test (CUSTOM.md #11).
func flushPending() {
	lock.Lock()
	found := pending
	pending = map[string]string{}
	lock.Unlock()

	rememberCountries(found)
}

// Lookup returns the address the core most recently dialed for `node` (or its only address) and,
// when already known, the exit country behind it. It never starts a probe (CUSTOM.md #8).
func Lookup(node string) (ip, country string) {
	lock.RLock()
	defer lock.RUnlock()

	if rec := recent[node]; len(rec) > 0 { // CUSTOM.md #7: the address a request went through last
		ip = rec[0].ip
	} else if last, ok := lastByNode[node]; ok {
		ip = last
	} else if list := nodeIPs[node]; len(list) == 1 {
		ip = list[0]
	}

	if ip == "" {
		return "", ""
	}

	return ip, countries[node+"|"+ip]
}

// CountryOf returns the exit country already known for that node and address, "" when it is not.
// It never starts a probe (CUSTOM.md #20).
func CountryOf(node, ip string) string {
	lock.RLock()
	defer lock.RUnlock()

	return countries[node+"|"+ip]
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
