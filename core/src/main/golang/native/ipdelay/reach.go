package ipdelay

// CUSTOM (CUSTOM.md #37, not upstream): "does the node in use work right now", for the home page.
//
// One small request (the same test address as the speed test, through the node in use) is made when the
// home page or the notification asks for the tunnel state and the last answer is old or missing, and once
// after the network changed. There is no timer: nothing happens unless something asks, and at most one
// request per reachFresh (reachRetry after a failure). The app is told, with the same event as for a node
// IP change, only when the answer switched between "works" and "does not work".

import (
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
	reachAt   time.Time // when the answer came, zero = it is stale
	reachBusy bool      // a request is on its way
)

// Reach returns the last answer for the node (0 = not known yet) and starts a new request when it is due.
func Reach(node string) int {
	if node == "" {
		return 0
	}

	reachLock.Lock()
	defer reachLock.Unlock()

	if reachNode != node {
		reachNode, reachMs, reachAt = node, 0, time.Time{}
	}

	age := time.Since(reachAt)

	due := reachAt.IsZero() ||
		(reachMs >= 0 && age > reachFresh) ||
		(reachMs < 0 && age > reachRetry)

	if due && !reachBusy {
		reachBusy = true

		go probeReach(node)
	}

	return reachMs
}

// resetReach forgets the answer (a new profile was loaded).
func resetReach() {
	reachLock.Lock()
	reachNode, reachMs, reachAt = "", 0, time.Time{}
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
	ms := measureReach(node)

	reachLock.Lock()

	tell := false

	if reachNode == node {
		// tell the app when there was no answer before or the answer switched between works / does not work
		tell = reachMs == 0 || (reachMs > 0) != (ms > 0)

		reachMs, reachAt = ms, time.Now()
	}

	reachBusy = false

	reachLock.Unlock()

	if tell && ms != 0 {
		notifyNow()
	}
}

// measureReach returns the delay in milliseconds, -1 when the request failed, 0 when the node is not known.
func measureReach(node string) (ms int) {
	defer func() {
		if r := recover(); r != nil {
			ms = -1
		}
	}()

	px := tunnel.Proxies()[node]
	if px == nil {
		return 0
	}

	status, _, elapsed, err := requestVia(px, defaultTestURL, testTimeout, false)
	if err != nil || status < 200 || status >= 400 {
		return -1
	}

	ms = int(elapsed.Milliseconds())
	if ms <= 0 {
		ms = 1
	}

	return ms
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
