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
	dnsProbe = func() conftest.DNSReport {
		return conftest.DNSReport{Servers: []conftest.DNSServer{
			{Address: "https://a/dns", Kind: "doh", OK: false, Reason: "i/o timeout"},
			{Address: "https://b/dns", Kind: "doh", OK: true},
			{Address: "x", Kind: "unsupported"},
		}}
	}

	round()

	s := issues.Query()
	if s.Active != 1 || s.Items[0].Subject != "https://a/dns" {
		t.Fatalf("got %+v", s)
	}

	dnsProbe = func() conftest.DNSReport { return conftest.DNSReport{} }
	round()

	if s := issues.Query(); s.Active != 0 || !s.Items[0].Resolved {
		t.Fatalf("not resolved: %+v", s)
	}
}

func TestOfflineReportsNothing(t *testing.T) {
	sleep = func(time.Duration) {}
	dialProbe = func(string) error { return errors.New("i/o timeout") }
	dnsProbe = func() conftest.DNSReport {
		return conftest.DNSReport{Servers: []conftest.DNSServer{{Address: "https://z/dns", Kind: "doh"}}}
	}

	issues.Clear()
	round()

	if s := issues.Query(); s.Active != 0 {
		t.Fatalf("reported while offline: %+v", s)
	}
}
