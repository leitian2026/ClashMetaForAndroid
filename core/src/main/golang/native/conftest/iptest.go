package conftest

// CUSTOM (CUSTOM.md #43, not upstream): "test other IPs" on the config check page. The user types addresses that
// are not necessarily in the config; one run tests each of them once, one after the other with a random 1 to 3
// second pause between two:
//
//   - mode "direct" (A): a TLS connection straight to the address (the socket does not go through the VPN), with the
//     domain of the page as SNI and Host. Shows the time to connect and handshake, the TLS version, the ALPN, whether
//     the server accepted ECH (the ECH config is fetched once for the domain), the operator (AS) of the address and,
//     for Cloudflare, the data centre read from the `Cf-Ray` header of the answer;
//   - mode "node" (B): the chosen node copied with its `server` replaced by the address, like the per-address test
//     of #1 (ipdelay.TryIP). Shows the delay, the exit country, and how it compares with the quickest address the
//     last speed test found in the node's `hosts:` entry.
//   - mode "nodes": nothing is tested, the names of the nodes to choose from come back.
//
// Power: nothing runs on its own; one call is one run, and CancelIPTest stops it.

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"math/rand"
	"net"
	"net/http"
	"net/netip"
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
	// CancelIPTestID is the special id of closeCustomConnection that stops a running IP test.
	CancelIPTestID = "!cancel-iptest"

	ipGapMin     = 1 * time.Second
	ipGapMax     = 3 * time.Second
	httpTimeout  = 4 * time.Second
	asnTimeout   = 4 * time.Second
	echFetchWait = 8 * time.Second
)

// IPRequest is what the app sends (as the filter of the query, with "kind":"ip").
type IPRequest struct {
	Kind   string `json:"kind"`
	Mode   string `json:"mode"`   // "direct", "node" or "nodes"
	Domain string `json:"domain"` // direct: SNI and Host
	Node   string `json:"node"`   // node: the node to copy
	IPs    string `json:"ips"`    // the text as typed
}

// IPRow is the result for one address.
type IPRow struct {
	IP      string `json:"ip"`
	OK      bool   `json:"ok"`
	Ms      int    `json:"ms"`
	Code    string `json:"code"`   // errlog code (or "echrejected"), "" when OK
	Reason  string `json:"reason"` // the failure in the core's words, "" when OK
	InHosts bool   `json:"inHosts"`

	// node mode
	Country string `json:"country"`
	Faster  int    `json:"faster"` // milliseconds quicker than the reference address, 0 when not quicker

	// direct mode
	TLS  string `json:"tls"`  // "1.2" / "1.3"
	ALPN string `json:"alpn"` // "h2" / "http/1.1"
	ECH  string `json:"ech"`  // "accepted", "notaccepted", "rejected", "none" (the domain has no ECH config)
	ASN  string `json:"asn"`  // "AS13335 Cloudflare, Inc."
	Colo string `json:"colo"` // Cloudflare data centre
}

// IPRef is the quickest address of the node's `hosts:` entry in its last speed test.
type IPRef struct {
	IP string `json:"ip"`
	Ms int    `json:"ms"`
	At int64  `json:"at"` // unix milliseconds
}

// IPReport is the answer of RunIP.
type IPReport struct {
	Mode      string   `json:"mode"`
	Domain    string   `json:"domain"`
	Node      string   `json:"node"`
	Nodes     []string `json:"nodes"`     // mode "nodes"
	Rows      []IPRow  `json:"rows"`      // quickest first, the ones that failed last
	Invalid   []string `json:"invalid"`   // words that were not an address
	Cut       int      `json:"cut"`       // addresses over the limit, not tested
	Ref       *IPRef   `json:"ref"`       // node mode
	ECHSource string   `json:"echSource"` // direct mode: the server that gave the ECH config, "" when there is none
	At        int64    `json:"at"`
	Cancelled bool     `json:"cancelled"`
	Error     string   `json:"error"`
}

// ipPort is the port tested in direct mode (a variable only so that a test can point it at a local server).
var ipPort = "443"

var (
	ipRunLock   sync.Mutex
	ipRunCancel context.CancelFunc

	asnCache sync.Map // address -> "AS13335 Cloudflare, Inc." (only what was found)
)

// CancelIPTest stops the running IP test (the rows done so far are still returned by RunIP).
func CancelIPTest() bool {
	ipRunLock.Lock()
	defer ipRunLock.Unlock()

	if ipRunCancel == nil {
		return false
	}

	ipRunCancel()

	return true
}

// RunIP runs one IP test. It blocks until every address is done (or CancelIPTest was called).
func RunIP(filter string) IPReport {
	var req IPRequest
	_ = json.Unmarshal([]byte(filter), &req)

	req.Domain = strings.TrimSpace(req.Domain)
	rep := IPReport{Mode: req.Mode, Domain: req.Domain, Node: req.Node, Rows: []IPRow{}, Invalid: []string{}, Nodes: []string{}}

	if req.Mode == "nodes" {
		for name, raw := range ipdelay.RawProxies() {
			if server, _ := raw["server"].(string); server != "" {
				rep.Nodes = append(rep.Nodes, name)
			}
		}

		sort.Strings(rep.Nodes)

		return rep
	}

	ips, invalid := parseIPList(req.IPs)

	rep.Invalid = append(rep.Invalid, invalid...)

	if len(ips) > maxIPTest {
		rep.Cut = len(ips) - maxIPTest
		ips = ips[:maxIPTest]
	}

	if len(ips) == 0 {
		rep.Error = "no address"

		return rep
	}

	ctx, cancel := context.WithCancel(context.Background())

	ipRunLock.Lock()
	if ipRunCancel != nil {
		ipRunCancel() // an earlier run that is still going is stopped
	}

	ipRunCancel = cancel
	ipRunLock.Unlock()

	defer func() {
		cancel()

		ipRunLock.Lock()
		ipRunCancel = nil
		ipRunLock.Unlock()
	}()

	switch req.Mode {
	case "direct":
		runDirect(ctx, &rep, ips)
	case "node":
		runNode(ctx, &rep, ips)
	default:
		rep.Error = "unknown mode"
	}

	rep.Cancelled = ctx.Err() != nil
	rep.At = time.Now().UnixMilli()

	sortRows(rep.Rows)

	return rep
}

// pause waits a random 1 to 3 seconds; false when the run was cancelled meanwhile.
func pause(ctx context.Context) bool {
	d := ipGapMin + time.Duration(rand.Int63n(int64(ipGapMax-ipGapMin)))

	select {
	case <-ctx.Done():
		return false
	case <-time.After(d):
		return true
	}
}

// configHosts is every address any node's `hosts:` entry (or literal server) has.
func configHosts() map[string]bool {
	out := map[string]bool{}

	for name := range ipdelay.RawProxies() {
		for _, ip := range ipdelay.NodeAddresses(name) {
			out[ip] = true
		}
	}

	return out
}

func runNode(ctx context.Context, rep *IPReport, ips []netip.Addr) {
	if rep.Node == "" {
		rep.Error = "no node"

		return
	}

	hosts := map[string]bool{}
	for _, ip := range ipdelay.NodeAddresses(rep.Node) {
		hosts[ip] = true
	}

	// the quickest address of the last speed test of this node, to compare with
	for _, r := range ipdelay.Get(rep.Node) {
		if r.Delay > 0 && (rep.Ref == nil || r.Delay < rep.Ref.Ms) {
			rep.Ref = &IPRef{IP: r.IP, Ms: r.Delay, At: r.At}
		}
	}

	for i, ip := range ips {
		if i > 0 && !pause(ctx) {
			return
		}

		if ctx.Err() != nil {
			return
		}

		s := ip.String()
		res := ipdelay.TryIP(rep.Node, s)

		row := IPRow{IP: s, InHosts: hosts[s], Ms: res.Delay, Country: res.Country}

		if res.Err == "" {
			row.OK = true

			if rep.Ref != nil && res.Delay < rep.Ref.Ms {
				row.Faster = rep.Ref.Ms - res.Delay
			}
		} else {
			row.Reason = res.Err
			row.Code = errlog.Classify(res.Err)
		}

		rep.Rows = append(rep.Rows, row)
	}
}

func runDirect(ctx context.Context, rep *IPReport, ips []netip.Addr) {
	if rep.Domain == "" {
		rep.Error = "no domain"

		return
	}

	// one ECH config for the domain, asked from the DoH servers of the profile and a few public ones
	var echList []byte

	fctx, fcancel := context.WithTimeout(ctx, echFetchWait)
	cfg, source, err := fetch(fctx, rep.Domain, ipdelay.DoHServers())
	fcancel()

	if err == nil {
		echList, rep.ECHSource = cfg, source
	}

	inConfig := configHosts()

	for i, ip := range ips {
		if i > 0 && !pause(ctx) {
			return
		}

		if ctx.Err() != nil {
			return
		}

		row := probeIP(ctx, ip, rep.Domain, echList)
		row.InHosts = inConfig[row.IP]

		rep.Rows = append(rep.Rows, row)
	}
}

// probeIP makes one TLS connection to ip:443 with `domain` as SNI. With an ECH config the first try offers ECH; when
// the server refuses it, a second plain one still tells whether the address works.
func probeIP(ctx context.Context, ip netip.Addr, domain string, echList []byte) IPRow {
	row := IPRow{IP: ip.String(), ECH: "none"}

	addr := net.JoinHostPort(row.IP, ipPort)

	conn, ms, err := handshakeTo(ctx, addr, domain, echList)

	if len(echList) > 0 {
		var rej *tls.ECHRejectionError

		switch {
		case err == nil && conn.ConnectionState().ECHAccepted:
			row.ECH = "accepted"
		case err == nil:
			row.ECH = "notaccepted"
		case errors.As(err, &rej):
			row.ECH = "rejected"

			conn, ms, err = handshakeTo(ctx, addr, domain, nil)
		default:
			row.ECH = "" // failed for another reason: nothing is known about ECH
		}
	}

	row.Ms = ms

	if err != nil {
		row.Reason = err.Error()
		row.Code = errlog.Classify(row.Reason)

		return row
	}

	row.OK = true

	state := conn.ConnectionState()
	row.TLS = tlsVersion(state.Version)
	row.ALPN = state.NegotiatedProtocol

	// the answer of the server (for the data centre) and the operator of the address; neither is needed for the
	// address to count as working, so a failure of either only leaves its field empty
	row.Colo = fetchColo(ctx, conn, domain)
	row.ASN = lookupASN(ctx, row.IP)

	return row
}

func tlsVersion(v uint16) string {
	switch v {
	case tls.VersionTLS13:
		return "1.3"
	case tls.VersionTLS12:
		return "1.2"
	case tls.VersionTLS11:
		return "1.1"
	case tls.VersionTLS10:
		return "1.0"
	}

	return ""
}

// handshakeTo connects to addr and does the TLS handshake. The time is from dialing to the end of the handshake.
// The caller owns (and must close) the returned connection when err is nil; it is closed here when there is an error.
func handshakeTo(ctx context.Context, addr, domain string, echList []byte) (*tls.Conn, int, error) {
	ctx, cancel := context.WithTimeout(ctx, attemptTimeout)
	defer cancel()

	start := time.Now()

	raw, err := dialer.DialContext(ctx, "tcp", addr) // the socket does not go through the VPN
	if err != nil {
		return nil, int(time.Since(start).Milliseconds()), err
	}

	cfg := &tls.Config{
		ServerName:         domain,
		InsecureSkipVerify: true,
		MinVersion:         tls.VersionTLS12,
		NextProtos:         []string{"h2", "http/1.1"},
	}

	if len(echList) > 0 {
		cfg.MinVersion = tls.VersionTLS13
		cfg.EncryptedClientHelloConfigList = echList
	}

	c := tls.Client(raw, cfg)

	if err := c.HandshakeContext(ctx); err != nil {
		_ = raw.Close()

		return nil, int(time.Since(start).Milliseconds()), err
	}

	return c, int(time.Since(start).Milliseconds()), nil
}

// fetchColo sends HEAD / over the connection that was just made and reads the Cloudflare data centre from the
// `Cf-Ray` header. It closes the connection. "" when the server is not Cloudflare or does not answer.
func fetchColo(ctx context.Context, conn *tls.Conn, domain string) string {
	defer conn.Close()

	ctx, cancel := context.WithTimeout(ctx, httpTimeout)
	defer cancel()

	used := false

	tr := &http.Transport{
		DialTLSContext: func(context.Context, string, string) (net.Conn, error) {
			if used {
				return nil, errors.New("connection already used")
			}

			used = true

			return conn, nil
		},
		ForceAttemptHTTP2: true,
		DisableKeepAlives: true,
	}
	defer tr.CloseIdleConnections()

	req, err := http.NewRequestWithContext(ctx, http.MethodHead, "https://"+domain+"/", nil)
	if err != nil {
		return ""
	}

	resp, err := tr.RoundTrip(req)
	if err != nil {
		return ""
	}

	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	_ = resp.Body.Close()

	return coloOf(resp.Header.Get("Cf-Ray"))
}

// lookupASN asks a public service who runs the address. "" when it cannot be found (the answer is kept for the
// next run when it is found).
func lookupASN(ctx context.Context, ip string) string {
	if v, ok := asnCache.Load(ip); ok {
		return v.(string)
	}

	client := &http.Client{
		Timeout: asnTimeout,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, addr) // the socket does not go through the VPN
			},
			TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
			ForceAttemptHTTP2: true,
		},
	}
	defer client.CloseIdleConnections()

	get := func(url string) []byte {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return nil
		}

		resp, err := client.Do(req)
		if err != nil {
			return nil
		}
		defer resp.Body.Close()

		if resp.StatusCode != http.StatusOK {
			return nil
		}

		body, _ := io.ReadAll(io.LimitReader(resp.Body, 8192))

		return body
	}

	text := ""

	// ipinfo.io/<ip>/org answers "AS13335 Cloudflare, Inc."
	if body := get("https://ipinfo.io/" + ip + "/org"); len(body) > 0 {
		s := strings.TrimSpace(string(body))
		if strings.HasPrefix(s, "AS") && len(s) < 200 {
			text = s
		}
	}

	if text == "" {
		if body := get("https://ipwho.is/" + ip + "?fields=success,connection"); len(body) > 0 {
			var v struct {
				Success    bool `json:"success"`
				Connection struct {
					ASN int    `json:"asn"`
					Org string `json:"org"`
				} `json:"connection"`
			}

			if json.Unmarshal(body, &v) == nil && v.Success && v.Connection.ASN > 0 {
				text = asnText(strconv.Itoa(v.Connection.ASN), v.Connection.Org)
			}
		}
	}

	if text != "" {
		asnCache.Store(ip, text)
	}

	return text
}
