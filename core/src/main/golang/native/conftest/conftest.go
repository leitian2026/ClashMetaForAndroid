// Package conftest is the "config check" of the app (CUSTOM.md #22, not upstream): it really connects, once,
// when the user taps the button, to find out whether a field the user wrote in the config works.
//
// First item: ech-opts. For every node with `ech-opts` the TLS handshake to the node's server is made
// twice with a domain the user knows is blocked as the inner SNI: with ECH (the config the user wrote) and
// without. Go's own TLS tells whether the server accepted ECH, and the pair of results shows whether the
// block was really got around.
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

	// Verdicts of an ech item.
	VerdictEffective = "effective" // ECH accepted, and the plain handshake was cut: the block is got around
	VerdictAccepted  = "accepted"  // ECH accepted, but the plain handshake also worked: this network does not block the domain
	VerdictRejected  = "rejected"  // the server refused the ECH config (out of date / wrong)
	VerdictFailed    = "failed"    // the ECH handshake failed while the plain one worked
	VerdictUnknown   = "unknown"   // both failed: cannot tell (server does not host the domain, node down, ...)
	VerdictNoConfig  = "noconfig"  // ech-opts without a usable config, the test needs the config in the file
	VerdictDisabled  = "disabled"  // ech-opts written but enable is not true
)

// Request is what the app sends (as the filter of the query).
type Request struct {
	Domain string `json:"domain"`
}

// Attempt is one handshake.
type Attempt struct {
	OK       bool   `json:"ok"`
	Accepted bool   `json:"accepted"` // ECH accepted by the server (only meaningful for the ECH handshake)
	Ms       int    `json:"ms"`
	Code     string `json:"code"`   // errlog codes, "" when OK
	Reason   string `json:"reason"` // the error text
}

// Item is the result for one node.
type Item struct {
	Node    string  `json:"node"`
	Server  string  `json:"server"`
	Verdict string  `json:"verdict"`
	ECH     Attempt `json:"ech"`
	Plain   Attempt `json:"plain"`
}

// Report is the answer of Run.
type Report struct {
	Domain string `json:"domain"`
	Items  []Item `json:"items"`
}

// handshake is replaced in tests.
var handshake = realHandshake

// Run tests every node that has ech-opts. It blocks until all attempts are over (at most a few
// attempt timeouts per node, four nodes at a time).
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
	sem := make(chan struct{}, parallel)

	var wg sync.WaitGroup

	for i, name := range names {
		wg.Add(1)
		sem <- struct{}{}

		go func(i int, name string) {
			defer wg.Done()
			defer func() { <-sem }()

			items[i] = testNode(name, nodes[name], req.Domain)
		}(i, name)
	}

	wg.Wait()

	rep.Items = items

	return rep
}

func testNode(name string, raw map[string]any, domain string) Item {
	item := Item{Node: name}

	server, _ := raw["server"].(string)
	port := toInt(raw["port"])
	item.Server = net.JoinHostPort(server, strconv.Itoa(port))

	opts, _ := raw["ech-opts"].(map[string]any)

	if enabled, _ := opts["enable"].(bool); !enabled {
		item.Verdict = VerdictDisabled
		return item
	}

	cfg, _ := opts["config"].(string)

	list, err := decodeConfig(cfg)
	if err != nil || len(list) == 0 || server == "" || port == 0 || domain == "" {
		item.Verdict = VerdictNoConfig
		return item
	}

	ctx := context.Background()

	var wg sync.WaitGroup

	wg.Add(2)

	go func() { defer wg.Done(); item.ECH = handshake(ctx, item.Server, domain, list) }()
	go func() { defer wg.Done(); item.Plain = handshake(ctx, item.Server, domain, nil) }()

	wg.Wait()

	item.Verdict = verdict(item.ECH, item.Plain)

	return item
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
// are not verified: this tests whether the handshake gets through, not who the server is.
func realHandshake(ctx context.Context, addr, domain string, echList []byte) Attempt {
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
