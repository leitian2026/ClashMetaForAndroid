package ipdelay

// CUSTOM (CUSTOM.md #23, #24, #25, not upstream): small facts about the loaded profile and the last
// speed test that the errors page and the DNS check ask for. Nothing here runs on its own.

import (
	"net/netip"
	"sort"
	"sync/atomic"
	"time"
)

const (
	// SortNodeInfo is not an order: asked with it, queryCustomConnections returns NodeInfo() instead.
	SortNodeInfo = 5

	// SortRetest is not an order either: the service process tells the core the network changed
	// ("changed") or asks for a latency test of every group ("run"), see the main package's retest.go.
	SortRetest = 6
)

var (
	testedAt         int64 // unix milliseconds: the last latency test that finished (manual or after a network change)
	networkChangedAt int64 // unix milliseconds: the last time the service saw the underlying network change
)

// MarkTested records that a latency test has just finished.
func MarkTested() {
	atomic.StoreInt64(&testedAt, time.Now().UnixMilli())
}

// MarkNetworkChanged records that the underlying network just changed (Wi-Fi <-> mobile data, another
// Wi-Fi, IPv6 appeared or went away). A speed test older than this says little about the new network.
func MarkNetworkChanged() {
	atomic.StoreInt64(&networkChangedAt, time.Now().UnixMilli())
}

// NodeInfoResult is the answer to SortNodeInfo.
type NodeInfoResult struct {
	IPv6             []string `json:"ipv6"`             // nodes that can only be reached over IPv6
	TestedAt         int64    `json:"testedAt"`         // 0 = no test since the core started
	NetworkChangedAt int64    `json:"networkChangedAt"` // 0 = the network did not change since the core started
}

// NodeInfo returns the facts above.
func NodeInfo() NodeInfoResult {
	return NodeInfoResult{
		IPv6:             IPv6Nodes(),
		TestedAt:         atomic.LoadInt64(&testedAt),
		NetworkChangedAt: atomic.LoadInt64(&networkChangedAt),
	}
}

// IPv6Nodes lists the nodes of the profile that need IPv6: `ip-version: ipv6`, or a server whose address (a
// literal IP, or every address of its `hosts:` entry) is IPv6. It goes by what is written in the profile, not
// by the node's name; a domain that resolves to both families is not counted (it may work over IPv4).
func IPv6Nodes() []string {
	lock.RLock()
	defer lock.RUnlock()

	out := []string{}

	for name, raw := range proxies {
		if isIPv6Node(raw, nodeIPs[name]) {
			out = append(out, name)
		}
	}

	sort.Strings(out)

	return out
}

func isIPv6Node(raw map[string]any, addrs []string) bool {
	if v, _ := raw["ip-version"].(string); v == "ipv6" {
		return true
	}

	if len(addrs) == 0 {
		return false
	}

	for _, s := range addrs {
		a, err := netip.ParseAddr(s)
		if err != nil || a.Unmap().Is4() {
			return false
		}
	}

	return true
}

var (
	dnsTree any // the parsed `dns:` section of the profile (see CaptureDNS)
)

// DNSTree returns the `dns:` section of the loaded profile as plain JSON values (nil when none was captured).
// Key names are whatever the core's struct marshals to, so readers must compare them loosely.
func DNSTree() any {
	lock.RLock()
	defer lock.RUnlock()

	return dnsTree
}

// RawHosts returns a copy of the profile's `hosts:` mapping.
func RawHosts() map[string]any {
	lock.RLock()
	defer lock.RUnlock()

	out := make(map[string]any, len(hosts))
	for k, v := range hosts {
		out[k] = v
	}

	return out
}
