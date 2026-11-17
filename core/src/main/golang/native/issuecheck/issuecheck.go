// Package issuecheck finds out which DNS server is down, and only when something just went wrong (CUSTOM.md #53, not
// upstream).
//
// The core logs no failure of a single DNS server at any level (it only reports "all DNS requests failed" when every
// server failed), so when a connection fails for that reason (package issues calls DNSFailed) each server of the
// profile's dns: section is asked once, through its proxy group when the profile sends it through one, to say which
// one is down. There is no timer and nothing runs at config load or network change; a result-aware cooldown keeps a
// burst of failing connections to one round. Nothing is reported when the network itself is down.
package issuecheck

import (
	"context"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/conftest"
	"cfa/native/errlog"
	"cfa/native/issues"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	dialTimeout = 3 * time.Second
	probeDomain = "www.cloudflare.com"
	maxCooldown = 30 * time.Minute
)

var (
	running int32
	screenInteractive int32 = 1 // default to screen on until the service reports otherwise

	stateMu        sync.Mutex
	lastRoundStart time.Time
	cooldownStep   int
	previousFailed []string
	hasPrevious    bool

	cooldowns = [...]time.Duration{
		time.Minute,
		2 * time.Minute,
		5 * time.Minute,
		10 * time.Minute,
		30 * time.Minute,
	}

	// the test replaces these
	dnsProbe = func() []conftest.DNSServer { return conftest.ProbeIssues(probeDomain) }
	dialProbe = func(addr string) error {
		ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
		defer cancel()

		c, err := dialer.DialContext(ctx, "tcp", addr)
		if err == nil {
			_ = c.Close()
		}

		return err
	}
	now = time.Now
)

type roundResult struct {
	offline bool
	failed  []string
}

// DNSFailed is told that a connection failed because no DNS server answered. It starts one round (in the background)
// unless one runs or the current result-aware cooldown has not elapsed.
func DNSFailed() {
	if atomic.LoadInt32(&screenInteractive) == 0 {
		return
	}

	start := now()

	stateMu.Lock()
	if !lastRoundStart.IsZero() && start.Sub(lastRoundStart) > maxCooldown {
		cooldownStep = 0
		hasPrevious = false
		previousFailed = nil
	}

	if !lastRoundStart.IsZero() && start.Sub(lastRoundStart) < cooldowns[cooldownStep] {
		stateMu.Unlock()
		return
	}

	if !atomic.CompareAndSwapInt32(&running, 0, 1) {
		stateMu.Unlock()
		return
	}

	lastRoundStart = start
	stateMu.Unlock()

	go func() {
		defer atomic.StoreInt32(&running, 0)
		defer func() { _ = recover() }()

		result := round()
		finishRound(result)
	}()
}

func SetScreenInteractive(interactive bool) {
	if interactive {
		atomic.StoreInt32(&screenInteractive, 1)
	} else {
		atomic.StoreInt32(&screenInteractive, 0)
	}
}

func round() roundResult {
	if offline() {
		return roundResult{offline: true}
	}

	keep := map[string]bool{}

	for _, s := range dnsProbe() {
		if s.Kind == "unsupported" || s.OK {
			continue
		}

		code := s.Code
		if code == "" {
			code = errlog.Classify(s.Reason)
		}

		keep[s.Address] = true
		issues.Report(issues.KindDoH, s.Address, code, s.Reason, s.Kind)
	}

	issues.ResolveExcept(issues.KindDoH, keep) // a server that answers now is no longer a problem

	failed := make([]string, 0, len(keep))
	for address := range keep {
		failed = append(failed, address)
	}
	sort.Strings(failed)

	return roundResult{failed: failed}
}

// finishRound changes the next cooldown only after a complete round. A network-offline skip is unchanged;
// the first round has no baseline and is treated as a change.
func finishRound(result roundResult) {
	stateMu.Lock()
	defer stateMu.Unlock()

	if !hasPrevious {
		hasPrevious = true
		previousFailed = append(previousFailed[:0], result.failed...)
		cooldownStep = 0
		return
	}

	if result.offline {
		if cooldownStep < len(cooldowns)-1 {
			cooldownStep++
		}
		return
	}

	if len(result.failed) == 0 || !sameServers(previousFailed, result.failed) {
		cooldownStep = 0
	} else if cooldownStep < len(cooldowns)-1 {
		cooldownStep++
	}

	previousFailed = append(previousFailed[:0], result.failed...)
}

func sameServers(a, b []string) bool {
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

// offline: neither of two well-known addresses accepts a connection, so the DNS failure was the network's.
func offline() bool {
	return dialProbe("1.1.1.1:443") != nil && dialProbe("223.5.5.5:443") != nil
}

// Offline reports whether the device itself has no working network route, using the same two TCP checks as round().
func Offline() bool { return offline() }
