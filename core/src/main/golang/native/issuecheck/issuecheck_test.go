package issuecheck

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"

	"cfa/native/conftest"
	"cfa/native/issues"
)

func reset() {
	atomic.StoreInt64(&lastRun, 0)
	atomic.StoreInt32(&running, 0)
	now = time.Now
	dialProbe = func(string) error { return nil }
	issues.Clear()
}

func TestRoundReportsDownServersAndResolves(t *testing.T) {
	reset()
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

	dnsProbe = func() []conftest.DNSServer {
		return []conftest.DNSServer{{Address: "https://a/dns", Kind: "doh", OK: true}}
	}
	round()

	if s := issues.Query(); s.Active != 0 || !s.Items[0].Resolved {
		t.Fatalf("not resolved: %+v", s)
	}
}

func TestOfflineReportsNothing(t *testing.T) {
	reset()
	dialProbe = func(string) error { return errors.New("i/o timeout") }
	dnsProbe = func() []conftest.DNSServer { return []conftest.DNSServer{{Address: "https://z/dns", Kind: "doh"}} }

	round()

	if s := issues.Query(); s.Active != 0 {
		t.Fatalf("reported while offline: %+v", s)
	}
}

func TestCooldownAndSingleFlight(t *testing.T) {
	reset()

	var calls int32

	done := make(chan struct{}, 4)

	dnsProbe = func() []conftest.DNSServer {
		atomic.AddInt32(&calls, 1)
		done <- struct{}{}

		return nil
	}

	DNSFailed()
	DNSFailed() // within the cooldown: no second round
	DNSFailed()

	<-done

	time.Sleep(50 * time.Millisecond)

	if n := atomic.LoadInt32(&calls); n != 1 {
		t.Fatalf("%d rounds for a burst", n)
	}

	t0 := time.Now()
	now = func() time.Time { return t0.Add(2 * time.Minute) }

	for atomic.LoadInt32(&running) != 0 {
		time.Sleep(time.Millisecond)
	}

	DNSFailed()
	<-done

	if n := atomic.LoadInt32(&calls); n != 2 {
		t.Fatalf("%d rounds after the cooldown", n)
	}
}
