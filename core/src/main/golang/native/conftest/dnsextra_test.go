package conftest

import "testing"

func srv(addr, kind string, a, aaaa []string) DNSServer {
	return DNSServer{Address: addr, Kind: kind, OK: true, Rname: "NOERROR", A: a, AAAA: aaaa}
}

func nx(addr, kind string) DNSServer {
	return DNSServer{Address: addr, Kind: kind, OK: true, Rname: "NXDOMAIN"}
}

func flagOf(v DNSVerdict, addr string) *DNSFlag {
	for i := range v.Flags {
		if v.Flags[i].Address == addr {
			return &v.Flags[i]
		}
	}

	return nil
}

// the answers of a real run: five plain servers each gave another unrelated address (one of them a Teredo IPv6
// address), the two DoH servers gave Cloudflare addresses
func TestVerdictPolluted(t *testing.T) {
	cf4 := []string{"172.67.137.153", "104.21.86.232"}
	cf6 := []string{"2606:4700:3037::6815:56e8"}

	servers := []DNSServer{
		srv("114.114.114.114", kindUDP, []string{"104.244.43.52"}, []string{"2a03:2880:f112:83:face:b00c:0:25de"}),
		srv("223.5.5.5", kindUDP, []string{"69.162.134.178"}, []string{"2001::42dc:9512"}),
		srv("8.8.8.8", kindUDP, []string{"107.181.166.244"}, nil),
		srv("https://a/x", kindDoH, cf4, cf6),
		srv("https://b/x", kindDoH, cf4, cf6),
	}

	v := analyzeVerdict(servers, "x.workers.dev")

	if v.Kind != "polluted" {
		t.Fatalf("kind = %q, want polluted", v.Kind)
	}

	if f := flagOf(v, "223.5.5.5"); f == nil || f.Level != "likely" || !hasWhy(*f, "bogon") {
		t.Fatalf("223.5.5.5 flag = %+v, want likely with bogon", f)
	}

	if f := flagOf(v, "114.114.114.114"); f == nil || f.Level != "likely" || !hasWhy(*f, "differs") {
		t.Fatalf("114.114.114.114 flag = %+v, want likely with differs", f)
	}

	if flagOf(v, "https://a/x") != nil {
		t.Fatal("an encrypted server must not be flagged")
	}

	if len(v.Trusted) != 3 {
		t.Fatalf("trusted = %v, want the three distinct Cloudflare addresses", v.Trusted)
	}
}

func TestVerdictConsistent(t *testing.T) {
	a := []string{"104.21.86.232"}

	v := analyzeVerdict([]DNSServer{
		srv("223.5.5.5", kindUDP, a, nil),
		srv("https://a/x", kindDoH, []string{"104.21.1.1"}, nil), // same /16 network as the plain answer
	}, "example.com")

	if v.Kind != "consistent" || len(v.Flags) != 0 {
		t.Fatalf("verdict = %+v, want consistent without flags", v)
	}
}

// a single plain server that differs from the encrypted ones is only a suspicion, not proof
func TestVerdictSuspect(t *testing.T) {
	v := analyzeVerdict([]DNSServer{
		srv("223.5.5.5", kindUDP, []string{"8.8.4.4"}, nil),
		srv("https://a/x", kindDoH, []string{"104.21.1.1"}, nil),
	}, "example.com")

	if v.Kind != "suspect" {
		t.Fatalf("kind = %q, want suspect", v.Kind)
	}
}

func TestVerdictNxdomainAll(t *testing.T) {
	v := analyzeVerdict([]DNSServer{nx("223.5.5.5", kindUDP), nx("https://a/x", kindDoH)}, "geng-xin.uf0.workers")

	if v.Kind != "nxdomain_all" || v.Suggest != "geng-xin.uf0.workers.dev" || v.Hint != "typo" {
		t.Fatalf("verdict = %+v, want nxdomain_all suggesting the .dev name", v)
	}
}

func TestVerdictNxdomainMixed(t *testing.T) {
	v := analyzeVerdict([]DNSServer{
		nx("223.5.5.5", kindUDP),
		srv("https://a/x", kindDoH, []string{"104.21.1.1"}, nil),
	}, "example.com")

	if f := flagOf(v, "223.5.5.5"); f == nil || f.Level != "likely" || !hasWhy(*f, "nx") {
		t.Fatalf("flag = %+v, want likely with nx", f)
	}
}

func TestVerdictNone(t *testing.T) {
	v := analyzeVerdict([]DNSServer{{Address: "x", Kind: kindUDP}}, "example.com")

	if v.Kind != "none" {
		t.Fatalf("kind = %q, want none", v.Kind)
	}
}

func TestBogon(t *testing.T) {
	for _, a := range []string{"10.1.2.3", "127.0.0.1", "0.0.0.0", "100.64.1.1", "198.19.1.1", "2001::42dc:9512", "::1", "fe80::1"} {
		if !bogon(a) {
			t.Errorf("%s should be a bogon", a)
		}
	}

	for _, a := range []string{"104.21.86.232", "8.8.8.8", "2606:4700:3037::6815:56e8", "2a03:2880:f112:83:face:b00c:0:25de"} {
		if bogon(a) {
			t.Errorf("%s should not be a bogon", a)
		}
	}
}

func TestSuggestDomain(t *testing.T) {
	cases := []struct{ in, want, hint string }{
		{"a.b.workers", "a.b.workers.dev", "typo"},
		{"x.pages", "x.pages.dev", "typo"},
		{"example.con", "example.com", "typo"},
		{"example.com", "", ""},
		{"localhost", "", "single_label"},
		{"", "", ""},
	}

	for _, c := range cases {
		got, hint := suggestDomain(c.in)
		if got != c.want || hint != c.hint {
			t.Errorf("suggestDomain(%q) = %q, %q; want %q, %q", c.in, got, hint, c.want, c.hint)
		}
	}
}

func TestMatchPolicy(t *testing.T) {
	entries := []policyEntry{
		{"geosite:cn", []string{"223.5.5.5"}},
		{"+.google.com,+.youtube.com", []string{"https://dns.google/dns-query"}},
		{"*.lan", []string{"192.168.1.1"}},
	}

	m := matchPolicy(entries, "www.google.com")
	if m.Key != "+.google.com,+.youtube.com" || m.Pattern != "+.google.com" || len(m.Servers) != 1 || m.Total != 3 {
		t.Fatalf("match = %+v", m)
	}

	if len(m.Unjudged) != 1 || m.Unjudged[0] != "geosite:cn" {
		t.Fatalf("the geosite entry before the hit must be reported as unjudged, got %v", m.Unjudged)
	}

	if m := matchPolicy(entries, "example.org"); m.Key != "" || len(m.Unjudged) != 1 {
		t.Fatalf("no plain entry covers example.org, got %+v", m)
	}

	if m := matchPolicy(nil, "example.org"); m.Total != 0 || m.Key != "" {
		t.Fatalf("empty policy, got %+v", m)
	}
}

func TestExpandPolicyKey(t *testing.T) {
	got := expandPolicyKey("geosite:cn,private")
	if len(got) != 2 || got[0] != "geosite:cn" || got[1] != "geosite:private" {
		t.Fatalf("expand = %v", got)
	}

	got = expandPolicyKey("+.a.com,+.b.com")
	if len(got) != 2 || got[1] != "+.b.com" {
		t.Fatalf("expand = %v", got)
	}
}

func TestCoreMatches(t *testing.T) {
	core := CoreAnswer{OK: true, A: []string{"1.1.1.1"}}

	got := coreMatches(core, []DNSServer{
		srv("a", kindUDP, []string{"1.1.1.1"}, nil),
		srv("b", kindUDP, []string{"2.2.2.2"}, nil),
	})

	if len(got) != 1 || got[0] != "a" {
		t.Fatalf("matches = %v", got)
	}
}
