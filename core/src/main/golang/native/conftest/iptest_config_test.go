package conftest

import (
	"context"
	"net/netip"
	"sort"
	"testing"
)

// CUSTOM.md #69: reading the addresses of a profile. Written without being run (no Go in the environment where
// it was written); the expected values were worked out by hand.

func table(answers map[string]dnsResult, asked *[]string) func(context.Context, string) dnsResult {
	return func(_ context.Context, d string) dnsResult {
		if asked != nil {
			*asked = append(*asked, d)
		}

		if r, ok := answers[d]; ok {
			return r
		}

		return dnsResult{Reason: "failed", Detail: "no such"}
	}
}

func addrs(list ...string) []netip.Addr {
	out := make([]netip.Addr, 0, len(list))

	for _, s := range list {
		out = append(out, netip.MustParseAddr(s))
	}

	return out
}

func ipsOf(r ConfigRead) []string {
	out := []string{}
	for _, c := range r.IPs {
		out = append(out, c.IP)
	}

	sort.Strings(out)

	return out
}

func same(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}

	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}

	return true
}

func TestCollectConfigBasics(t *testing.T) {
	proxies := map[string]map[string]any{
		"lit":     {"server": "162.159.153.2"},
		"six":     {"server": "[2606:4700::1]"},
		"viahost": {"server": "node.example.com"},
		"dom":     {"server": "dns.example.net"},
		"nosrv":   {"type": "direct"},
		"v4only":  {"server": "both.example.org", "ip-version": "ipv4"},
		"chained": {"server": "5.5.5.5", "dialer-proxy": "other"},
	}
	hosts := map[string]any{
		"node.example.com": []any{"1.1.1.1", "1.0.0.1", "not an ip!"},
		"unrelated.doh.xx": "9.9.9.9",
	}

	var asked []string

	ans := map[string]dnsResult{
		"dns.example.net":  {IPs: addrs("2.2.2.2")},
		"both.example.org": {IPs: addrs("3.3.3.3", "2606:4700::9")},
	}

	r := collectConfig(context.Background(), proxies, hosts, table(ans, &asked))

	want := []string{"1.0.0.1", "1.1.1.1", "162.159.153.2", "2.2.2.2", "2606:4700::1", "3.3.3.3", "5.5.5.5"}
	if got := ipsOf(r); !same(got, want) {
		t.Fatalf("addresses: got %v want %v", got, want)
	}

	if r.Family != 1 {
		t.Fatalf("family: got %d want 1 (the IPv6 answer of the ipv4 node)", r.Family)
	}

	for _, c := range r.IPs {
		if c.IP == "5.5.5.5" && !c.Chain {
			t.Fatal("the chained node's address must be marked")
		}

		if c.IP == "1.1.1.1" && (c.Via != "hosts" || c.Name != "node.example.com") {
			t.Fatalf("hosts source: %+v", c)
		}
	}

	if len(asked) != 2 {
		t.Fatalf("only the two domains that hosts does not have are asked, got %v", asked)
	}
}

func TestCollectConfigSharedAndFailed(t *testing.T) {
	proxies := map[string]map[string]any{
		"a": {"server": "shared.example.com"},
		"b": {"server": "shared.example.com"},
		"c": {"server": "gone.example.com"},
	}

	var asked []string

	r := collectConfig(context.Background(), proxies, map[string]any{},
		table(map[string]dnsResult{"shared.example.com": {IPs: addrs("4.4.4.4"), Omitted: 3}}, &asked))

	if len(asked) != 2 {
		t.Fatalf("a domain used by two nodes is asked once: %v", asked)
	}

	if len(r.IPs) != 1 || !same(r.IPs[0].From, []string{"a", "b"}) {
		t.Fatalf("one row, two nodes: %+v", r.IPs)
	}

	if r.Omitted != 3 {
		t.Fatalf("omitted: %d", r.Omitted)
	}

	if len(r.Fails) != 1 || r.Fails[0].Name != "gone.example.com" || r.Fails[0].Reason != "failed" {
		t.Fatalf("fails: %+v", r.Fails)
	}
}

func TestCollectConfigAliasAndWildcard(t *testing.T) {
	hosts := map[string]any{
		"alias.example.com": "target.example.com",
		"target.example.com": []any{"7.7.7.7"},
		"loop-a.example.com": "loop-b.example.com",
		"loop-b.example.com": "loop-a.example.com",
		"+.wild.example.org": "8.8.8.8",
		"*.one.example.org":  "6.6.6.6",
	}

	proxies := map[string]map[string]any{
		"al":    {"server": "alias.example.com"},
		"lp":    {"server": "loop-a.example.com"},
		"wild":  {"server": "x.y.wild.example.org"},
		"root":  {"server": "wild.example.org"},
		"one":   {"server": "a.one.example.org"},
		"deep":  {"server": "a.b.one.example.org"},
	}

	r := collectConfig(context.Background(), proxies, hosts, table(nil, nil))

	got := map[string]string{}
	for _, c := range r.IPs {
		for _, n := range c.From {
			got[n] = c.IP + "/" + c.Via
		}
	}

	if got["al"] != "7.7.7.7/alias" {
		t.Fatalf("alias: %v", got)
	}

	if got["wild"] != "8.8.8.8/hosts" || got["root"] != "8.8.8.8/hosts" {
		t.Fatalf("+. wildcard: %v", got)
	}

	if got["one"] != "6.6.6.6/hosts" {
		t.Fatalf("*. wildcard, one label: %v", got)
	}

	if _, ok := got["deep"]; ok {
		t.Fatalf("*. wildcard must not match two labels: %v", got)
	}

	loops := 0

	for _, f := range r.Fails {
		if f.Reason == "loop" {
			loops++
		}
	}

	if loops == 0 {
		t.Fatalf("an alias circle must be reported: %+v", r.Fails)
	}
}
