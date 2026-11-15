package issuecheck

import (
	"errors"
	"testing"
	"time"

	"cfa/native/conftest"
	"cfa/native/issues"
)

func TestDNSFailsTwiceIsReported(t *testing.T) {
	sleep = func(time.Duration) {}
	dialProbe = func(string) error { return nil }
	dnsProbe = func() []conftest.DNSServer {
		return []conftest.DNSServer{
			{Address: "https://a/dns", Kind: "doh", OK: false, Reason: "i/o timeout"},
			{Address: "https://b/dns", Kind: "doh", OK: true},
			{Address: "x", Kind: "unsupported"},
		}
	}

	round()

	s := issues.Query()
	if s.Active != 1 || s.Items[0].Subject != "https://a/dns" {
		t.Fatalf("got %+v", s)
	}

	dnsProbe = func() []conftest.DNSServer { return nil }
	round()

	if s := issues.Query(); s.Active != 0 || !s.Items[0].Resolved {
		t.Fatalf("not resolved: %+v", s)
	}
}

func TestOfflineReportsNothing(t *testing.T) {
	sleep = func(time.Duration) {}
	dialProbe = func(string) error { return errors.New("i/o timeout") }
	dnsProbe = func() []conftest.DNSServer { return []conftest.DNSServer{{Address: "https://z/dns", Kind: "doh"}} }

	issues.Clear()
	round()

	if s := issues.Query(); s.Active != 0 {
		t.Fatalf("reported while offline: %+v", s)
	}
}

func TestNodeKinds(t *testing.T) {
	sleep = func(time.Duration) {}
	rawProxies = func() map[string]map[string]any {
		return map[string]map[string]any{
			"tcp":    {"type": "vless", "server": "1.1.1.1", "port": 443},
			"hy2":    {"type": "hysteria2", "server": "2.2.2.2", "port": 8443},
			"domain": {"type": "trojan", "server": "a.example.com", "port": 443},
			"chain":  {"type": "ss", "server": "3.3.3.3", "port": 1, "dialer-proxy": "x"},
		}
	}
	nodeAddrs = func(n string) []string {
		return map[string][]string{"tcp": {"1.1.1.1"}, "hy2": {"2.2.2.2"}, "chain": {"3.3.3.3"}}[n]
	}

	got := map[string]target{}
	for _, x := range targets() {
		got[x.node] = x
	}

	if got["tcp"].viaNode || !got["hy2"].viaNode || !got["chain"].viaNode {
		t.Fatalf("modes: %+v", got)
	}

	if d := got["domain"]; !d.viaNode || !d.isDomain || d.hostport != "a.example.com:443" {
		t.Fatalf("domain: %+v", d)
	}

	dialProbe = func(string) error { return nil }
	dnsProbe = func() []conftest.DNSServer { return nil }
	nodeProbe = func(node, server string, isDomain bool) string {
		if node == "hy2" {
			return "timeout"
		}

		if node == "chain" {
			return "skip"
		}

		return ""
	}

	issues.Clear()
	round()

	s := issues.Query()
	if s.Active != 1 || s.Items[0].Extra != "hy2" || s.Items[0].Subject != "2.2.2.2:8443" {
		t.Fatalf("got %+v", s)
	}
}
