// Package errlog keeps the last warnings and errors the core logged, for the app's "errors" page.
//
// CUSTOM (CUSTOM.md #21, not upstream). It adds no work of its own: mihomo already logs every failed
// connection ("[TCP] dial <node> (match <rule>) <src> --> <dst> error: <reason>"), DNS failure, provider
// update failure and so on. This package only listens to that log (a goroutine blocked on a channel, so it
// costs nothing while nothing is logged), keeps WARNING and ERROR lines in a small ring and, for connection
// failures, splits the line into node / rule / destination / reason.
//
// Lines that are not a problem for the user are not kept as entries but folded into a counter each
// (see foldKind): connecting to an IPv6 address on a network that has no IPv6, and connections cancelled
// or closed on purpose. The page shows one line per counter.
package errlog

import (
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/log"
)

const (
	maxEntries = 300 // distinct problems kept; identical ones are counted instead of repeated
	maxText    = 400 // longest message / reason kept, in bytes

	// Kind of an entry.
	KindConnect = "connect" // a connection through a node (or DIRECT) failed
	KindDNS     = "dns"     // name resolution failed
	KindOther   = "other"   // any other warning or error of the core

	// Code is a stable short name of the reason; the app turns it into text in the user's language.
	CodeTimeout = "timeout"
	CodeRefused = "refused"
	CodeReset   = "reset"
	CodeEOF     = "eof"
	CodeDNS     = "dns"
	CodeNetwork = "network"
	CodeTLS     = "tls"
	CodeAuth    = "auth"
	CodeReject  = "reject"
	CodeOther   = "other"

	// Kinds of folded lines.
	FoldNoIPv6 = "noipv6" // an IPv6 address was dialled on a network without IPv6
	FoldClosed = "closed" // cancelled or closed on purpose (page closed, app switched away)

	// SortErrors is not an order: asked with it, queryCustomConnections answers with Query instead.
	SortErrors = 3
	// ClearID is not a connection id: closeCustomConnection with it clears the recorded errors.
	ClearID = "!errors"
)

// Entry is one problem. Identical problems (same kind, node, destination and reason) are merged: Count
// goes up and Time moves to the latest one.
type Entry struct {
	Time    int64  `json:"time"`  // unix milliseconds of the latest occurrence
	First   int64  `json:"first"` // unix milliseconds of the first occurrence
	Count   int    `json:"count"`
	Level   string `json:"level"` // "warn" or "error"
	Kind    string `json:"kind"`
	Tag     string `json:"tag"`    // "[TCP]", "[DNS]", ... of the log line, without brackets; "" when none
	Network string `json:"net"`    // "TCP" or "UDP" for connection failures
	Node    string `json:"node"`   // node (or DIRECT) the connection went through
	Rule    string `json:"rule"`   // rule that picked the node, e.g. "DomainSuffix/example.com"
	Source  string `json:"source"` // app side address
	Dest    string `json:"dest"`   // host:port the app wanted
	Code    string `json:"code"`
	Reason  string `json:"reason"` // the core's own words
	Message string `json:"message"`
}

// Fold counts the lines of one folded kind.
type Fold struct {
	Kind  string `json:"kind"`
	Count int    `json:"count"`
	Time  int64  `json:"time"` // unix milliseconds of the latest one
}

// Snapshot is the answer of Query.
type Snapshot struct {
	Total  int     `json:"total"`  // problems seen since the core started, repeats included, folded ones not
	Items  []Entry `json:"items"`  // newest first
	Folded []Fold  `json:"folded"` // the folded kinds that happened, in a fixed order
}

var (
	mu      sync.Mutex
	entries []Entry
	total   int
	folded  = map[string]*Fold{}
)

var (
	// "[TCP] dial <node> (match <rule>) <src> --> <dst> error: <reason>"; the node name may hold spaces.
	reDialRule = regexp.MustCompile(`(?s)^\[(TCP|UDP)\] dial (.+?) \(match (.*?)\) (\S+) --> (\S+) error: (.*)$`)
	// the same without the rule part; (?s): the reason can have several lines (one per address tried)
	reDial = regexp.MustCompile(`(?s)^\[(TCP|UDP)\] dial (.+?) (\S+) --> (\S+) error: (.*)$`)
	reTag  = regexp.MustCompile(`^\[([A-Za-z0-9 _.-]{1,24})\]\s*`)

	reIPv6 = regexp.MustCompile(`\[[0-9a-fA-F]*:[0-9a-fA-F:.]*\]`)
	reIPv4 = regexp.MustCompile(`\b\d{1,3}(?:\.\d{1,3}){3}\b`)
)

func init() {
	go func() {
		sub := log.Subscribe()
		defer log.UnSubscribe(sub)

		for msg := range sub {
			if msg.LogLevel == log.WARNING || msg.LogLevel == log.ERROR {
				record(msg.LogLevel == log.ERROR, msg.Payload, time.Now())
			}
		}
	}()
}

// Query returns what was recorded, newest first.
func Query() Snapshot {
	mu.Lock()
	defer mu.Unlock()

	items := make([]Entry, len(entries))
	copy(items, entries)
	sort.SliceStable(items, func(i, j int) bool { return items[i].Time > items[j].Time })

	snap := Snapshot{Total: total, Items: items, Folded: []Fold{}}

	for _, kind := range []string{FoldNoIPv6, FoldClosed} {
		if f := folded[kind]; f != nil {
			snap.Folded = append(snap.Folded, *f)
		}
	}

	return snap
}

// Clear forgets everything recorded.
func Clear() {
	mu.Lock()
	defer mu.Unlock()

	entries = nil
	total = 0
	folded = map[string]*Fold{}
}

func record(isError bool, payload string, now time.Time) {
	e := parse(payload)
	e.Level = "warn"
	if isError {
		e.Level = "error"
	}
	ms := now.UnixMilli()
	e.Time, e.First, e.Count = ms, ms, 1

	mu.Lock()
	defer mu.Unlock()

	if kind := foldKind(e); kind != "" {
		f := folded[kind]
		if f == nil {
			f = &Fold{Kind: kind}
			folded[kind] = f
		}

		f.Count++
		f.Time = ms

		return
	}

	total++

	for i := range entries {
		o := &entries[i]
		if o.Kind == e.Kind && o.Node == e.Node && o.Dest == e.Dest && o.Code == e.Code &&
			(e.Kind == KindConnect || o.Message == e.Message) {
			o.Count++
			o.Time = ms
			o.Source = e.Source
			o.Reason = e.Reason
			o.Level = e.Level
			return
		}
	}

	if len(entries) >= maxEntries { // drop the one that was seen longest ago
		oldest := 0
		for i := range entries {
			if entries[i].Time < entries[oldest].Time {
				oldest = i
			}
		}
		entries[oldest] = e
		return
	}

	entries = append(entries, e)
}

// foldKind tells whether a line is one that is only counted. Only what is certainly harmless is folded:
// a network-unreachable failure whose addresses are all IPv6 (so the network just has no IPv6; one IPv4
// address in the text keeps it as a normal entry), and cancelled / closed connections.
func foldKind(e Entry) string {
	text := e.Reason
	if e.Kind != KindConnect {
		text = e.Message
	}

	r := strings.ToLower(text)

	switch {
	case strings.Contains(r, "context canceled"), strings.Contains(r, "use of closed network connection"),
		strings.Contains(r, "broken pipe"):
		return FoldClosed
	case strings.Contains(r, "network is unreachable") && reIPv6.MatchString(text) && !reIPv4.MatchString(text):
		return FoldNoIPv6
	}

	return ""
}

func parse(payload string) Entry {
	payload = clip(strings.TrimSpace(payload))

	if m := reDialRule.FindStringSubmatch(payload); m != nil {
		return connectEntry(payload, m[1], m[2], m[3], m[4], m[5], m[6])
	}
	if m := reDial.FindStringSubmatch(payload); m != nil {
		return connectEntry(payload, m[1], m[2], "", m[3], m[4], m[5])
	}

	e := Entry{Kind: KindOther, Code: CodeOther, Message: payload}
	if m := reTag.FindStringSubmatch(payload); m != nil {
		e.Tag = m[1]
		if strings.EqualFold(m[1], "DNS") {
			e.Kind = KindDNS
		}
	}
	e.Code = classify(payload)
	if e.Kind == KindDNS && e.Code == CodeOther {
		e.Code = CodeDNS
	}
	return e
}

func connectEntry(payload, network, node, rule, src, dst, reason string) Entry {
	return Entry{
		Kind:    KindConnect,
		Tag:     network,
		Network: network,
		Node:    node,
		Rule:    rule,
		Source:  src,
		Dest:    dst,
		Code:    classify(reason),
		Reason:  clip(reason),
		Message: payload,
	}
}

// Classify is classify for other packages (the config check shows its failures the same way).
func Classify(reason string) string { return classify(reason) }

// classify maps the core's wording to a short code. The order matters: the first match wins.
func classify(reason string) string {
	r := strings.ToLower(reason)

	has := func(parts ...string) bool {
		for _, p := range parts {
			if strings.Contains(r, p) {
				return true
			}
		}
		return false
	}

	switch {
	case has("no such host", "couldn't find ip", "could not find ip", "dns resolve", "dns request", "all dns", "lookup "):
		return CodeDNS
	case has("i/o timeout", "context deadline exceeded", "timed out", "timeout", "deadline exceeded"):
		return CodeTimeout
	case has("connection refused", "refused"):
		return CodeRefused
	case has("connection reset", "broken pipe", "forcibly closed"):
		return CodeReset
	case has("x509", "tls:", "certificate", "handshake"):
		return CodeTLS
	case has("auth", "unauthorized", "forbidden", "invalid password", "invalid user"):
		return CodeAuth
	case has("network is unreachable", "no route to host", "unreachable", "network down", "protocol not available"):
		return CodeNetwork
	case has("reject", "blocked", "not allowed", "denied"):
		return CodeReject
	case r == "eof" || strings.HasSuffix(r, ": eof") || has("unexpected eof"):
		return CodeEOF
	}

	return CodeOther
}

func clip(s string) string {
	if len(s) <= maxText {
		return s
	}
	s = s[:maxText]
	for len(s) > 0 && !validTail(s) { // do not cut a UTF-8 character in half
		s = s[:len(s)-1]
	}
	return s + "…"
}

func validTail(s string) bool {
	for i := len(s) - 1; i >= 0 && i >= len(s)-4; i-- {
		b := s[i]
		if b < 0x80 {
			return true
		}
		if b >= 0xC0 { // start byte: complete only if the whole character is there
			need := 2
			switch {
			case b >= 0xF0:
				need = 4
			case b >= 0xE0:
				need = 3
			}
			return len(s)-i >= need
		}
	}
	return true
}
