package ipdelay

// CUSTOM (CUSTOM.md #7, not part of upstream): the node addresses that requests really go through.
//
// The xhttp client of the core reports, for every request (a new connection and a reused one alike),
// the address of the connection that carries it (patches/mihomo/0002-request-remote.patch, through
// dialer.SetUseHook). With multiplexing a new web page may go through several addresses of the node, and
// a request put on an open connection dials nothing, so the socket hook alone cannot tell.
//
// The set kept here is the addresses used by the requests of the last useWindow before the most recent
// one; it stays as it is until the next request. What the home page and the notification show is only the
// newest address of it (r42). Nothing runs on its own: the hook is only called when a request is made
// anyway, and the app is told only when the set changes (the same delayed notification as for a dial,
// see scheduleNotify).

import (
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	useWindow   = 1500 * time.Millisecond // requests closer together than this belong to one visit
	repeatGap   = 500 * time.Millisecond  // CUSTOM.md #35: the same address again within this is not worth recording (< useWindow)
)

// NodeIP is an address of a node and, when already known, the exit country behind it.
type NodeIP struct {
	IP      string `json:"ip"`
	Country string `json:"country,omitempty"`
}

type useRec struct {
	ip string
	at time.Time
}

var (
	recent  = map[string][]useRec{} // node -> addresses its requests went through, most recent first (guarded by lock)
	shown   = map[string]string{}   // node -> sorted addresses of the set last announced (guarded by lock)
	useOnce sync.Once
)

// installUseHook starts listening to the core (once).
func installUseHook() {
	useOnce.Do(func() {
		dialer.SetUseHook(noteUse)
	})
}

// noteUse is told that a request of `node` goes through the address `ip`.
func noteUse(node, ip string) {
	if atomic.LoadInt32(&selfDialing) > 0 {
		return // our own tests and country lookups are not real traffic
	}

	now := time.Now()

	// CUSTOM.md #35: this is called for every request. When the newest address of the node is this very
	// one and was noted a moment ago, nothing can have changed (same set, same order): leave at once,
	// without the write lock, the allocations and the sort below.
	lock.RLock()

	repeated := false

	if _, ok := proxies[node]; ok {
		if list := recent[node]; len(list) > 0 && list[0].ip == ip && now.Sub(list[0].at) < repeatGap {
			repeated = true
		}
	}

	lock.RUnlock()

	if repeated {
		return
	}

	lock.Lock()

	if _, ok := proxies[node]; !ok {
		lock.Unlock()

		return
	}

	list := recent[node]
	next := make([]useRec, 0, len(list)+1)
	next = append(next, useRec{ip: ip, at: now})

	for _, r := range list {
		if r.ip == ip || now.Sub(r.at) > useWindow {
			continue
		}

		next = append(next, r)
	}

	recent[node] = next

	ips := make([]string, len(next))
	for i, r := range next {
		ips[i] = r.ip
	}

	sort.Strings(ips)

	key := strings.Join(ips, ",")
	changed := shown[node] != key
	shown[node] = key

	lock.Unlock()

	if changed {
		scheduleNotify()
	}
}

// countryLocked returns the exit country known for node/ip; when unknown a single lookup is started in
// the background (retried at most every retryAfter). The caller holds lock.
func countryLocked(node, ip string) string {
	key := node + "|" + ip
	country := countries[key]

	if country == "" && !probing[key] && time.Since(failedAt[key]) > retryAfter {
		if raw := proxies[node]; raw != nil {
			probing[key] = true

			go probeCountry(key, raw, ip)
		}
	}

	return country
}

// CurrentSet returns the node in use and the address its most recent request went through (a list of one;
// `more` is always 0 since r42, kept so that the callers need no change). Without any request seen yet it
// falls back to the address the core dialed most recently. Like Current it starts the country
// lookups that are still missing.
func CurrentSet() (node string, ips []NodeIP, more int) {
	active := activeNodes() // before taking our lock: it walks the core's connection list

	lock.Lock()
	defer lock.Unlock()

	best := ""

	for name, count := range active {
		if best == "" || count > active[best] || (count == active[best] && name < best) {
			best = name
		}
	}

	var primary string

	switch {
	case best != "":
		node = best

		if list := nodeIPs[best]; len(list) == 1 {
			primary = list[0] // a single address: nothing to choose from
		} else if last, ok := lastByNode[best]; ok {
			primary = last
		} else if len(list) > 0 {
			primary = list[0]
		}
	case lastUsed.ip != "":
		node, primary = lastUsed.node, lastUsed.ip
	}

	var list []string

	for _, r := range recent[node] {
		list = append(list, r.ip)
	}

	if len(list) == 0 && primary != "" {
		list = []string{primary}
	}

	if len(list) == 0 {
		return "", nil, 0
	}

	// CUSTOM.md #7 (r42): only the address the most recent request went through is shown; `more` stays 0
	list = list[:1]

	for _, ip := range list {
		ips = append(ips, NodeIP{IP: ip, Country: countryLocked(node, ip)})
	}

	return node, ips, more
}

// Current returns the node in use and its most recently used address; see CurrentSet.
func Current() (node, ip, country string) {
	node, ips, _ := CurrentSet()

	if len(ips) == 0 {
		return "", "", ""
	}

	return node, ips[0].IP, ips[0].Country
}

// setKey describes what CurrentSet returned, for telling whether the listener must be told again. The
// addresses are sorted: the order alone changing (two connections taking turns) is not a change.
func setKey(node string, ips []NodeIP, more int) string {
	parts := make([]string, len(ips))
	for i, e := range ips {
		parts[i] = e.IP + "/" + e.Country
	}

	sort.Strings(parts)

	return node + "|" + strings.Join(parts, ",") + "|" + string(rune('0'+more))
}
