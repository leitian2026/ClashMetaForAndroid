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
	stateMu.Lock()
	lastRoundStart = time.Time{}
	cooldownStep = 0
	previousFailed = nil
	hasPrevious = false
	stateMu.Unlock()

	atomic.StoreInt32(&running, 0)
	now = time.Now
	dnsProbe = func() []conftest.DNSServer { return nil }
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

	result := round()

	if !result.offline || len(result.failed) != 0 {
		t.Fatalf("unexpected result: %+v", result)
	}
	if s := issues.Query(); s.Active != 0 {
		t.Fatalf("reported while offline: %+v", s)
	}
}

func TestCooldownStepsAndCap(t *testing.T) {
	reset()

	failed := []string{"https://a/dns"}
	finishRound(roundResult{failed: failed}) // first round: changed, keep 1 minute
	for i, want := range []int{1, 2, 3, 4, 4, 4} {
		finishRound(roundResult{failed: failed})
		if cooldownStep != want {
			t.Fatalf("round %d: step=%d, want %d", i+1, cooldownStep, want)
		}
	}
}

func TestChangedSetAndDNSRecoveryResetCooldown(t *testing.T) {
	reset()
	finishRound(roundResult{failed: []string{"a"}})
	finishRound(roundResult{failed: []string{"a"}})
	if cooldownStep != 1 {
		t.Fatalf("same result should increase step: %d", cooldownStep)
	}

	finishRound(roundResult{failed: []string{"b"}})
	if cooldownStep != 0 {
		t.Fatalf("changed server set should reset: %d", cooldownStep)
	}

	finishRound(roundResult{failed: []string{"b"}})
	finishRound(roundResult{})
	if cooldownStep != 0 {
		t.Fatalf("DNS recovery should reset: %d", cooldownStep)
	}
}

func TestOfflineSkipIncreasesCooldown(t *testing.T) {
	reset()
	finishRound(roundResult{failed: []string{"a"}})
	finishRound(roundResult{offline: true})
	if cooldownStep != 1 {
		t.Fatalf("offline skip should increase step: %d", cooldownStep)
	}
}

func TestCooldownResetsAfterThirtyMinutesFromRoundStart(t *testing.T) {
	reset()
	start := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	dnsProbe = func() []conftest.DNSServer {
		return []conftest.DNSServer{{Address: "https://a/dns", Kind: "doh", OK: false, Reason: "i/o timeout"}}
	}
	now = func() time.Time { return start }
	DNSFailed()
	waitForIdle(t)

	stateMu.Lock()
	if cooldownStep != 0 {
		stateMu.Unlock()
		t.Fatalf("first round should keep one-minute cooldown: %d", cooldownStep)
	}
	stateMu.Unlock()

	now = func() time.Time { return start.Add(31 * time.Minute) }
	DNSFailed()
	waitForIdle(t)

	stateMu.Lock()
	defer stateMu.Unlock()
	if lastRoundStart != start.Add(31*time.Minute) || cooldownStep != 0 {
		t.Fatalf("long gap with the same failure set should restart at one minute: start=%v step=%d", lastRoundStart, cooldownStep)
	}
}

func TestCooldownUsesRoundStartAndSingleFlight(t *testing.T) {
	reset()

	var calls int32
	entered := make(chan struct{}, 2)
	release := make(chan struct{})

	dnsProbe = func() []conftest.DNSServer {
		atomic.AddInt32(&calls, 1)
		entered <- struct{}{}
		<-release
		return nil
	}

	start := time.Now()
	now = func() time.Time { return start }
	DNSFailed()
	<-entered
	DNSFailed() // still inside the one-minute cooldown and the first round is in flight
	close(release)
	waitForIdle(t)

	if n := atomic.LoadInt32(&calls); n != 1 {
		t.Fatalf("%d rounds for a burst", n)
	}

	now = func() time.Time { return start.Add(time.Minute + time.Second) }
	DNSFailed()
	waitForIdle(t)

	if n := atomic.LoadInt32(&calls); n != 2 {
		t.Fatalf("%d rounds after the cooldown", n)
	}
}

func waitForIdle(t *testing.T) {
	t.Helper()
	deadline := time.Now().Add(time.Second)
	for atomic.LoadInt32(&running) != 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if atomic.LoadInt32(&running) != 0 {
		t.Fatal("round did not finish")
	}
}
