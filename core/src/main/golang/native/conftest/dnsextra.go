package conftest

// CUSTOM (CUSTOM.md #26, not upstream): the pure logic added to the DNS check, standard library only so that it
// can be unit-tested on its own (dnsextra_test.go):
//
//   - a verdict over the answers of all servers (are the plain-text ones forged?),
//   - a hint when every server says NXDOMAIN (the domain is probably mistyped),
//   - which `nameserver-policy` entry covers the domain.

import (
	"net/netip"
	"strings"
)

// DNSFlag marks one server whose answer looks wrong. Level is "likely" or "suspect"; Why holds the reasons:
// "bogon" (an address no real site has), "nx" (NXDOMAIN while other servers resolve the name), "differs"
// (nothing in common with what the encrypted servers answered).
type DNSFlag struct {
	Address string   `json:"address"`
	Level   string   `json:"level"`
	Why     []string `json:"why"`
}

// DNSVerdict is the overall reading of the answers. Kind: none (no server answered), nxdomain_all, polluted,
// suspect, consistent, single (only one server gave addresses, nothing to compare with).
type DNSVerdict struct {
	Kind    string    `json:"kind"`
	Flags   []DNSFlag `json:"flags"`
	Trusted []string  `json:"trusted"` // addresses answered by DoH / DoT servers, which cannot be rewritten on the way
	Suggest string    `json:"suggest"` // a likelier spelling of the domain (every server said NXDOMAIN)
	Hint    string    `json:"hint"`    // typo, single_label or ""
}

func allAddrs(s DNSServer) []string {
	out := make([]string, 0, len(s.A)+len(s.AAAA))
	out = append(out, s.A...)

	return append(out, s.AAAA...)
}

func isPlain(s DNSServer) bool { return s.Kind == kindUDP || s.Kind == kindTCP }

func isEncrypted(s DNSServer) bool { return s.Kind == kindDoH || s.Kind == kindDoT }

var bogonPrefixes = func() []netip.Prefix {
	var out []netip.Prefix

	for _, p := range []string{
		"0.0.0.0/8", "100.64.0.0/10", "198.18.0.0/15", "240.0.0.0/4", // this-network, CGNAT, benchmarking, reserved
		"2001::/32", "2001:db8::/32", // Teredo, documentation
	} {
		out = append(out, netip.MustParsePrefix(p))
	}

	return out
}()

// bogon tells whether a real website could never be at this address (private, loopback, reserved, Teredo ...).
// Forged DNS answers very often are.
func bogon(addr string) bool {
	a, err := netip.ParseAddr(addr)
	if err != nil {
		return false
	}

	a = a.Unmap()

	if a.IsLoopback() || a.IsPrivate() || a.IsUnspecified() || a.IsLinkLocalUnicast() || a.IsMulticast() {
		return true
	}

	for _, p := range bogonPrefixes {
		if p.Contains(a) {
			return true
		}
	}

	return false
}

// netKey is the network an address is judged by: the first two octets of an IPv4 address, the first 32 bits of an
// IPv6 address. Two answers from the same CDN usually share it, answers from unrelated networks do not.
func netKey(addr string) (family int, key string) {
	a, err := netip.ParseAddr(addr)
	if err != nil {
		return 0, ""
	}

	a = a.Unmap()

	if a.Is4() {
		b := a.As4()

		return 4, string([]byte{b[0], b[1]})
	}

	b := a.As16()

	return 6, string(b[:4])
}

func netKeys(addrs []string) map[int]map[string]bool {
	out := map[int]map[string]bool{}

	for _, a := range addrs {
		f, k := netKey(a)
		if f == 0 {
			continue
		}

		if out[f] == nil {
			out[f] = map[string]bool{}
		}

		out[f][k] = true
	}

	return out
}

// sharesNetwork: some address family is present on both sides and has a network in common. A pair with no family
// in common cannot be judged and counts as sharing.
func sharesNetwork(a, b map[int]map[string]bool) bool {
	compared := false

	for f, keys := range a {
		other := b[f]
		if len(other) == 0 {
			continue
		}

		compared = true

		for k := range keys {
			if other[k] {
				return true
			}
		}
	}

	return !compared
}

// analyzeVerdict reads the answers of all servers together. Servers that failed or whose answers lie in the
// fake-ip pool take no part.
func analyzeVerdict(servers []DNSServer, domain string) DNSVerdict {
	v := DNSVerdict{Flags: []DNSFlag{}, Trusted: []string{}}

	var ok []DNSServer

	for _, s := range servers {
		if s.OK && !s.Fake {
			ok = append(ok, s)
		}
	}

	if len(ok) == 0 {
		v.Kind = "none"

		return v
	}

	nx, answering := 0, 0

	for _, s := range ok {
		switch {
		case s.Rname == "NXDOMAIN":
			nx++
		case len(allAddrs(s)) > 0:
			answering++
		}
	}

	if nx == len(ok) {
		v.Kind = "nxdomain_all"
		v.Suggest, v.Hint = suggestDomain(domain)

		return v
	}

	// what the encrypted servers say is the reference
	var trusted []string

	seen := map[string]bool{}

	for _, s := range ok {
		if !isEncrypted(s) {
			continue
		}

		for _, a := range allAddrs(s) {
			if !seen[a] {
				seen[a] = true

				trusted = append(trusted, a)
			}
		}
	}

	if trusted != nil {
		v.Trusted = trusted
	}

	ref := netKeys(trusted)

	type flagged struct {
		flag DNSFlag
		keys map[int]map[string]bool
	}

	var flags []flagged

	for _, s := range ok {
		addrs := allAddrs(s)

		var why []string

		if s.Rname == "NXDOMAIN" && answering > 0 {
			why = append(why, "nx")
		}

		for _, a := range addrs {
			if bogon(a) {
				why = append(why, "bogon")

				break
			}
		}

		if isPlain(s) && len(trusted) > 0 && len(addrs) > 0 && !sharesNetwork(netKeys(addrs), ref) {
			why = append(why, "differs")
		}

		if len(why) == 0 {
			continue
		}

		level := "suspect"

		for _, w := range why {
			if w == "bogon" || (w == "nx" && isPlain(s) && len(trusted) > 0) {
				level = "likely"
			}
		}

		flags = append(flags, flagged{DNSFlag{Address: s.Address, Level: level, Why: why}, netKeys(addrs)})
	}

	// several plain servers that disagree with the encrypted ones and with each other: they are not the same
	// CDN seen from different places, the answers are scattered over unrelated networks
	for i := range flags {
		if flags[i].flag.Level != "suspect" || !hasWhy(flags[i].flag, "differs") {
			continue
		}

		for j := range flags {
			if i != j && hasWhy(flags[j].flag, "differs") && !sharesNetwork(flags[i].keys, flags[j].keys) {
				flags[i].flag.Level = "likely"

				break
			}
		}
	}

	for _, f := range flags {
		v.Flags = append(v.Flags, f.flag)
	}

	switch {
	case anyLevel(v.Flags, "likely"):
		v.Kind = "polluted"
	case len(v.Flags) > 0:
		v.Kind = "suspect"
	case answering >= 2:
		v.Kind = "consistent"
	default:
		v.Kind = "single"
	}

	return v
}

func hasWhy(f DNSFlag, why string) bool {
	for _, w := range f.Why {
		if w == why {
			return true
		}
	}

	return false
}

func anyLevel(flags []DNSFlag, level string) bool {
	for _, f := range flags {
		if f.Level == level {
			return true
		}
	}

	return false
}

// Cloudflare names that are often written without their last label, and top-level domains that are often
// mistyped. A suggestion is only a guess, the report words it that way.
var (
	missingSuffix = map[string]string{"workers": "dev", "pages": "dev", "trycloudflare": "com"}
	typoTLD       = map[string]string{
		"con": "com", "cmo": "com", "ocm": "com", "vom": "com", "comm": "com",
		"ogr": "org", "rog": "org", "nte": "net", "ent": "net",
	}
)

// suggestDomain returns a likelier spelling and what kind of mistake it fixes ("typo"), or "single_label" (no dot at
// all, nothing to suggest), or "" "" when nothing stands out.
func suggestDomain(domain string) (suggest, hint string) {
	d := strings.ToLower(strings.Trim(strings.TrimSpace(domain), "."))
	if d == "" {
		return "", ""
	}

	parts := strings.Split(d, ".")

	if len(parts) == 1 {
		return "", "single_label"
	}

	last := parts[len(parts)-1]

	if add, found := missingSuffix[last]; found {
		return d + "." + add, "typo"
	}

	if fix, found := typoTLD[last]; found {
		parts[len(parts)-1] = fix

		return strings.Join(parts, "."), "typo"
	}

	return "", ""
}

// policyEntry is one line of nameserver-policy, in the order of the profile.
type policyEntry struct {
	Key     string
	Servers []string
}

// PolicyMatch says which nameserver-policy entry answers the domain. Total is the number of entries in the profile
// (0: none written). Unjudged are the `geosite:` / `rule-set:` entries met before the decision, which the check
// cannot evaluate: one of them might cover the domain first.
type PolicyMatch struct {
	Total    int      `json:"total"`
	Key      string   `json:"key"`     // the entry as written; "" when no plain entry covers the domain
	Pattern  string   `json:"pattern"` // the pattern of that entry that covers the domain
	Servers  []string `json:"servers"`
	Unjudged []string `json:"unjudged"`
}

func isMatcherPattern(p string) bool {
	l := strings.ToLower(p)

	return strings.HasPrefix(l, "geosite:") || strings.HasPrefix(l, "rule-set:")
}

// expandPolicyKey splits an entry key into its patterns like parseNameServerPolicy in config/config.go: a key with
// commas is several patterns, and after `geosite:` / `rule-set:` the commas separate names of that kind.
func expandPolicyKey(k string) []string {
	lower := strings.ToLower(k)

	if !strings.Contains(lower, ",") {
		return []string{k}
	}

	for _, prefix := range []string{"geosite:", "rule-set:"} {
		if strings.HasPrefix(lower, prefix) {
			parts := strings.Split(k, ":")

			var out []string

			for _, sub := range strings.Split(parts[1], ",") {
				out = append(out, k[:len(prefix)]+sub)
			}

			return out
		}
	}

	return strings.Split(k, ",")
}

// matchPolicy walks the entries like Resolver.matchPolicy in dns/resolver.go: the first policy that matches wins,
// and runs of plain domain patterns are one domain tree (the most specific pattern of the run wins).
func matchPolicy(entries []policyEntry, domain string) PolicyMatch {
	out := PolicyMatch{Total: len(entries), Servers: []string{}, Unjudged: []string{}}
	domain = strings.TrimSuffix(strings.ToLower(domain), ".")

	type item struct {
		pattern string
		entry   int
	}

	// the patterns in order, then cut into runs: [plain plain plain] [matcher] [plain] ...
	var items []item

	for i, e := range entries {
		for _, p := range expandPolicyKey(e.Key) {
			items = append(items, item{p, i})
		}
	}

	for start := 0; start < len(items); {
		if isMatcherPattern(items[start].pattern) {
			out.Unjudged = append(out.Unjudged, items[start].pattern)
			start++

			continue
		}

		trie := newDomainTrie()
		owner := map[string]int{}
		end := start

		for ; end < len(items) && !isMatcherPattern(items[end].pattern); end++ {
			if trie.insert(items[end].pattern) {
				owner[items[end].pattern] = items[end].entry
			}
		}

		if key := trie.search(domain); key != "" {
			e := entries[owner[key]]
			out.Key, out.Pattern = e.Key, key
			out.Servers = append(out.Servers, e.Servers...)

			return out
		}

		start = end
	}

	return out
}

// CoreAnswer is what the core's own resolver answers for the domain, i.e. what its nameserver / fallback /
// nameserver-policy settings really produce (hosts and fake-ip are applied before that and are not part of it).
// Asked is false when the core has no resolver loaded. Matches lists the checked servers whose answer has an
// address in common with the core's.
type CoreAnswer struct {
	Asked   bool     `json:"asked"`
	OK      bool     `json:"ok"`
	Ms      int      `json:"ms"`
	Rcode   int      `json:"rcode"`
	Rname   string   `json:"rname"`
	A       []string `json:"a"`
	AAAA    []string `json:"aaaa"`
	Code    string   `json:"code"`
	Reason  string   `json:"reason"`
	Matches []string `json:"matches"`
}

// coreMatches names the servers that gave an address the core's resolver gave too.
func coreMatches(core CoreAnswer, servers []DNSServer) []string {
	out := []string{}

	want := map[string]bool{}

	for _, a := range core.A {
		want[a] = true
	}

	for _, a := range core.AAAA {
		want[a] = true
	}

	if !core.OK || len(want) == 0 {
		return out
	}

	for _, s := range servers {
		if !s.OK {
			continue
		}

		for _, a := range allAddrs(s) {
			if want[a] {
				out = append(out, s.Address)

				break
			}
		}
	}

	return out
}
