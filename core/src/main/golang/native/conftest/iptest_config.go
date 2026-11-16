package conftest

// CUSTOM (CUSTOM.md #69, not upstream): mode "config" of the test-IP page: read the addresses the profile itself
// uses for its nodes, so they can be put into the list of "test other IPs".
//
// What is read, per node of `proxies:` that has a `server`:
//   - a literal IP: that address;
//   - a name that `hosts:` has (exact, or by a wildcard key): the addresses of that entry; a value that is itself
//     a name (an alias) is followed, at most maxAliasDepth names deep, a loop stops it;
//   - any other name: asked from the core's own resolver (askCore, the same as the DNS check page), A and AAAA.
//
// `hosts:` entries that belong to no node (e.g. the DoH domain of `dns:`) are not read. Nodes of providers are not
// in `proxies:` and are not read either.
//
// Not checked against the core's source (it is not in this repository's checkout): the way the core matches a
// wildcard key of `hosts:` ("*.a.com", "+.a.com", ".a.com") and that a value of `hosts:` may be a name. The matching
// below is written from memory of the documented behaviour; see CUSTOM.md #69.
//
// Power: nothing runs on its own. One tap = one read of the profile in memory, and at most one A and one AAAA
// question per distinct domain (four domains at a time, all within readTimeout).

import (
	"context"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	"cfa/native/ipdelay"
)

const (
	maxAliasDepth = 3                // names followed through `hosts:` values
	maxCfgResolve = 8                // most addresses taken from one resolved domain (sorted: IPv4 first); the rest is counted
	resolveAtOnce = 4                // domains asked at the same time
	readTimeout   = 10 * time.Second // the whole read; a domain not answered by then is "timeout"
)

// ConfigIP is one address read from the profile.
type ConfigIP struct {
	IP    string   `json:"ip"`
	From  []string `json:"from"`  // the nodes that use it (sorted)
	Via   string   `json:"via"`   // "server" (written as the node's server), "hosts" (in a hosts entry), "alias" (reached through a hosts value that is a name), "dns" (resolved)
	Name  string   `json:"name"`  // the hosts key or domain it came from, "" for "server"
	Chain bool     `json:"chain"` // every node that uses it has a `dialer-proxy`: a direct connection to it may not be possible
}

// ConfigFail is a server that gave no address.
type ConfigFail struct {
	Name   string   `json:"name"`   // the domain
	Nodes  []string `json:"nodes"`  // the nodes whose server it is (sorted)
	Reason string   `json:"reason"` // "failed" (no answer / error), "empty" (answered, but no address), "fake" (only fake-ip addresses), "loop" (hosts aliases too deep or in a circle), "timeout", "zone" (a link-local address with a zone id)
	Detail string   `json:"detail"` // the core's words for "failed"
}

// ConfigRead is the result of reading the profile.
type ConfigRead struct {
	IPs     []ConfigIP
	Fails   []ConfigFail
	Omitted int // addresses of a resolved domain over maxCfgResolve
	Family  int // addresses left out because the node's `ip-version` is the other family
}

// dnsResult is what asking for one domain gave.
type dnsResult struct {
	IPs     []netip.Addr
	Omitted int
	Reason  string // "" when IPs is not empty
	Detail  string
}

// found is one address on its way from a node's server.
type found struct {
	addr netip.Addr
	via  string
	name string
}

type nodeRead struct {
	name  string
	chain bool
	found []found
	fails []ConfigFail
	skip  int
}

var (
	cfgReadLock   sync.Mutex
	cfgReadCancel context.CancelFunc
)

// readConfigIPs reads the profile in memory (ipdelay.RawProxies / RawHosts) and asks the core's resolver for the
// domains that need it.
func readConfigIPs(ctx context.Context) ConfigRead {
	ctx, cancel := context.WithTimeout(ctx, readTimeout)

	cfgReadLock.Lock()
	if cfgReadCancel != nil {
		cfgReadCancel() // an earlier read still going is stopped
	}

	cfgReadCancel = cancel
	cfgReadLock.Unlock()

	defer func() {
		cancel()

		cfgReadLock.Lock()
		cfgReadCancel = nil
		cfgReadLock.Unlock()
	}()

	return collectConfig(ctx, ipdelay.RawProxies(), ipdelay.RawHosts(), askDomain)
}

// askDomain asks the core's resolver for A and AAAA of one domain (askCore, dnscore.go).
func askDomain(ctx context.Context, domain string) dnsResult {
	ans := askCore(ctx, domain)

	if ctx.Err() != nil {
		return dnsResult{Reason: "timeout"}
	}

	if !ans.OK {
		return dnsResult{Reason: "failed", Detail: ans.Reason}
	}

	var v4, v6 []netip.Addr

	for _, s := range append(append([]string{}, ans.A...), ans.AAAA...) {
		a, err := netip.ParseAddr(s)
		if err != nil {
			continue
		}

		a = a.Unmap()

		if a.Is4() {
			v4 = append(v4, a)
		} else {
			v6 = append(v6, a)
		}
	}

	sortAddrs(v4)
	sortAddrs(v6)

	// 198.18.0.0/15 is the default fake-ip range: such an answer is not an address of the server
	usable := make([]netip.Addr, 0, len(v4)+len(v6))
	fake := 0

	for _, a := range append(v4, v6...) {
		if isFakeAddr(a) {
			fake++

			continue
		}

		usable = append(usable, a)
	}

	if len(usable) == 0 {
		if fake > 0 {
			return dnsResult{Reason: "fake"}
		}

		return dnsResult{Reason: "empty"}
	}

	out := dnsResult{IPs: usable}

	if len(usable) > maxCfgResolve {
		out.Omitted = len(usable) - maxCfgResolve
		out.IPs = usable[:maxCfgResolve]
	}

	return out
}

var fakePrefix = netip.MustParsePrefix("198.18.0.0/15")

func isFakeAddr(a netip.Addr) bool {
	return a.Is4() && fakePrefix.Contains(a)
}

func sortAddrs(list []netip.Addr) {
	sort.Slice(list, func(i, j int) bool { return list[i].Less(list[j]) })
}

// collectConfig is readConfigIPs without the core: `ask` answers a domain. Pure apart from `ask`, so a test can
// give it a table.
func collectConfig(ctx context.Context, proxies map[string]map[string]any, hosts map[string]any,
	ask func(context.Context, string) dnsResult) ConfigRead {

	names := make([]string, 0, len(proxies))

	for name, raw := range proxies {
		if server, _ := raw["server"].(string); strings.TrimSpace(server) != "" {
			names = append(names, name)
		}
	}

	sort.Strings(names)

	// every distinct domain is asked once, however many nodes use it, and at most resolveAtOnce at a time
	memo := &dnsMemo{ask: ask, ctx: ctx, slots: make(chan struct{}, resolveAtOnce), done: map[string]*dnsCell{}}

	reads := make([]nodeRead, len(names))

	var wg sync.WaitGroup

	for i, name := range names {
		wg.Add(1)

		go func(i int, name string) {
			defer wg.Done()

			reads[i] = readNode(name, proxies[name], hosts, memo)
		}(i, name)
	}

	wg.Wait()

	return mergeReads(reads, memo)
}

type dnsCell struct {
	done chan struct{}
	res  dnsResult
}

// dnsMemo asks each domain once; the others who want it wait for the same answer.
type dnsMemo struct {
	ask   func(context.Context, string) dnsResult
	ctx   context.Context
	slots chan struct{}

	mu   sync.Mutex
	done map[string]*dnsCell
}

func (m *dnsMemo) get(domain string) dnsResult {
	m.mu.Lock()

	if cell, ok := m.done[domain]; ok {
		m.mu.Unlock()
		<-cell.done

		return cell.res
	}

	cell := &dnsCell{done: make(chan struct{})}
	m.done[domain] = cell
	m.mu.Unlock()

	defer close(cell.done)

	select {
	case m.slots <- struct{}{}:
		defer func() { <-m.slots }()
	case <-m.ctx.Done():
		cell.res = dnsResult{Reason: "timeout"}

		return cell.res
	}

	func() {
		defer func() {
			if r := recover(); r != nil {
				cell.res = dnsResult{Reason: "failed"}
			}
		}()

		cell.res = m.ask(m.ctx, domain)
	}()

	return cell.res
}

// omitted adds up what was cut from the domains that were asked.
func (m *dnsMemo) omitted() int {
	m.mu.Lock()
	defer m.mu.Unlock()

	total := 0

	for _, c := range m.done {
		total += c.res.Omitted
	}

	return total
}

// readNode reads one node's server.
func readNode(name string, raw map[string]any, hosts map[string]any, memo *dnsMemo) nodeRead {
	out := nodeRead{name: name}

	if v, _ := raw["dialer-proxy"].(string); strings.TrimSpace(v) != "" {
		out.chain = true
	}

	server := strings.TrimSpace(raw["server"].(string))
	version, _ := raw["ip-version"].(string)

	add := func(a netip.Addr, via, from string) {
		a = a.Unmap()

		// `ip-version: ipv4` / `ipv6` makes the node use that family only; the other is not what it would dial
		if (version == "ipv4" && !a.Is4()) || (version == "ipv6" && a.Is4()) {
			out.skip++

			return
		}

		out.found = append(out.found, found{addr: a, via: via, name: from})
	}

	// a literal address, in the forms a profile may have: 1.2.3.4, [2606::1], 2606::1
	if a, ok := parseServerAddr(server); ok {
		if a.Zone() != "" {
			out.fails = append(out.fails, ConfigFail{Name: server, Nodes: []string{name}, Reason: "zone"})

			return out
		}

		add(a, "server", "")

		return out
	}

	domain := normName(server)
	if domain == "" {
		return out
	}

	walkName(domain, hosts, memo, add, &out, 0, map[string]bool{}, "")

	return out
}

// walkName follows a name through `hosts:` (and its aliases) and finally the core's resolver.
func walkName(domain string, hosts map[string]any, memo *dnsMemo, add func(netip.Addr, string, string),
	out *nodeRead, depth int, seen map[string]bool, root string) {

	if root == "" {
		root = domain
	}

	if seen[domain] || depth > maxAliasDepth {
		out.fails = append(out.fails, ConfigFail{Name: root, Nodes: []string{out.name}, Reason: "loop"})

		return
	}

	seen[domain] = true
	defer delete(seen, domain) // a path, not a list: two values may name the same alias

	if key, values, ok := lookupHostsEntry(hosts, domain); ok {
		via := "hosts"
		if depth > 0 {
			via = "alias"
		}

		for _, v := range values {
			v = strings.TrimSpace(v)

			if a, ok := parseServerAddr(v); ok {
				if a.Zone() == "" {
					add(a, via, key)
				}

				continue
			}

			// the value is a name: the core takes it as an alias of the key
			if alias := normName(v); alias != "" {
				walkName(alias, hosts, memo, add, out, depth+1, seen, root)
			}

			// anything else is not an address and not a name: left out
		}

		return
	}

	res := memo.get(domain)

	if len(res.IPs) == 0 {
		out.fails = append(out.fails, ConfigFail{Name: domain, Nodes: []string{out.name}, Reason: res.Reason, Detail: res.Detail})

		return
	}

	for _, a := range res.IPs {
		add(a, "dns", domain)
	}
}

// mergeReads puts the nodes' addresses together: one row per address (the nodes that use it listed), one failure
// per domain.
func mergeReads(reads []nodeRead, memo *dnsMemo) ConfigRead {
	out := ConfigRead{IPs: []ConfigIP{}, Fails: []ConfigFail{}, Omitted: memo.omitted()}

	index := map[string]int{}
	allChain := map[string]bool{}
	failIndex := map[string]int{}

	for _, r := range reads {
		out.Family += r.skip

		for _, f := range r.found {
			key := f.addr.String()

			i, ok := index[key]
			if !ok {
				index[key] = len(out.IPs)
				i = len(out.IPs)
				out.IPs = append(out.IPs, ConfigIP{IP: key, From: []string{}, Via: f.via, Name: f.name})
				allChain[key] = true
			}

			if !containsStr(out.IPs[i].From, r.name) {
				out.IPs[i].From = append(out.IPs[i].From, r.name)
			}

			if !r.chain {
				allChain[key] = false
			}
		}

		for _, f := range r.fails {
			i, ok := failIndex[f.Name+"|"+f.Reason]
			if !ok {
				failIndex[f.Name+"|"+f.Reason] = len(out.Fails)
				f.Nodes = append([]string{}, f.Nodes...)
				out.Fails = append(out.Fails, f)

				continue
			}

			for _, n := range f.Nodes {
				if !containsStr(out.Fails[i].Nodes, n) {
					out.Fails[i].Nodes = append(out.Fails[i].Nodes, n)
				}
			}
		}
	}

	for i := range out.IPs {
		sort.Strings(out.IPs[i].From)

		out.IPs[i].Chain = allChain[out.IPs[i].IP]
	}

	sort.SliceStable(out.Fails, func(i, j int) bool { return out.Fails[i].Name < out.Fails[j].Name })

	for i := range out.Fails {
		sort.Strings(out.Fails[i].Nodes)
	}

	return out
}

func containsStr(list []string, s string) bool {
	for _, e := range list {
		if e == s {
			return true
		}
	}

	return false
}

// parseServerAddr reads an address as a profile may write it: `1.2.3.4`, `2606::1`, `[2606::1]`, with a zone
// (`fe80::1%eth0`). ok is false when it is not an address.
func parseServerAddr(s string) (netip.Addr, bool) {
	s = strings.TrimSpace(s)

	if strings.HasPrefix(s, "[") && strings.HasSuffix(s, "]") {
		s = s[1 : len(s)-1]
	}

	a, err := netip.ParseAddr(s)
	if err != nil {
		return netip.Addr{}, false
	}

	return a, true
}

// normName lowercases a name and drops a final dot; "" when it is not a host name the test can use.
func normName(s string) string {
	name, ok := domainOf(strings.TrimSpace(s))
	if !ok {
		return ""
	}

	return name
}

// lookupHostsEntry finds the `hosts:` entry for a name: an exact key first, then the most specific wildcard key
// (the one with the most labels; equal ones by key order, so the answer does not change between runs).
//
// Wildcards (from memory, see the top of this file): "+.a.com" = a.com and every name below it; "*.a.com" = one
// label below a.com; ".a.com" = any name below a.com (not a.com itself).
func lookupHostsEntry(hosts map[string]any, name string) (key string, values []string, ok bool) {
	for k, v := range hosts {
		if strings.EqualFold(strings.TrimSuffix(strings.TrimSpace(k), "."), name) {
			return strings.ToLower(k), ipdelay.ToStrings(v), true
		}
	}

	keys := make([]string, 0, len(hosts))
	for k := range hosts {
		keys = append(keys, k)
	}

	sort.Strings(keys)

	best, bestLabels := "", -1

	for _, k := range keys {
		pattern := strings.ToLower(strings.TrimSpace(k))

		if !wildcardMatch(pattern, name) {
			continue
		}

		if labels := strings.Count(pattern, "."); labels > bestLabels {
			best, bestLabels = k, labels
		}
	}

	if bestLabels < 0 {
		return "", nil, false
	}

	return strings.ToLower(best), ipdelay.ToStrings(hosts[best]), true
}

func wildcardMatch(pattern, name string) bool {
	switch {
	case strings.HasPrefix(pattern, "+."):
		base := pattern[2:]

		return base != "" && (name == base || strings.HasSuffix(name, "."+base))
	case strings.HasPrefix(pattern, "*."):
		base := pattern[2:]

		if base == "" || !strings.HasSuffix(name, "."+base) {
			return false
		}

		return !strings.Contains(strings.TrimSuffix(name, "."+base), ".")
	case strings.HasPrefix(pattern, "."):
		base := pattern[1:]

		return base != "" && strings.HasSuffix(name, "."+base)
	}

	return false
}
