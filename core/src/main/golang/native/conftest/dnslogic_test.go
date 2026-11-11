package conftest

import (
	"encoding/binary"
	"reflect"
	"testing"
)

func TestMatchDomain(t *testing.T) {
	cases := []struct {
		pattern, domain string
		want            bool
	}{
		{"a.example.com", "a.example.com", true},
		{"A.Example.com", "a.example.com", true},
		{"a.example.com.", "a.example.com", false}, // the core's trie refuses a pattern with a trailing dot
		{"+", "a.com", false},
		{"a.+.com", "a.b.com", false},
		{"+.com", "com", true},
		{"a.example.com", "b.example.com", false},
		{"*.example.com", "a.example.com", true},
		{"*.example.com", "a.b.example.com", false},
		{"*.example.com", "example.com", false},
		{"+.example.com", "example.com", true},
		{"+.example.com", "a.b.example.com", true},
		{"+.example.com", "badexample.com", false},
		{".example.com", "a.example.com", true},
		{".example.com", "example.com", false},
		{"time.*.com", "time.apple.com", true},
		{"time.*.com", "time.a.apple.com", false},
		{"", "a.com", false},
	}

	for _, c := range cases {
		if got := matchDomain(c.pattern, c.domain); got != c.want {
			t.Errorf("matchDomain(%q, %q) = %v, want %v", c.pattern, c.domain, got, c.want)
		}
	}
}

func TestMatchHosts(t *testing.T) {
	hosts := map[string]any{
		"+.example.com":     "1.1.1.1",
		"www.example.com":   []any{"2.2.2.2", "2606:4700::1"},
		"alias.example.org": "www.example.com",
	}

	if k, v := matchHosts(hosts, "www.example.com"); k != "www.example.com" || len(v) != 2 {
		t.Errorf("exact key must win: %q %v", k, v)
	}

	if k, v := matchHosts(hosts, "x.example.com"); k != "+.example.com" || !reflect.DeepEqual(v, []string{"1.1.1.1"}) {
		t.Errorf("wildcard: %q %v", k, v)
	}

	if k, _ := matchHosts(hosts, "other.net"); k != "" {
		t.Errorf("no entry expected, got %q", k)
	}

	// priority of the core's trie: exact > "*" label > "+" / "." suffix
	prio := map[string]any{
		"+.example.com":   "1.1.1.1",
		"*.example.com":   "2.2.2.2",
		"a.example.com":   "3.3.3.3",
		".example.com":    "4.4.4.4",
		"a.b.example.com": "5.5.5.5",
	}

	if k, _ := matchHosts(prio, "a.example.com"); k != "a.example.com" {
		t.Errorf("exact must win over wildcards, got %q", k)
	}

	if k, _ := matchHosts(prio, "z.example.com"); k != "*.example.com" {
		t.Errorf("* must win over +, got %q", k)
	}
}

func TestHostsAlias(t *testing.T) {
	if a, ok := hostsAlias([]string{"www.example.com"}); !ok || a != "www.example.com" {
		t.Errorf("single non-IP value is an alias, got %q %v", a, ok)
	}

	if _, ok := hostsAlias([]string{"1.2.3.4"}); ok {
		t.Error("an IP is not an alias")
	}

	if _, ok := hostsAlias([]string{"a.com", "b.com"}); ok {
		t.Error("several values are not an alias")
	}
}

func TestParseServer(t *testing.T) {
	cases := []struct {
		raw  string
		kind string
		hp   string
		url  string
		via  string
		h3   bool
		sch  string
	}{
		{"223.5.5.5", kindUDP, "223.5.5.5:53", "", "", false, ""},
		{"223.5.5.5:5353", kindUDP, "223.5.5.5:5353", "", "", false, ""},
		{"udp://1.1.1.1", kindUDP, "1.1.1.1:53", "", "", false, ""},
		{"2001:4860:4860::8888", kindUDP, "[2001:4860:4860::8888]:53", "", "", false, ""},
		{"[::1]:1053", kindUDP, "[::1]:1053", "", "", false, ""},
		{"tcp://8.8.8.8", kindTCP, "8.8.8.8:53", "", "", false, ""},
		{"tls://dns.google", kindDoT, "dns.google:853", "", "", false, ""},
		{"tls://dns.google:8853", kindDoT, "dns.google:8853", "", "", false, ""},
		{"https://doh.pub/dns-query", kindDoH, "", "https://doh.pub/dns-query", "", false, ""},
		{"https://doh.pub/dns-query#PROXY&h3=true", kindDoH, "", "https://doh.pub/dns-query", "PROXY", true, ""},
		{"https://doh.pub/dns-query#DIRECT", kindDoH, "", "https://doh.pub/dns-query", "", false, ""},
		{"http://doh.pub/dns-query", kindDoH, "", "http://doh.pub/dns-query", "", false, ""},
		{"quic://dns.adguard.com", kindUnsupported, "", "", "", false, "quic"},
		{"dhcp://en0", kindUnsupported, "", "", "", false, "dhcp"},
		{"system", kindUnsupported, "", "", "", false, "system"},
	}

	for _, c := range cases {
		got := parseServer(c.raw)

		if got.Kind != c.kind || got.HostPort != c.hp || got.URL != c.url || got.Via != c.via || got.H3 != c.h3 || got.Scheme != c.sch {
			t.Errorf("parseServer(%q) = %+v", c.raw, got)
		}
	}
}

func TestParseDNSConfig(t *testing.T) {
	// the same section as it looks with yaml-style keys and with Go field names
	trees := []any{
		map[string]any{
			"enhanced-mode":           "fake-ip",
			"fake-ip-range":           "198.18.0.1/16",
			"fake-ip-filter":          []any{"+.lan", "geosite:cn"},
			"listen":                  "0.0.0.0:1053",
			"nameserver":              []any{"https://doh.pub/dns-query", "223.5.5.5"},
			"fallback":                []any{"tls://8.8.8.8"},
			"fallback-filter":         map[string]any{"geoip": true, "domain": []any{"+.google.com"}},
			"default-nameserver":      []any{"119.29.29.29"},
			"nameserver-policy":       map[string]any{"geosite:cn": "223.5.5.5", "+.lan": []any{"system"}},
			"proxy-server-nameserver": []any{"https://dns.alidns.com/dns-query"},
		},
		map[string]any{
			"EnhancedMode":          "fake-ip",
			"FakeIPRange":           "198.18.0.1/16",
			"FakeIPFilter":          []any{"+.lan", "geosite:cn"},
			"Listen":                "0.0.0.0:1053",
			"NameServer":            []any{"https://doh.pub/dns-query", "223.5.5.5"},
			"Fallback":              []any{"tls://8.8.8.8"},
			"FallbackFilter":        map[string]any{"GeoIP": true},
			"DefaultNameserver":     []any{"119.29.29.29"},
			"NameServerPolicy":      []any{map[string]any{"Key": "geosite:cn", "Value": "223.5.5.5"}},
			"ProxyServerNameserver": []any{"https://dns.alidns.com/dns-query"},
		},
	}

	for i, tree := range trees {
		cfg := parseDNSConfig(tree)

		if cfg.Mode != "fake-ip" || cfg.FakeRange != "198.18.0.1/16" || cfg.Listen != "0.0.0.0:1053" {
			t.Errorf("tree %d: scalars %+v", i, cfg)
		}

		if !reflect.DeepEqual(cfg.Filter, []string{"+.lan", "geosite:cn"}) {
			t.Errorf("tree %d: filter %v", i, cfg.Filter)
		}

		got := map[string]bool{}
		for _, s := range cfg.Servers {
			got[s.Address] = true
		}

		for _, want := range []string{"https://doh.pub/dns-query", "223.5.5.5", "tls://8.8.8.8", "119.29.29.29", "https://dns.alidns.com/dns-query"} {
			if !got[want] {
				t.Errorf("tree %d: server %q not found in %+v", i, want, cfg.Servers)
			}
		}

		if got["geosite:cn"] || got["+.google.com"] || got["+.lan"] || got["true"] {
			t.Errorf("tree %d: something that is not a server was taken as one: %+v", i, cfg.Servers)
		}
	}

	if cfg := parseDNSConfig(nil); len(cfg.Servers) != 0 || !cfg.UseHosts {
		t.Errorf("nil tree: %+v", cfg)
	}
}

func TestParseDNSConfigFlags(t *testing.T) {
	cfg := parseDNSConfig(map[string]any{
		"use-hosts":      false,
		"ipv6":           true,
		"respect-rules":  true,
		"fake-ip-range6": "fdfe:dcba:9876::1/64",
	})

	if cfg.UseHosts || !cfg.IPv6 || !cfg.RespectRules || cfg.FakeRange6 != "fdfe:dcba:9876::1/64" {
		t.Errorf("flags: %+v", cfg)
	}

	cfg = parseDNSConfig(map[string]any{"UseHosts": false, "IPv6": true, "RespectRules": true, "FakeIPRange6": "fdfe::1/64"})
	if cfg.UseHosts || !cfg.IPv6 || !cfg.RespectRules || cfg.FakeRange6 != "fdfe::1/64" {
		t.Errorf("flags (Go names): %+v", cfg)
	}
}

func TestInferFinal(t *testing.T) {
	fake := dnsConfig{Mode: "fake-ip"}

	if got := inferFinal(fake, "k", "", ""); got != finalHosts {
		t.Errorf("hosts first: %s", got)
	}

	if got := inferFinal(fake, "", "", ""); got != finalFakeIP {
		t.Errorf("fake-ip: %s", got)
	}

	if got := inferFinal(fake, "", "+.lan", ""); got != finalReal {
		t.Errorf("filtered: %s", got)
	}

	if got := inferFinal(fake, "", "", "geosite:cn"); got != finalUnknown {
		t.Errorf("unknown filter entry: %s", got)
	}

	white := dnsConfig{Mode: "fake-ip", FilterMode: "whitelist"}

	if got := inferFinal(white, "", "+.x.com", ""); got != finalFakeIP {
		t.Errorf("whitelist hit: %s", got)
	}

	if got := inferFinal(white, "", "", ""); got != finalReal {
		t.Errorf("whitelist miss: %s", got)
	}

	if got := inferFinal(dnsConfig{Mode: "redir-host"}, "", "", ""); got != finalReal {
		t.Errorf("redir-host: %s", got)
	}
}

func TestInPrefix(t *testing.T) {
	if !inPrefix("198.18.3.4", "198.18.0.1/16") || inPrefix("1.1.1.1", "198.18.0.1/16") {
		t.Error("v4 prefix")
	}

	if !inPrefix("::ffff:198.18.3.4", "198.18.0.1/16") {
		t.Error("mapped address")
	}

	if inPrefix("x", "198.18.0.1/16") || inPrefix("1.1.1.1", "bad") {
		t.Error("bad input must be false")
	}
}

func dnsReply(id uint16, rcode byte, name string, answers ...[]byte) []byte {
	q, _ := buildQuery(name, typeA, id)
	msg := append([]byte(nil), q...)
	msg[2] = 0x81 // QR, RD
	msg[3] = 0x80 | rcode
	binary.BigEndian.PutUint16(msg[6:], uint16(len(answers)))

	for _, rdata := range answers {
		typ := uint16(typeA)
		if len(rdata) == 16 {
			typ = typeAAAA
		}

		rr := []byte{0xC0, 0x0C, 0, 0, 0, 1, 0, 0, 0, 60, 0, 0}
		binary.BigEndian.PutUint16(rr[2:], typ)
		binary.BigEndian.PutUint16(rr[10:], uint16(len(rdata)))
		msg = append(append(msg, rr...), rdata...)
	}

	return msg
}

func TestBuildDNSQuery(t *testing.T) {
	q, err := buildQuery("www.example.com.", typeAAAA, 0x1234)
	if err != nil {
		t.Fatal(err)
	}

	want := []byte{0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
		3, 'w', 'w', 'w', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0,
		0, 28, 0, 1}

	if !reflect.DeepEqual(q, want) {
		t.Errorf("query bytes:\n got %v\nwant %v", q, want)
	}

	if _, err := buildQuery("", typeA, 1); err == nil {
		t.Error("empty name must fail")
	}

	if _, err := buildQuery("a..b", typeA, 1); err == nil {
		t.Error("empty label must fail")
	}
}

func TestParseReply(t *testing.T) {
	msg := dnsReply(7, 0, "a.com", []byte{1, 2, 3, 4}, []byte{0x26, 0x06, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1})

	ans, err := parseReply(msg, 7)
	if err != nil || ans.Rcode != 0 || !reflect.DeepEqual(ans.Addrs, []string{"1.2.3.4", "2606:4700::1"}) {
		t.Errorf("answer %+v err %v", ans, err)
	}

	if _, err := parseReply(msg, 8); err == nil {
		t.Error("a reply to another query must be refused")
	}

	if ans, err := parseReply(dnsReply(7, 3, "a.com"), 7); err != nil || ans.Rcode != 3 || len(ans.Addrs) != 0 {
		t.Errorf("nxdomain %+v err %v", ans, err)
	}

	query, _ := buildQuery("a.com", typeA, 7)
	if _, err := parseReply(query, 7); err == nil {
		t.Error("a query (QR not set) must be refused")
	}

	if _, err := parseReply([]byte(`{"Status":0}`), 7); err == nil {
		t.Error("JSON text is not a DNS message")
	}

	if _, err := parseReply(msg[:20], 7); err == nil {
		t.Error("a cut message must fail")
	}
}

func TestParseReplyTruncated(t *testing.T) {
	msg := dnsReply(7, 0, "a.com")[:12]
	msg[2] |= 0x02

	ans, err := parseReply(msg, 7)
	if err != nil || !ans.Truncated {
		t.Errorf("a cut-off reply must be reported as truncated, not as an error: %+v %v", ans, err)
	}
}
