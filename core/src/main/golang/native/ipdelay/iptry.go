package ipdelay

// CUSTOM (CUSTOM.md #43, not part of upstream): one address (or domain) of a node, tried on request. The config check
// page's "test other IPs" calls it for every entry the user typed: the node is copied with its `server` replaced by
// the entry, exactly like the per-address test of #1 (so the numbers can be compared), and a request is made through
// the copy. Nothing here runs on its own; one call is one entry.

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

// TryIP tests node `name` with its server replaced by `server`: the delay of a request to the node's test URL and,
// when that works, the exit country (for an address the one already known for this node and address is reused, else
// asked through the copy). `server` is an IP address, or a domain (isDomain) that the core resolves itself when it
// dials, `hosts:` included.
//
// A domain is tested with a copy that has another name: the core dials a node's preferred address (0003) by the
// node's name, and a domain whose addresses include it would then be dialed at that one address only.
func TryIP(name, server string, isDomain bool) TryResult {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return TryResult{Err: "node not found"}
	}

	if isDomain {
		cp := make(map[string]any, len(raw))
		for k, v := range raw {
			cp[k] = v
		}

		cp["name"] = name + " (test)"
		raw = cp
	}

	testURL := defaultTestURL
	if p := tunnel.Proxies()[name]; p != nil {
		testURL = testURLOf(p)
	}

	atomic.AddInt32(&selfDialing, 1) // our own sockets are not real traffic
	defer atomic.AddInt32(&selfDialing, -1)

	px := newVariant(raw, server)
	if px == nil {
		return TryResult{Err: "cannot build the node with this server"}
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

	country := ""
	if !isDomain {
		country = CountryOf(name, server)
	}

	if country == "" {
		country = lookupCountry(px)
	}

	return TryResult{Delay: delay, Country: country}
}
