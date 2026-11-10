// Package connlist is a CUSTOM addition (not part of upstream), see CUSTOM.md #8.
//
// It hands the app a snapshot of the core's active connections (the same data the REST API of the
// core calls /connections) and closes one connection on request. Nothing here runs on its own: it
// is called when the connections page asks (when it opens, on its refresh button, and while it is
// visible).
package connlist

import (
	"encoding/json"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// maxItems keeps the answer well below the size limit of one binder transaction.
const maxItems = 300

const (
	// SortTime lists the newest connections first (the default).
	SortTime = 0
	// SortTraffic lists the connections that moved the most data (upload + download) first.
	SortTraffic = 1
)

// CloseAll is the id Close accepts to close every connection at once.
const CloseAll = "*"

// startCache remembers the start time of the connections seen by the last Query, by id. A start time
// never changes, so with the default order (newest first) only the connections that appeared since
// the last Query and the ones that are going to be shown have to be converted; with thousands of
// connections the rest costs one map lookup each. It is rebuilt on every Query, so it only ever
// holds ids that were alive then.
var (
	cacheLock  sync.Mutex
	startCache = map[string]int64{}
)

// Item is one connection. `Chains` is as the core keeps it: the node that really carries the
// connection first, the groups it was selected through after it.
type Item struct {
	ID          string   `json:"id"`
	Host        string   `json:"host,omitempty"`
	IP          string   `json:"ip,omitempty"`
	Port        string   `json:"port,omitempty"`
	Network     string   `json:"network,omitempty"`
	Type        string   `json:"type,omitempty"`
	Source      string   `json:"source,omitempty"`
	Process     string   `json:"process,omitempty"`
	Chains      []string `json:"chains,omitempty"`
	Rule        string   `json:"rule,omitempty"`
	Payload     string   `json:"payload,omitempty"`
	Upload      int64    `json:"upload"`
	Download    int64    `json:"download"`
	Start       int64    `json:"start"` // unix milliseconds, 0 if unknown
	NodeIP      string   `json:"nodeIp,omitempty"`
	NodeCountry string   `json:"nodeCountry,omitempty"`
}

// Snapshot is the answer of Query. Total counts every connection, Matched those that pass the filter
// (equal to Total without a filter), Items only the first maxItems of them.
type Snapshot struct {
	Total   int    `json:"total"`
	Matched int    `json:"matched"`
	Items   []Item `json:"items"`
}

// Query returns the active connections ordered by sortMode (SortTime or SortTraffic). A non-empty
// filter keeps only the connections that contain every word of it (case-insensitive) in their host,
// address, port, app, rule or any name of their route.
func Query(sortMode int, filter string) (result Snapshot) {
	result.Items = []Item{}

	defer func() {
		if r := recover(); r != nil {
			log.Warnln("connections: %v", r)
		}
	}()

	cacheLock.Lock()
	defer cacheLock.Unlock()

	words := strings.Fields(strings.ToLower(filter))

	// the filter and the traffic order need the data of every connection, the time order does not
	needAll := len(words) > 0 || sortMode == SortTraffic

	type row struct {
		id      string
		tracker statistic.Tracker
		start   int64
		item    *Item // nil while the connection was not converted
	}

	rows := make([]row, 0, 64)
	seen := make(map[string]int64, len(startCache))

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		r := row{id: c.ID(), tracker: c}

		if start, ok := startCache[r.id]; ok && !needAll {
			r.start = start
		} else if item, ok := convert(c); ok {
			r.item = &item
			r.start = item.Start
		} else {
			return true
		}

		seen[r.id] = r.start
		rows = append(rows, r)

		return true
	})

	startCache = seen

	result.Total = len(rows)

	if len(words) > 0 {
		kept := rows[:0]

		for _, r := range rows {
			if r.item != nil && matches(r.item, words) {
				kept = append(kept, r)
			}
		}

		rows = kept
	}

	result.Matched = len(rows)

	sort.Slice(rows, func(i, j int) bool {
		a, b := rows[i], rows[j]

		if sortMode == SortTraffic && a.item != nil && b.item != nil {
			ta, tb := a.item.Upload+a.item.Download, b.item.Upload+b.item.Download
			if ta != tb {
				return ta > tb
			}
		}

		if a.start != b.start {
			return a.start > b.start
		}

		return a.id < b.id
	})

	if len(rows) > maxItems {
		rows = rows[:maxItems]
	}

	items := make([]Item, 0, len(rows))

	for _, r := range rows {
		if r.item != nil {
			items = append(items, *r.item)
		} else if item, ok := convert(r.tracker); ok {
			items = append(items, item)
		}
	}

	// the address and exit country the core last used for a node, when they are known
	type nodeInfo struct{ ip, country string }

	known := map[string]nodeInfo{}

	for i := range items {
		if len(items[i].Chains) == 0 {
			continue
		}

		node := items[i].Chains[0]

		info, ok := known[node]
		if !ok {
			info.ip, info.country = ipdelay.Lookup(node)
			known[node] = info
		}

		items[i].NodeIP, items[i].NodeCountry = info.ip, info.country
	}

	result.Items = items

	return result
}

// matches reports whether every word is part of the connection's searchable text.
func matches(item *Item, words []string) bool {
	text := strings.ToLower(strings.Join([]string{
		item.Host, item.IP, item.Port, item.Network, item.Process, item.Rule, item.Payload,
		strings.Join(item.Chains, " "),
	}, " "))

	for _, w := range words {
		if !strings.Contains(text, w) {
			return false
		}
	}

	return true
}

// Close closes the connection with that id, or every connection for CloseAll. It reports false when
// the connection is already gone (or, for CloseAll, when there was nothing to close).
func Close(id string) (closed bool) {
	defer func() {
		if r := recover(); r != nil {
			log.Warnln("close connection: %v", r)
			closed = false
		}
	}()

	var targets []statistic.Tracker

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if id == CloseAll {
			targets = append(targets, c)

			return true
		}

		if c.ID() == id {
			targets = append(targets, c)

			return false
		}

		return true
	})

	for _, t := range targets {
		_ = t.Close()
	}

	return len(targets) > 0
}

// convert reads the connection through its JSON form, which is the stable, documented shape of the
// core's REST API, so no field of the core's internal structs is relied on.
func convert(c statistic.Tracker) (Item, bool) {
	info := c.Info()
	if info == nil {
		return Item{}, false
	}

	raw, err := json.Marshal(info)
	if err != nil {
		return Item{}, false
	}

	var v struct {
		Metadata    map[string]any `json:"metadata"`
		Upload      any            `json:"upload"`
		Download    any            `json:"download"`
		Start       any            `json:"start"`
		Rule        any            `json:"rule"`
		RulePayload any            `json:"rulePayload"`
	}

	if json.Unmarshal(raw, &v) != nil {
		return Item{}, false
	}

	item := Item{
		ID:       c.ID(),
		Host:     text(v.Metadata["host"]),
		IP:       text(v.Metadata["destinationIP"]),
		Port:     text(v.Metadata["destinationPort"]),
		Network:  text(v.Metadata["network"]),
		Type:     text(v.Metadata["type"]),
		Process:  text(v.Metadata["process"]),
		Chains:   append([]string(nil), c.Chains()...),
		Rule:     text(v.Rule),
		Payload:  text(v.RulePayload),
		Upload:   number(v.Upload),
		Download: number(v.Download),
	}

	if ip, port := text(v.Metadata["sourceIP"]), text(v.Metadata["sourcePort"]); ip != "" {
		if port != "" {
			item.Source = ip + ":" + port
		} else {
			item.Source = ip
		}
	}

	if s, ok := v.Start.(string); ok {
		if t, err := time.Parse(time.RFC3339Nano, s); err == nil && !t.IsZero() && t.Unix() > 0 {
			item.Start = t.UnixMilli()
		}
	}

	return item, true
}

func text(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case float64:
		return strconv.FormatFloat(t, 'f', -1, 64)
	}

	return ""
}

func number(v any) int64 {
	switch t := v.(type) {
	case float64:
		return int64(t)
	case string:
		n, _ := strconv.ParseInt(t, 10, 64)

		return n
	}

	return 0
}
