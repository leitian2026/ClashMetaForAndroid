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
	Ph      Phases // CUSTOM.md #68: where the delay went (0 = not measured)
}

func copyNodeForDomain(raw map[string]any, name string) map[string]any {
	copy := make(map[string]any, len(raw))
	for key, value := range raw {
		copy[key] = value
	}
	copy["name"] = name + " (test)"
	return copy
}

// TryIP tests node `name` with its server replaced by `server`: the delay of a request to the node's test URL.
// CUSTOM.md #72: it no longer asks for the exit country (it did, on every try, when the proxy page had none for this
// address): the caller asks once with ExitCountry. `server` is an IP address, or a domain (isDomain) that the core resolves itself when it
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
		raw = copyNodeForDomain(raw, name)
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

	// CUSTOM.md #68: same request as requestVia, with the three parts read from it
	status, elapsed, ph, err := requestPhased(px, testURL, testTimeout)
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

	return TryResult{Delay: delay, Ph: ph}
}

// ExitCountry (CUSTOM.md #62) is the country that an outside service sees for traffic of node `name` with its server
// replaced by `server` (an address, or a domain when isDomain), asked now through a copy of the node (api.country.is,
// then ipinfo.io). CUSTOM.md #72: always asked, the one the proxy page knows from an earlier test is not used. A domain
// is asked with a copy that has another name, like TryIP. "" when it cannot be found. Nothing is stored.
func ExitCountry(name, server string, isDomain bool) (country, exitIP string) {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return "", ""
	}

	if isDomain {
		raw = copyNodeForDomain(raw, name)
	}

	atomic.AddInt32(&selfDialing, 1) // our own sockets are not real traffic
	defer atomic.AddInt32(&selfDialing, -1)

	px := newVariant(raw, server)
	if px == nil {
		return "", ""
	}
	defer closeProxy(px)

	return lookupCountry(px)
}
