package ipdelay

// CUSTOM (CUSTOM.md #37, not upstream): "does the node in use work right now", for the home page.
//
// One small request (the same test address as the speed test, through the node in use) is made when the
// home page or the notification asks for the tunnel state and the last answer is old or missing, and once
// after the network changed. There is no timer: nothing happens unless something asks, and at most one
// request per reachFresh (reachRetry after a failure). The app is told, with the same event as for a node
// IP change, only when the answer switched between "works" and "does not work".

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/metacubex/mihomo/tunnel"
)

const (
	reachFresh = 3 * time.Minute  // a working answer is asked again after this
	reachRetry = 20 * time.Second // a failed one is asked again after this
)

var (
	reachLock sync.Mutex
	reachNode string    // the node the answer below is about
	reachMs   int       // > 0: it works, the delay in milliseconds; -1: it does not; 0: not known
	reachReason string // timeout, connect, or http:<status>
	reachAt   time.Time // when the answer came, zero = it is stale
	reachBusy bool      // a request is on its way
)

// Reach returns the last answer for the node (0 = not known yet) and starts a new request when it is due.
func Reach(node string) (int, string, int64) {
	if node == "" {
		return 0, "", 0
	}

	reachLock.Lock()
	defer reachLock.Unlock()

	if reachNode != node {
		reachNode, reachMs, reachReason, reachAt = node, 0, "", time.Time{}
	}

	age := time.Since(reachAt)

	due := reachAt.IsZero() ||
		(reachMs >= 0 && age > reachFresh) ||
		(reachMs < 0 && age > reachRetry)

	if due && !reachBusy {
		reachBusy = true

		go probeReach(node)
	}

	at := int64(0)
	if !reachAt.IsZero() {
		at = reachAt.UnixMilli()
	}
	return reachMs, reachReason, at
}

// resetReach forgets the answer (a new profile was loaded).
func resetReach() {
	reachLock.Lock()
	reachNode, reachMs, reachReason, reachAt = "", 0, "", time.Time{}
	reachLock.Unlock()
}

// refreshReach is called once the network settled after a change: the old answer says little about the
// new network, so the node last asked about is tested again now.
func refreshReach() {
	reachLock.Lock()
	defer reachLock.Unlock()

	reachAt = time.Time{}

	if reachNode != "" && !reachBusy {
		reachBusy = true

		go probeReach(reachNode)
	}
}

func probeReach(node string) {
	ms, reason := measureReach(node)

	reachLock.Lock()

	tell := false

	if reachNode == node {
		// tell the app when there was no answer before or the answer switched between works / does not work
		tell = reachMs == 0 || (reachMs > 0) != (ms > 0)

		reachMs, reachReason, reachAt = ms, reason, time.Now()
	}

	reachBusy = false

	reachLock.Unlock()

	if tell && ms != 0 {
		notifyNow()
	}
}

// measureReach returns the delay in milliseconds, -1 when the request failed, 0 when the node is not known.
func measureReach(node string) (ms int, reason string)
	defer func() {
		if r := recover(); r != nil {
			ms, reason = -1, "connect"
		}
	}()

	px := tunnel.Proxies()[node]
	if px == nil {
		return 0, ""
	}

	status, _, elapsed, err := requestVia(px, defaultTestURL, testTimeout, false)
	if err != nil {
		var networkError net.Error
		if errors.Is(err, context.DeadlineExceeded) || (errors.As(err, &networkError) && networkError.Timeout()) {
			return -1, "timeout"
		}
		return -1, "connect"
	}
	if status < 200 || status >= 400 {
		return -1, fmt.Sprintf("http:%d", status)
	}

	ms = int(elapsed.Milliseconds())
	if ms <= 0 {
		ms = 1
	}

	return ms, ""
}

// notifyNow tells the app at once (no delay, no history entry: only what is shown is read again).
func notifyNow() {
	notifyLock.Lock()
	f := onChange
	notifyLock.Unlock()

	if f != nil {
		f()
	}
}
