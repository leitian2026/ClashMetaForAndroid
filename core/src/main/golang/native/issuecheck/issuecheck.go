// Package issuecheck finds out which DNS server is down, and only when something just went wrong (CUSTOM.md #53, not
// upstream).
//
// The core logs no failure of a single DNS server at any level (it only reports "all DNS requests failed" when every
// server failed), so when a connection fails for that reason (package issues calls DNSFailed) each server of the
// profile's dns: section is asked once, through its proxy group when the profile sends it through one, to say which
// one is down. There is no timer and nothing runs at config load or network change; a cooldown keeps a burst of
// failing connections to one round. Nothing is reported when the network itself is down.
package issuecheck

import (
	"context"
	"sync/atomic"
	"time"

	"cfa/native/conftest"
	"cfa/native/errlog"
	"cfa/native/issues"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	cooldown    = time.Minute // at most one round per minute, counted from its start
	dialTimeout = 3 * time.Second
	probeDomain = "www.cloudflare.com"
)

var (
	running int32
	lastRun int64 // unix nanoseconds of the start of the last round

	// the test replaces these
	dnsProbe  = func() []conftest.DNSServer { return conftest.ProbeIssues(probeDomain) }
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

// DNSFailed is told that a connection failed because no DNS server answered. It starts one round (in the background)
// unless one runs or ran less than a minute ago.
func DNSFailed() {
	t := now().UnixNano()

	if last := atomic.LoadInt64(&lastRun); last != 0 && t-last < int64(cooldown) {
		return
	}

	if !atomic.CompareAndSwapInt32(&running, 0, 1) {
		return
	}

	atomic.StoreInt64(&lastRun, t)

	go func() {
		defer atomic.StoreInt32(&running, 0)
		defer func() { _ = recover() }()

		round()
	}()
}

func round() {
	if offline() {
		return
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
}

// offline: neither of two well-known addresses accepts a connection, so the DNS failure was the network's.
func offline() bool {
	return dialProbe("1.1.1.1:443") != nil && dialProbe("223.5.5.5:443") != nil
}


// Offline reports whether the device itself has no working network route, using the same two TCP checks as round().
func Offline() bool { return offline() }
