package conftest

// CUSTOM (CUSTOM.md #25, not upstream): the parts of the DNS check that need no network: reading the `dns:` and
// `hosts:` sections, matching domain patterns, and building / reading DNS messages. Standard library only, so it
// can be unit-tested on its own (dnslogic_test.go).

import (
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
)

// Kinds of DNS server the check can talk to.
const (
	kindDoH         = "doh"
	kindDoT         = "dot"
	kindTCP         = "tcp"
	kindUDP         = "udp"
	kindUnsupported = "unsupported"
)

// serverRef is one DNS server as written in the profile, with the section it was written in.
type serverRef struct {
	Role    string
	Address string
}

// dnsConfig is what the check reads from the `dns:` section. Nothing else of the profile is used.
type dnsConfig struct {
	Mode       string // enhanced-mode
	FakeRange  string // fake-ip-range
	FakeRange6 string // fake-ip-range6
	FilterMode string // fake-ip-filter-mode
	Filter     []string
	Listen     string
	Servers    []serverRef

	UseHosts     bool // use-hosts: the core's DNS server answers from `hosts:` (default true)
	IPv6         bool // ipv6: false = the DNS server answers AAAA with an empty answer
	RespectRules bool // respect-rules: servers without #name follow the routing rules
}

// serverSpec is a server address taken apart.
type serverSpec struct {
	Raw      string // as written
	Kind     string
	URL      string // DoH: the https URL without #options
	HostPort string // the others
	Host     string
	Via      string // `#name`: the core reaches this server through a proxy group / interface of that name
	H3       bool   // `#h3=true` on a DoH server
	Scheme   string // the scheme of an unsupported server
}

// normKey makes key names comparable whatever the casing / separators of the struct that was marshalled:
// "fake-ip-range", "FakeIPRange" and "fake_ip_range" all become "fakeiprange".
func normKey(k string) string {
	return strings.ToLower(strings.NewReplacer("-", "", "_", "", " ", "").Replace(k))
}

// parseDNSConfig reads the sections the check needs from the JSON tree of `dns:`.
func parseDNSConfig(tree any) dnsConfig {
	cfg := dnsConfig{UseHosts: true} // the core's default

	top, ok := tree.(map[string]any)
	if !ok {
		return cfg
	}

	keys := make([]string, 0, len(top))
	for k := range top {
		keys = append(keys, k)
	}

	sort.Strings(keys) // the same order every time

	for _, k := range keys {
		v := top[k]
		n := normKey(k)

		switch {
		case n == "enhancedmode":
			cfg.Mode, _ = v.(string)
		case n == "fakeiprange":
			cfg.FakeRange, _ = v.(string)
		case n == "fakeiprange6":
			cfg.FakeRange6, _ = v.(string)
		case n == "usehosts":
			if b, ok := v.(bool); ok {
				cfg.UseHosts = b
			}
		case n == "ipv6":
			cfg.IPv6, _ = v.(bool)
		case n == "respectrules":
			cfg.RespectRules, _ = v.(bool)
		case n == "fakeipfiltermode":
			cfg.FilterMode, _ = v.(string)
		case n == "fakeipfilter":
			cfg.Filter = stringsIn(v)
		case n == "listen":
			cfg.Listen, _ = v.(string)
		case n == "fallback":
			cfg.add("fallback", v, false)
		case strings.Contains(n, "nameserver"):
			// `nameserver`, `default-nameserver`, `proxy-server-nameserver`, `direct-nameserver` and the
			// `...-policy` maps. For a policy, only values that look like a server count (its keys are domains).
			cfg.add(k, v, strings.Contains(n, "policy"))
		}
	}

	return cfg
}

func (c *dnsConfig) add(role string, v any, strict bool) {
	for _, s := range stringsIn(v) {
		if strict && !strictServer(s) {
			continue
		}

		c.Servers = append(c.Servers, serverRef{Role: role, Address: s})
	}
}

// stringsIn collects every string inside a JSON value (a string, a list, or the values of a map).
func stringsIn(v any) []string {
	var out []string

	switch t := v.(type) {
	case string:
		if s := strings.TrimSpace(t); s != "" {
			out = append(out, s)
		}
	case []any:
		for _, e := range t {
			out = append(out, stringsIn(e)...)
		}
	case map[string]any:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}

		sort.Strings(keys)

		for _, k := range keys {
			out = append(out, stringsIn(t[k])...)
		}
	}

	return out
}

// strictServer: a scheme, or an IP address (with an optional port). Used where the strings may also be
// domain patterns, which are not servers.
func strictServer(s string) bool {
	if strings.Contains(s, "://") {
		return true
	}

	base := s
	if i := strings.IndexByte(base, '#'); i >= 0 {
		base = base[:i]
	}

	if _, err := netip.ParseAddr(strings.Trim(base, "[]")); err == nil {
		return true
	}

	if _, err := netip.ParseAddrPort(base); err == nil {
		return true
	}

	return false
}

// parseServer takes a server address apart the way the core reads `nameserver:` entries.
func parseServer(raw string) serverSpec {
	spec := serverSpec{Raw: raw, Kind: kindUnsupported}

	base := strings.TrimSpace(raw)

	if i := strings.IndexByte(base, '#'); i >= 0 {
		for _, part := range strings.Split(base[i+1:], "&") {
			switch {
			case part == "":
			case strings.EqualFold(part, "h3=true"):
				spec.H3 = true
			case strings.Contains(part, "="):
				// ecs, disable-ipv6 and the like: no effect on whether the server answers
			case !strings.EqualFold(part, "DIRECT"):
				spec.Via = part
			}
		}

		base = base[:i]
	}

	scheme := ""
	rest := base

	if i := strings.Index(base, "://"); i >= 0 {
		scheme = strings.ToLower(base[:i])
		rest = base[i+3:]
	}

	switch scheme {
	case "https", "http": // http:// is a DoH server without TLS (the core allows it)
		spec.Kind = kindDoH
		spec.URL = base
	case "tls":
		spec.Kind, spec.HostPort, spec.Host = kindDoT, hostPort(rest, "853"), hostOf(rest)
	case "tcp":
		spec.Kind, spec.HostPort, spec.Host = kindTCP, hostPort(rest, "53"), hostOf(rest)
	case "udp", "":
		if scheme == "" && (strings.EqualFold(rest, "system") || rest == "") {
			spec.Scheme = "system"

			break
		}

		spec.Kind, spec.HostPort, spec.Host = kindUDP, hostPort(rest, "53"), hostOf(rest)
	default:
		spec.Scheme = scheme // quic, h3, dhcp, rcode, ...
	}

	return spec
}

// hostPort adds the default port when the address has none. IPv6 literals may be bracketed or bare.
func hostPort(addr, port string) string {
	addr = strings.TrimSuffix(addr, "/")

	if h, p, err := net.SplitHostPort(addr); err == nil && p != "" {
		return net.JoinHostPort(h, p)
	}

	return net.JoinHostPort(strings.Trim(addr, "[]"), port)
}

func hostOf(addr string) string {
	h, _, err := net.SplitHostPort(hostPort(addr, "0"))
	if err != nil {
		return addr
	}

	return h
}

// domainTrie is a port of the core's component/trie/domain.go (mihomo 3f27ef39, the commit this fork builds
// with), reduced to what the check needs: patterns `a.b` (exact), `*.b` (one label), `a.*.b`, `.b` (everything below
// b, not b itself, at any depth) and `+.b` (b itself and everything below). Labels are stored reversed; a search
// tries the static label first, then `*`, and last the dot-wildcard of the node it stopped at.
type domainTrie struct {
	root *trieNode
}

type trieNode struct {
	children map[string]*trieNode
	key      string // the pattern as written; "" = no pattern ends here
}

func newDomainTrie() *domainTrie {
	return &domainTrie{root: &trieNode{}}
}

func (n *trieNode) child(label string) *trieNode {
	if n == nil {
		return nil
	}

	return n.children[label]
}

func (n *trieNode) isEmpty() bool {
	return n == nil || n.key == ""
}

// splitPattern lower-cases and splits a pattern; ok is false for what the core refuses (empty, trailing dot, an empty
// label after the first, `*` / `+` inside a label, `+` anywhere but the whole first label).
func splitPattern(domain string) ([]string, bool) {
	if domain == "" || strings.HasSuffix(domain, ".") || domain != strings.TrimSpace(domain) {
		return nil, false
	}

	parts := strings.Split(strings.ToLower(domain), ".")

	if len(parts) == 1 && parts[0] == "" {
		return nil, false
	}

	for i, part := range parts {
		if i > 0 && part == "" {
			return nil, false
		}

		if strings.Contains(part, "+") && (part != "+" || i != 0 || len(parts) == 1) {
			return nil, false
		}

		if strings.Contains(part, "*") && part != "*" {
			return nil, false
		}
	}

	return parts, true
}

func (t *domainTrie) insert(pattern string) bool {
	parts, ok := splitPattern(pattern)
	if !ok {
		return false
	}

	if parts[0] == "+" {
		t.put(parts[1:], pattern)

		parts[0] = ""
	}

	t.put(parts, pattern)

	return true
}

func (t *domainTrie) put(parts []string, key string) {
	node := t.root

	for i := len(parts) - 1; i >= 0; i-- {
		if node.children == nil {
			node.children = map[string]*trieNode{}
		}

		next := node.children[parts[i]]
		if next == nil {
			next = &trieNode{}
			node.children[parts[i]] = next
		}

		node = next
	}

	node.key = key
}

// search returns the pattern that covers the domain ("" when none does).
func (t *domainTrie) search(domain string) string {
	parts, ok := splitPattern(domain)
	if !ok || parts[0] == "" {
		return ""
	}

	if n := t.find(t.root, parts); !n.isEmpty() {
		return n.key
	}

	return ""
}

func (t *domainTrie) find(node *trieNode, parts []string) *trieNode {
	if len(parts) == 0 {
		return node
	}

	last := len(parts) - 1

	if c := node.child(parts[last]); c != nil {
		if n := t.find(c, parts[:last]); !n.isEmpty() {
			return n
		}
	}

	if c := node.child("*"); c != nil {
		if n := t.find(c, parts[:last]); !n.isEmpty() {
			return n
		}
	}

	return node.child("")
}

// matchDomain tells whether one pattern (a `hosts:` key or a fake-ip-filter entry) covers a domain.
func matchDomain(pattern, domain string) bool {
	t := newDomainTrie()

	return t.insert(strings.TrimSpace(pattern)) && t.search(strings.TrimSuffix(domain, ".")) != ""
}

// matchHosts finds the `hosts:` entry that covers a domain, with the core's priority, and its values.
func matchHosts(hosts map[string]any, domain string) (key string, values []string) {
	keys := make([]string, 0, len(hosts))
	for k := range hosts {
		keys = append(keys, k)
	}

	sort.Strings(keys)

	t := newDomainTrie()

	for _, k := range keys {
		t.insert(k)
	}

	key = t.search(strings.TrimSuffix(domain, "."))
	if key == "" {
		return "", nil
	}

	return key, stringsIn(hosts[key])
}

// hostsAlias: a hosts entry with one value that is not an IP address points at another domain (the core answers
// with a CNAME and looks that domain up instead).
func hostsAlias(values []string) (string, bool) {
	if len(values) != 1 {
		return "", false
	}

	if _, err := netip.ParseAddr(values[0]); err == nil {
		return "", false
	}

	return strings.Trim(values[0], "."), true
}

// filterMatch checks a domain against the fake-ip-filter entries. hit is the entry that covers it; unknown is
// the first entry that cannot be judged here (geosite:, rule-set:, ...) when none covers it.
func filterMatch(patterns []string, domain string) (hit, unknown string) {
	for _, p := range patterns {
		if strings.Contains(p, ":") {
			if unknown == "" {
				unknown = p
			}

			continue
		}

		if matchDomain(p, domain) {
			return p, ""
		}
	}

	return "", unknown
}

// Where the answer for a domain comes from.
const (
	finalHosts   = "hosts"  // a hosts entry answers, DNS is not asked
	finalFakeIP  = "fakeip" // the core hands out an address of the fake-ip pool
	finalReal    = "real"   // the real address from the DNS servers
	finalUnknown = "unknown"
)

// inferFinal says what the core answers an app that asks for the domain, from the profile alone: hosts come
// first, then the fake-ip pool unless the filter lets the real address through.
func inferFinal(cfg dnsConfig, hostsKey, filterHit, filterUnknown string) string {
	if hostsKey != "" {
		return finalHosts
	}

	if !strings.EqualFold(strings.ReplaceAll(cfg.Mode, "-", ""), "fakeip") {
		return finalReal
	}

	mode := strings.ToLower(cfg.FilterMode)

	switch {
	case mode == "rule":
		return finalUnknown // entries carry their own `:fake-ip` / `:real-ip`
	case filterHit != "":
		if mode == "whitelist" {
			return finalFakeIP
		}

		return finalReal
	case filterUnknown != "":
		return finalUnknown
	case mode == "whitelist":
		return finalReal
	}

	return finalFakeIP
}

// inPrefix tells whether an address lies in a CIDR (false when either does not parse).
func inPrefix(addr, prefix string) bool {
	p, err := netip.ParsePrefix(strings.TrimSpace(prefix))
	if err != nil {
		return false
	}

	a, err := netip.ParseAddr(addr)
	if err != nil {
		return false
	}

	return p.Contains(a.Unmap())
}

// DNS record types the check asks for.
const (
	typeA    = 1
	typeAAAA = 28
)

// buildQuery makes the wire form of "record of type qtype for name, recursion desired".
func buildQuery(name string, qtype uint16, id uint16) ([]byte, error) {
	name = strings.TrimSuffix(name, ".")
	if name == "" || len(name) > 253 {
		return nil, errors.New("bad name")
	}

	b := []byte{byte(id >> 8), byte(id), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}

	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 {
			return nil, errors.New("bad name")
		}

		b = append(b, byte(len(label)))
		b = append(b, label...)
	}

	b = append(b, 0, 0, 0, 0, 1) // end of the name, type (set below), class IN
	binary.BigEndian.PutUint16(b[len(b)-4:], qtype)

	return b, nil
}

func dnsSkipName(msg []byte, off int) (int, error) {
	for hops := 0; hops < 128; hops++ {
		if off >= len(msg) {
			return 0, errors.New("short name")
		}

		n := int(msg[off])

		switch {
		case n == 0:
			return off + 1, nil
		case n&0xC0 == 0xC0:
			if off+2 > len(msg) {
				return 0, errors.New("short pointer")
			}

			return off + 2, nil
		case n&0xC0 != 0:
			return 0, errors.New("bad label")
		default:
			off += 1 + n
		}
	}

	return 0, errors.New("name too long")
}

// dnsAnswer is what the check needs from a reply.
type dnsAnswer struct {
	Rcode     int
	Truncated bool
	Addrs     []string // A and AAAA records, in the order of the reply
}

// parseReply reads the header and the A / AAAA records of a reply; wantID is the id of the query.
func parseReply(msg []byte, wantID uint16) (dnsAnswer, error) {
	var ans dnsAnswer

	if len(msg) < 12 {
		return ans, errors.New("not a DNS message (too short)")
	}

	if binary.BigEndian.Uint16(msg) != wantID {
		return ans, errors.New("not a DNS reply to this query (id differs)")
	}

	if msg[2]&0x80 == 0 {
		return ans, errors.New("not a DNS reply (QR bit is not set)")
	}

	ans.Truncated = msg[2]&0x02 != 0
	ans.Rcode = int(msg[3] & 0x0F)

	if ans.Truncated {
		return ans, nil // cut off by the server: the caller asks again over TCP, the body is not worth reading
	}

	qd := int(binary.BigEndian.Uint16(msg[4:]))
	an := int(binary.BigEndian.Uint16(msg[6:]))
	off := 12

	var err error

	for i := 0; i < qd; i++ {
		if off, err = dnsSkipName(msg, off); err != nil {
			return ans, err
		}

		off += 4
	}

	for i := 0; i < an; i++ {
		if off, err = dnsSkipName(msg, off); err != nil {
			return ans, err
		}

		if off+10 > len(msg) {
			return ans, errors.New("short record")
		}

		rtype := binary.BigEndian.Uint16(msg[off:])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8:]))
		off += 10

		if off+rdlen > len(msg) {
			return ans, errors.New("short record data")
		}

		rdata := msg[off : off+rdlen]
		off += rdlen

		switch {
		case rtype == typeA && rdlen == 4, rtype == typeAAAA && rdlen == 16:
			if a, ok := netip.AddrFromSlice(rdata); ok {
				ans.Addrs = append(ans.Addrs, a.String())
			}
		}
	}

	return ans, nil
}

// rcodeName names the DNS response codes a server is likely to answer with.
func rcodeName(rcode int) string {
	switch rcode {
	case 0:
		return "NOERROR"
	case 1:
		return "FORMERR"
	case 2:
		return "SERVFAIL"
	case 3:
		return "NXDOMAIN"
	case 4:
		return "NOTIMP"
	case 5:
		return "REFUSED"
	}

	return "RCODE" + strconv.Itoa(rcode)
}
