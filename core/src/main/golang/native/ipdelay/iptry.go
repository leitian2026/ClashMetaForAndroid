package ipdelay

// CUSTOM (CUSTOM.md #43, not part of upstream): one address of a node, tried on request. The config check page's
// "test other IPs" calls it for every address the user typed: the node is copied with its `server` replaced by the
// address, exactly like the per-address test of #1 (so the numbers can be compared), and a request is made through
// the copy. Nothing here runs on its own; one call is one address.

import (
	"fmt"
	"sync/atomic"

	"github.com/metacubex/mihomo/tunnel"
)

// TryResult is the outcome of TryIP. Err is "" when the address works.
type TryResult struct {
	Delay   int    // milliseconds
	Country string // exit country, "" if unknown
	Err     string // the failure in the core's own words (the caller classifies it)
}

// TryIP tests node `name` at address `ip`: the delay of a request to the node's test URL and, when that works,
// the exit country (the one already known for this node and address is reused, else asked through the copy).
func TryIP(name, ip string) TryResult {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return TryResult{Err: "node not found"}
	}

	testURL := defaultTestURL
	if p := tunnel.Proxies()[name]; p != nil {
		testURL = testURLOf(p)
	}

	atomic.AddInt32(&selfDialing, 1) // our own sockets are not real traffic
	defer atomic.AddInt32(&selfDialing, -1)

	px := newVariant(raw, ip)
	if px == nil {
		return TryResult{Err: "cannot build the node with this address"}
	}
	defer closeProxy(px)

	status, _, elapsed, err := requestVia(px, testURL, testTimeout, false)
	if err != nil {
		return TryResult{Err: err.Error()}
	}

	if status < 200 || status >= 400 {
		return TryResult{Err: fmt.Sprintf("HTTP %d", status)}
	}

	delay := int(elapsed.Milliseconds())
	if delay <= 0 {
		delay = 1
	}

	country := CountryOf(name, ip)
	if country == "" {
		country = lookupCountry(px)
	}

	return TryResult{Delay: delay, Country: country}
}
