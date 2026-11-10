// Package conftest is the "config check" of the app (CUSTOM.md #22, not upstream): it really connects, once,
// when the user taps the button, to find out whether a field the user wrote in the config works.
//
// First item: ech-opts. Each item uses only the fields it needs (never the whole config). For every node
// with `ech-opts` the TLS handshake to each address of the node's server is made twice with a domain the
// user knows is blocked as the inner SNI: with ECH and without. The ECH config is the one written in
// `ech-opts.config`, or, when there is none, the one in the HTTPS record of `query-server-name` (asked from
// the DoH servers of the profile's `dns:` section, then a few public ones), like the core does. Go's own TLS
// tells whether the server accepted ECH, and the pair of results shows whether the block was got around.
//
// Power: nothing here runs on its own; one call is one test run.
package conftest

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"cfa/native/errlog"
	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	// SortCheck is not an order: asked with it, queryCustomConnections runs Run(filter) instead.
	SortCheck = 4

	attemptTimeout = 6 * time.Second
	parallel       = 4
	maxAddresses   = 8

	// Status of a node.
	StatusTested      = "tested"      // the handshakes were made, see the targets
	StatusDisabled    = "disabled"    // ech-opts written but enable is not true
	StatusUnsupported = "unsupported" // a node type that does not use this TLS handshake (QUIC based)
	StatusBadConfig   = "badconfig"   // ech-opts.config is not valid base64 / the node has no server or port
	StatusFetchFailed = "fetchfailed" // no config written and none could be fetched from DNS

	// Verdicts of one address.
	VerdictEffective = "effective" // ECH accepted, and the plain handshake was cut: the block is got around
	VerdictAccepted  = "accepted"  // ECH accepted, but the plain handshake also worked: this network does not block the domain
	VerdictRejected  = "rejected"  // the server refused the ECH config (out of date / wrong)
	VerdictFailed    = "failed"    // the ECH handshake failed while the plain one worked
	VerdictUnknown   = "unknown"   // cannot tell (both failed, or connected without ECH being accepted)
)

// quicTypes do not do a TLS-over-TCP handshake, so this test does not apply to them.
var quicTypes = map[string]bool{"hysteria": true, "hysteria2": true, "tuic": true, "wireguard": true, "ssh": true}

// Request is what the app sends (as the filter of the query).
type Request struct {
	Domain string `json:"domain"`
}

// Attempt is one handshake.
type Attempt struct {
	OK       bool   `json:"ok"`
	Accepted bool   `json:"accepted"` // ECH accepted by the server (only meaningful for the ECH handshake)
	Ms       int    `json:"ms"`
	Code     string `json:"code"`   // errlog codes (or "echrejected"), "" when OK
	Reason   string `json:"reason"` // the error text
}

// Target is the result for one address of a node's server.
type Target struct {
	Addr    string  `json:"addr"`
	Verdict string  `json:"verdict"`
	ECH     Attempt `json:"ech"`
	Plain   Attempt `json:"plain"`
}

// Item is the result for one node.
type Item struct {
	Node    string   `json:"node"`
	Server  string   `json:"server"`
	Status  string   `json:"status"`
	Source  string   `json:"source"` // "config" (written in ech-opts) or the DoH server that answered
	Query   string   `json:"query"`  // the name whose HTTPS record was asked, when fetched
	Error   string   `json:"error"`  // why the config could not be fetched
	Targets []Target `json:"targets"`
}

// Report is the answer of Run.
type Report struct {
	Domain string `json:"domain"`
	Items  []Item `json:"items"`
}

// Replaced in tests.
var (
	handshake = realHandshake
	fetch     = fetchECH
)

// Run tests every node that has ech-opts. It blocks until all attempts are over (at most a few
// attempt timeouts per node, four at a time).
func Run(filter string) Report {
	var req Request
	_ = json.Unmarshal([]byte(filter), &req)

	req.Domain = strings.TrimSpace(req.Domain)
	rep := Report{Domain: req.Domain, Items: []Item{}}

	nodes := ipdelay.RawProxies()
	names := make([]string, 0, len(nodes))

	for name, raw := range nodes {
		if _, has := raw["ech-opts"]; has {
			names = append(names, name)
		}
	}

	sort.Strings(names)

	items := make([]Item, len(names))
	lists := make([][]byte, len(names))
	alpns := make([][]string, len(names))
	doh := ipdelay.DoHServers()
	sem := make(chan struct{}, parallel)

	// 1. what each node needs: the ECH config and its addresses
	var wg sync.WaitGroup

	for i, name := range names {
		wg.Add(1)
		sem <- struct{}{}

		go func(i int, name string) {
			defer wg.Done()
			defer func() { <-sem }()

			items[i], lists[i], alpns[i] = prepare(name, nodes[name], req.Domain, doh)
		}(i, name)
	}

	wg.Wait()

	// 2. the handshakes, every address of every prepared node
	for i := range items {
		if items[i].Status != StatusTested {
			continue
		}

		for j := range items[i].Targets {
			wg.Add(1)
			sem <- struct{}{}

			go func(it *Item, t *Target, list []byte, alpn []string) {
				defer wg.Done()
				defer func() { <-sem }()

				ctx := context.Background()

				var inner sync.WaitGroup

				inner.Add(2)

				go func() { defer inner.Done(); t.ECH = handshake(ctx, t.Addr, req.Domain, list, alpn) }()
				go func() { defer inner.Done(); t.Plain = handshake(ctx, t.Addr, req.Domain, nil, alpn) }()

				inner.Wait()

				t.Verdict = verdict(t.ECH, t.Plain)
			}(&items[i], &items[i].Targets[j], lists[i], alpns[i])
		}
	}

	wg.Wait()

	rep.Items = items

	return rep
}

// prepare reads only the fields the ECH test needs: type, server, port, alpn, servername / sni and ech-opts.
func prepare(name string, raw map[string]any, domain string, doh []string) (Item, []byte, []string) {
	item := Item{Node: name, Targets: []Target{}}

	server, _ := raw["server"].(string)
	port := toInt(raw["port"])
	item.Server = net.JoinHostPort(server, strconv.Itoa(port))

	if t, _ := raw["type"].(string); quicTypes[strings.ToLower(t)] {
		item.Status = StatusUnsupported
		return item, nil, nil
	}

	opts, _ := raw["ech-opts"].(map[string]any)

	if enabled, _ := opts["enable"].(bool); !enabled {
		item.Status = StatusDisabled
		return item, nil, nil
	}

	if server == "" || port == 0 || domain == "" {
		item.Status = StatusBadConfig
		item.Error = "server / port / test domain missing"
		return item, nil, nil
	}

	alpn := toStrings(raw["alpn"])
	if len(alpn) == 0 {
		alpn = []string{"h2"}
	}

	var list []byte

	if cfg, _ := opts["config"].(string); strings.TrimSpace(cfg) != "" {
		b, err := decodeConfig(cfg)
		if err != nil {
			item.Status = StatusBadConfig
			item.Error = "ech-opts.config is not valid base64"
			return item, nil, nil
		}

		list = b
		item.Source = "config"
	} else {
		query := firstString(opts["query-server-name"], raw["servername"], raw["sni"], domain)
		item.Query = query

		b, from, err := fetch(context.Background(), query, doh)
		if err != nil {
			item.Status = StatusFetchFailed
			item.Error = err.Error()
			return item, nil, nil
		}

		list = b
		item.Source = from
	}

	addrs := ipdelay.NodeAddresses(name)
	if len(addrs) == 0 {
		addrs = []string{server}
	}

	if len(addrs) > maxAddresses {
		addrs = addrs[:maxAddresses]
	}

	for _, a := range addrs {
		item.Targets = append(item.Targets, Target{Addr: net.JoinHostPort(a, strconv.Itoa(port))})
	}

	item.Status = StatusTested

	return item, list, alpn
}

func verdict(ech, plain Attempt) string {
	switch {
	case ech.OK && ech.Accepted && !plain.OK:
		return VerdictEffective
	case ech.OK && ech.Accepted:
		return VerdictAccepted
	case ech.Code == "echrejected":
		return VerdictRejected
	case !ech.OK && plain.OK:
		return VerdictFailed
	default:
		return VerdictUnknown
	}
}

func decodeConfig(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, errors.New("empty")
	}

	if b, err := base64.StdEncoding.DecodeString(s); err == nil {
		return b, nil
	}

	return base64.RawStdEncoding.DecodeString(s)
}

func firstString(values ...any) string {
	for _, v := range values {
		if s, _ := v.(string); strings.TrimSpace(s) != "" {
			return strings.TrimSpace(s)
		}
	}

	return ""
}

func toStrings(v any) []string {
	switch t := v.(type) {
	case string:
		if t != "" {
			return []string{t}
		}
	case []string:
		return t
	case []any:
		var out []string

		for _, e := range t {
			if s, ok := e.(string); ok && s != "" {
				out = append(out, s)
			}
		}

		return out
	}

	return nil
}

func toInt(v any) int {
	switch t := v.(type) {
	case int:
		return t
	case int64:
		return int(t)
	case uint64:
		return int(t)
	case float64:
		return int(t)
	case string:
		n, _ := strconv.Atoi(t)
		return n
	}

	return 0
}

// realHandshake connects (the socket does not go through the VPN) and only does the TLS handshake. Certificates
// are not verified: this tests whether the handshake gets through, not who the server is. It uses Go's own TLS,
// so its fingerprint is Go's, not the node's client-fingerprint.
func realHandshake(ctx context.Context, addr, domain string, echList []byte, alpn []string) Attempt {
	ctx, cancel := context.WithTimeout(ctx, attemptTimeout)
	defer cancel()

	start := time.Now()
	a := Attempt{}

	fail := func(err error) Attempt {
		a.Ms = int(time.Since(start).Milliseconds())
		a.Reason = err.Error()
		a.Code = errlog.Classify(a.Reason)

		var rej *tls.ECHRejectionError
		if errors.As(err, &rej) {
			a.Code = "echrejected"
		}

		return a
	}

	conn, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return fail(err)
	}

	defer conn.Close()

	c := tls.Client(conn, &tls.Config{
		ServerName:                     domain,
		InsecureSkipVerify:             true,
		MinVersion:                     tls.VersionTLS13,
		NextProtos:                     alpn,
		EncryptedClientHelloConfigList: echList,
	})

	if err := c.HandshakeContext(ctx); err != nil {
		return fail(err)
	}

	a.OK = true
	a.Ms = int(time.Since(start).Milliseconds())
	a.Accepted = c.ConnectionState().ECHAccepted

	return a
}
