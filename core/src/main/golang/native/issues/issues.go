// Package issues keeps the problems of the running configuration that the app shows as a notification and a
// card on the home page (CUSTOM.md #53, not upstream).
//
// It is only a store. Who finds a problem: the log listener of package errlog (provider / geo / dns lines, see
// FromLog), package issuecheck (probes after a config load or a network change), and the service process (a
// subscription update failed, through the same bridge call the page uses). Nothing here runs on its own; a change
// calls the registered callback once, after a short delay, outside the lock.
package issues

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	KindDoH      = "doh"      // a DNS server of the profile does not answer
	KindIP       = "ip"       // an address of a node does not accept connections
	KindSub      = "sub"      // a subscription update failed
	KindECH      = "ech"      // an ECH config was rejected / expired
	KindDNS      = "dns"      // name resolution fails / the dns: section has a problem
	KindGeo      = "geo"      // a geo file is missing, damaged or could not be updated
	KindProvider = "provider" // a proxy / rule provider could not be updated

	// SortIssues is not an order: asked with it, queryCustomConnections answers with Query (or runs a command).
	SortIssues = 10

	// ids for closeCustomConnection
	IgnorePrefix   = "!issue-ignore:"
	UnignorePrefix = "!issue-unignore:"
	ClearID        = "!issue-clear"

	maxItems = 100
	maxText  = 300
	delay    = 1500 * time.Millisecond
)

// Issue is one problem. ID is Kind + "|" + Subject, so a problem found again is the same entry.
type Issue struct {
	ID       string `json:"id"`
	Kind     string `json:"kind"`
	Subject  string `json:"subject"` // the server / node / file / provider it is about
	Code     string `json:"code"`    // errlog codes, "" when unknown
	Reason   string `json:"reason"`
	Extra    string `json:"extra"` // kind specific: for "ip" the node name
	First    int64  `json:"first"`
	Time     int64  `json:"time"`
	Count    int    `json:"count"` // how many checks / log lines in a row saw it
	Ignored  bool   `json:"ignored"`
	Resolved bool   `json:"resolved"` // seen working again; kept in the list until it is cleared
}

// Snapshot is the answer of Query. Active counts what is neither ignored nor resolved.
type Snapshot struct {
	Active int     `json:"active"`
	Items  []Issue `json:"items"` // active first (newest first), then ignored, then resolved
}

var (
	mu      sync.Mutex
	items   = map[string]*Issue{}
	ignored = map[string]bool{}
	dir     string
	loaded  bool
	onChg   func()
	timer   *time.Timer
)

// SetDir tells where the ignore list is kept (a file in the core's home dir). Called once, before the first use.
func SetDir(d string) {
	mu.Lock()
	defer mu.Unlock()

	if dir != d {
		dir, loaded = d, false
	}
}

// SetOnChange registers the function called (after a short delay, once for a burst) when the list changed.
func SetOnChange(f func()) {
	mu.Lock()
	onChg = f
	mu.Unlock()
}

func ID(kind, subject string) string { return kind + "|" + subject }

func file() string {
	if dir == "" {
		return ""
	}

	return filepath.Join(dir, "issues-ignored.json")
}

func loadLocked() {
	if loaded {
		return
	}

	loaded = true

	f := file()
	if f == "" {
		return
	}

	data, err := os.ReadFile(f)
	if err != nil {
		return
	}

	var ids []string
	if json.Unmarshal(data, &ids) == nil {
		for _, id := range ids {
			ignored[id] = true
		}
	}
}

func saveLocked() {
	f := file()
	if f == "" {
		return
	}

	ids := make([]string, 0, len(ignored))
	for id := range ignored {
		ids = append(ids, id)
	}

	sort.Strings(ids)

	if data, err := json.Marshal(ids); err == nil {
		_ = os.WriteFile(f, data, 0o600)
	}
}

func changedLocked() {
	if onChg == nil || timer != nil {
		return
	}

	timer = time.AfterFunc(delay, func() {
		mu.Lock()
		timer = nil
		f := onChg
		mu.Unlock()

		if f != nil {
			f()
		}
	})
}

func clip(s string) string {
	s = strings.TrimSpace(s)
	if len(s) <= maxText {
		return s
	}

	s = s[:maxText]
	for len(s) > 0 && s[len(s)-1]&0xC0 == 0x80 { // do not end inside a UTF-8 character
		s = s[:len(s)-1]
	}

	return s + "…"
}

// Report records that the problem exists now. Seen again: Count goes up, the reason is refreshed.
func Report(kind, subject, code, reason, extra string) {
	now := time.Now().UnixMilli()
	id := ID(kind, subject)

	mu.Lock()
	defer mu.Unlock()

	loadLocked()

	it := items[id]
	if it == nil {
		if len(items) >= maxItems {
			return
		}

		it = &Issue{ID: id, Kind: kind, Subject: clip(subject), First: now}
		items[id] = it
		changedLocked()
	} else if it.Resolved {
		it.Resolved, it.First, it.Count = false, now, 0
		changedLocked()
	}

	it.Count++
	it.Time = now
	it.Code, it.Reason, it.Extra = code, clip(reason), extra
	it.Ignored = ignored[id]
}

// Resolve records that the problem is gone (it is kept, marked, until cleared).
func Resolve(kind, subject string) {
	mu.Lock()
	defer mu.Unlock()

	if it := items[ID(kind, subject)]; it != nil && !it.Resolved {
		it.Resolved = true
		changedLocked()
	}
}

// ResolveExcept resolves every problem of the kind whose subject is not in keep: a check that found only `keep`
// failing says the others work (or are gone from the profile).
func ResolveExcept(kind string, keep map[string]bool) {
	mu.Lock()
	defer mu.Unlock()

	for _, it := range items {
		if it.Kind == kind && !it.Resolved && !keep[it.Subject] {
			it.Resolved = true
			changedLocked()
		}
	}
}

// SetIgnored (un)ignores one problem; the choice is kept across restarts.
func SetIgnored(id string, v bool) {
	mu.Lock()
	defer mu.Unlock()

	loadLocked()

	if v {
		ignored[id] = true
	} else {
		delete(ignored, id)
	}

	if it := items[id]; it != nil {
		it.Ignored = v
	}

	saveLocked()
	changedLocked()
}

// Clear forgets the resolved problems and the ones that are not ignored; ignored ones stay (and stay ignored).
func Clear() {
	mu.Lock()
	defer mu.Unlock()

	for id, it := range items {
		if it.Resolved || !it.Ignored {
			delete(items, id)
		}
	}

	changedLocked()
}

// Query returns the list.
func Query() Snapshot {
	mu.Lock()
	defer mu.Unlock()

	loadLocked()

	snap := Snapshot{Items: make([]Issue, 0, len(items))}

	for _, it := range items {
		snap.Items = append(snap.Items, *it)

		if !it.Ignored && !it.Resolved {
			snap.Active++
		}
	}

	rank := func(i Issue) int {
		switch {
		case i.Resolved:
			return 2
		case i.Ignored:
			return 1
		}

		return 0
	}

	sort.Slice(snap.Items, func(a, b int) bool {
		x, y := snap.Items[a], snap.Items[b]
		if rank(x) != rank(y) {
			return rank(x) < rank(y)
		}

		if x.Time != y.Time {
			return x.Time > y.Time
		}

		return x.ID < y.ID
	})

	return snap
}

// Command runs a request of the service process (the filter of a SortIssues query): "" lists, "sub|name|reason" says a
// subscription update failed, "subok|name" says it works again.
func Command(cmd string) Snapshot {
	parts := strings.SplitN(cmd, "|", 3)

	switch parts[0] {
	case "sub":
		if len(parts) == 3 {
			Report(KindSub, parts[1], "", parts[2], "")
		}
	case "subok":
		if len(parts) >= 2 {
			Resolve(KindSub, parts[1])
		}
	}

	return Query()
}

var (
	reProvider = regexp.MustCompile(`^\[Provider\]\s+(.+?)\s+(?:pull|read bundle file|initial|update)\b.*?error[:\s]*(.*)$`)
	reGeo      = regexp.MustCompile(`(?i)^\[GEO\]|(geosite|geoip|mmdb|asn)(\.dat|\.mmdb)?\s+invalid|failed to update geo`)
	reECH      = regexp.MustCompile(`(?i)\bech\b.*(reject|expired|retry|fail|invalid)|(reject|expired|retry|fail|invalid).*\bech\b`)
)

// FromLog is called with every WARNING / ERROR line of the core (by package errlog) and turns the ones that mean a
// problem of the configuration into issues. Everything else is ignored here (it is in the errors page anyway).
func FromLog(payload string) {
	p := strings.TrimSpace(payload)

	switch {
	case strings.HasPrefix(p, "[Provider]"):
		if m := reProvider.FindStringSubmatch(p); m != nil {
			Report(KindProvider, m[1], "", m[2], "")
		}
	case reGeo.MatchString(p):
		name := "geo"

		for _, f := range []string{"GeoSite", "GeoIP", "MMDB", "ASN"} {
			if strings.Contains(strings.ToLower(p), strings.ToLower(f)) {
				name = f
				break
			}
		}

		Report(KindGeo, name, "", p, "")
	case strings.HasPrefix(p, "[DNS] skip invalid nameserver policy"):
		Report(KindDNS, "nameserver-policy", "", p, "")
	case strings.Contains(p, "all DNS requests failed"):
		Report(KindDNS, "resolve", "dns", p, "")
	case reECH.MatchString(p):
		Report(KindECH, "ech", "", p, "")
	}
}
