// Package ipdelay is a CUSTOM addition (not part of upstream).
//
// It measures the delay of every IPv4 address behind a node's `server`
// (taken from the profile's `hosts:` entry, or resolved from the domain when
// there is no such entry) and keeps the last result per proxy so the UI can
// show it under the node card.
//
// Power: nothing here runs on its own. Tests only start when the user taps the
// URL-test button (see TestGroup, called from the exported healthCheck).
package ipdelay

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strconv"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/component/resolver"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// IPDelay is one tested address. Delay > 0: milliseconds, 0: failed / timeout.
type IPDelay struct {
	IP    string `json:"ip"`
	Delay int    `json:"delay"`
}

const (
	maxIPs         = 8
	testTimeout    = 5 * time.Second
	defaultTestURL = "https://www.gstatic.com/generate_204"
)

var (
	lock    sync.RWMutex
	proxies = map[string]map[string]any{} // proxy name -> raw proxy mapping
	hosts   = map[string]any{}            // raw `hosts:` of the profile
	results = map[string][]IPDelay{}      // proxy name -> last result
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

	lock.Lock()
	proxies = newProxies
	hosts = newHosts
	results = map[string][]IPDelay{}
	lock.Unlock()
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

			list[i].Delay = testIP(raw, ip, testURL)
		}(i, ip)
	}

	wg.Wait()

	lock.Lock()
	results[name] = list
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

// testIP builds a temporary copy of the node pointing at `ip` and measures the
// time to dial through it and get a response from the test URL. The temporary
// node is never registered anywhere.
func testIP(raw map[string]any, ip, rawURL string) int {
	m := make(map[string]any, len(raw))
	for k, v := range raw {
		m[k] = v
	}

	m["server"] = ip

	px, err := adapter.ParseProxy(m)
	if err != nil {
		log.Warnln("IP delay %s: %s", ip, err.Error())

		return 0
	}

	// release pooled connections of the temporary node (e.g. xhttp reuse)
	defer func() {
		if c, ok := any(px).(interface{ Close() error }); ok {
			_ = c.Close()
		}
	}()

	u, err := url.Parse(rawURL)
	if err != nil {
		return 0
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
		return 0
	}

	ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
	defer cancel()

	start := time.Now()

	conn, err := px.DialContext(ctx, &C.Metadata{
		Host:    u.Hostname(),
		DstPort: uint16(portNum),
	})
	if err != nil {
		return 0
	}
	defer conn.Close()

	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives: true,
		// Latency probe only: nothing secret is sent, and the system CA store is
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
		return 0
	}

	resp, err := client.Do(req)
	if err != nil {
		return 0
	}

	_ = resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return 0
	}

	delay := int(time.Since(start).Milliseconds())
	if delay <= 0 {
		delay = 1
	}

	return delay
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
