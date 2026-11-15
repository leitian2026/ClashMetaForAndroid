package ipdelay

// CUSTOM (CUSTOM.md #53, not part of upstream): what the runtime problem check (package issuecheck) needs from this
// package. Nothing here runs on its own.

import (
	"context"
	"fmt"
	"sync/atomic"

	"github.com/metacubex/mihomo/tunnel"
)

// WithSelfDial runs f while our own sockets are being made, so that they are not taken for real traffic (the node IP
// shown on the home page, the "in use" marks).
func WithSelfDial(f func()) {
	atomic.AddInt32(&selfDialing, 1)
	defer atomic.AddInt32(&selfDialing, -1)

	f()
}

// ProbeNode asks whether node `name`, with its `server` replaced by `server` (an IP, or a domain when isDomain), can
// carry a request to its test URL: the same test the core's own latency test makes, through the node's real
// transport (so UDP types such as hysteria2 / tuic / wireguard and nodes behind a `dialer-proxy` are tested too). It
// is TryIP without the exit country request. Returns "" when it works, else the failure in the core's own words;
// "skip" when the node cannot be built (nothing is judged then).
func ProbeNode(ctx context.Context, name, server string, isDomain bool) string {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return "skip"
	}

	if isDomain {
		cp := make(map[string]any, len(raw))
		for k, v := range raw {
			cp[k] = v
		}

		cp["name"] = name + " (test)" // see TryIP: a node's preferred address is dialed by its name

		raw = cp
	}

	testURL := defaultTestURL
	if p := tunnel.Proxies()[name]; p != nil {
		testURL = testURLOf(p)
	}

	var failure string

	WithSelfDial(func() {
		px := newVariant(raw, server)
		if px == nil {
			failure = "skip"
			return
		}
		defer closeProxy(px)

		if ctx.Err() != nil {
			failure = "skip"
			return
		}

		status, _, _, err := requestVia(px, testURL, testTimeout, false) // has its own time limit

		switch {
		case err != nil:
			failure = err.Error()
		case status < 200 || status >= 400:
			failure = fmt.Sprintf("HTTP %d", status)
		}
	})

	return failure
}
