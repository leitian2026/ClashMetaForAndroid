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
	"time"

	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// maxItems keeps the answer well below the size limit of one binder transaction.
const maxItems = 300

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

// Snapshot is the answer of Query. Total counts every connection, Items only the newest ones.
type Snapshot struct {
	Total int    `json:"total"`
	Items []Item `json:"items"`
}

// Query returns the active connections, newest first.
func Query() (result Snapshot) {
	result.Items = []Item{}

	defer func() {
		if r := recover(); r != nil {
			log.Warnln("connections: %v", r)
		}
	}()

	items := make([]Item, 0, 64)

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if item, ok := convert(c); ok {
			items = append(items, item)
		}

		return true
	})

	sort.Slice(items, func(i, j int) bool {
		if items[i].Start != items[j].Start {
			return items[i].Start > items[j].Start
		}

		return items[i].ID < items[j].ID
	})

	result.Total = len(items)

	if len(items) > maxItems {
		items = items[:maxItems]
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

// Close closes the connection with that id. It reports false when it is already gone.
func Close(id string) (closed bool) {
	defer func() {
		if r := recover(); r != nil {
			log.Warnln("close connection: %v", r)
			closed = false
		}
	}()

	var target statistic.Tracker

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if c.ID() == id {
			target = c

			return false
		}

		return true
	})

	if target == nil {
		return false
	}

	_ = target.Close()

	return true
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
