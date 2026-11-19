package conftest

// CUSTOM (CUSTOM.md #43, not upstream): "test other IPs" on the config check page. The user types addresses that
// are not necessarily in the config; one run tests each of them once, one after the other with a random 1 to 3
// second pause between two:
//
//   - mode "direct" (A): a TLS connection straight to the address (the socket does not go through the VPN), with the
//     domain of the page as SNI and Host. Shows the time to connect and handshake, the TLS version, the ALPN, whether
//     the server accepted ECH (the ECH config is fetched once for the domain) and, when the answer of the server
//     shows Cloudflare (`Cf-Ray` header or `Server: cloudflare`), that and the data centre read from `Cf-Ray`. The
//     operator (AS) of an address is not asked on its own: only when the user taps "look up" on a row (mode "asn");
//
//   - mode "node" (B): the chosen node copied with its `server` replaced by the address, like the per-address test
//     of #1 (ipdelay.TryIP). Shows the delay, the exit country, and how it compares with the quickest address the
//     last speed test found in the node's `hosts:` entry. A domain in the list is tested the same way, as the node's
//     `server`; the core resolves it when it dials. In mode "direct" a domain is not tested (it is reported in
//     `skipped`), only addresses are.
//   - mode "nodes": nothing is tested, the names of the nodes to choose from come back.
//   - mode "asn": the operator of the one address in `ips` is asked from public services (ipinfo.io, ipwho.is).
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
	// CUSTOM.md #52: the per-address modes "probe", "nodeone", "download", "colo" (iptest_probe.go)
	Ports string `json:"ports"` // probe: the ports to try, as typed ("" = the default list)
	URL   string `json:"url"`   // download: the address to download ("" = the default)
	// CUSTOM.md #66: nodeone: the address of a site the user really uses; the tries go to it through the node ("" = the node's own test URL)
	Target string `json:"target"`
	// parse: read the typed list without testing; Direct drops the domains (only node mode tests them)
	Direct bool `json:"direct"`
}

// IPRow is the result for one address.
type IPRow struct {
	IP       string `json:"ip"` // the address, or the domain when IsDomain
	IsDomain bool   `json:"isDomain"`
	OK       bool   `json:"ok"`
	Ms       int    `json:"ms"`
	Code     string `json:"code"`   // errlog code (or "echrejected"), "" when OK
	Reason   string `json:"reason"` // the failure in the core's words, "" when OK
	InHosts  bool   `json:"inHosts"`

	// node mode
	Country string `json:"country"` // the exit country (落地); direct mode (CUSTOM.md #62): only when a node of the config has the typed domain as its server
	Faster  int    `json:"faster"`  // milliseconds quicker than the reference address, 0 when not quicker

	// direct mode
	TLS  string `json:"tls"`  // "1.2" / "1.3"
	ALPN string `json:"alpn"` // "h2" / "http/1.1"
	ECH  string `json:"ech"`  // "accepted", "notaccepted", "rejected", "none" (the domain has no ECH config)
	ASN  string `json:"asn"`  // "AS13335 Cloudflare, Inc." (only after a "look up": mode "asn", or kept from an earlier one)
	Geo  string `json:"geo"`  // CUSTOM.md #62: the country the address is registered in (归属地), "US"; from the same answer as ASN
	CF   bool   `json:"cf"`   // the answer of the server shows Cloudflare (a positive sign only)
	Colo string `json:"colo"` // Cloudflare data centre
	// CUSTOM.md #63: node mode: why the data centre was not read ("nosni" = the node has no domain to use as SNI, else the
	// failure of the extra direct connection), "" when it was read or not asked
	ColoErr string `json:"coloErr"`
	// CUSTOM.md #66: node mode with a target address: what the site itself answered (the first, most common status; 0 = no
	// answer), where it redirected to, and the data centre of the answer's `Cf-Ray` (the exit side, not the entry side
	// of `Colo`; "" when the site is not behind Cloudflare)
	TargetStatus int    `json:"targetStatus"`
	TargetNote   string `json:"targetNote"`
	TargetColo   string `json:"targetColo"`
	// CUSTOM.md #156 step 41: TLS / certificate / response headers from the same target GET (no extra request).
	TargetTLS              string `json:"targetTls"`
	TargetALPN             string `json:"targetAlpn"`
	TargetCertSubject      string `json:"targetCertSubject"`
	TargetCertIssuer       string `json:"targetCertIssuer"`
	TargetCertNotBefore    string `json:"targetCertNotBefore"`
	TargetCertNotAfter     string `json:"targetCertNotAfter"`
	TargetCertDNSNames     string `json:"targetCertDnsNames"`
	TargetCertIPAddresses  string `json:"targetCertIpAddresses"`
	TargetCertVerification string `json:"targetCertVerification"`
	TargetServer           string `json:"targetServer"`
	TargetCfRay            string `json:"targetCfRay"`
	TargetContentType      string `json:"targetContentType"`
	TargetRetryAfter       string `json:"targetRetryAfter"`
	TargetCfMitigated      string `json:"targetCfMitigated"`
	// CUSTOM.md #67: what the page says about being moved or refused ("refresh", "script", "region", "" when nothing) and
	// the address or the words that gave it away; a guess from the first 64 KB of the page
	TargetSoft     string `json:"targetSoft"`
	TargetSoftNote string `json:"targetSoftNote"`

	// CUSTOM.md #52: ten tries, the ports and the download test (iptest_probe.go)
	Sent  int       `json:"sent"`  // tries made
	Loss  int       `json:"loss"`  // percent of the tries that failed
	Min   int       `json:"min"`   // milliseconds, of the tries that worked
	Max   int       `json:"max"`   //
	Avg   int       `json:"avg"`   //
	Ports []PortRes `json:"ports"` // direct mode, working address: the ports tried
	Port  string    `json:"port"`  // node mode: the node's own port (not tested)

	Speed      float64 `json:"speed"`      // download test: megabytes per second, 0 when not done / failed
	SpeedBytes int64   `json:"speedBytes"` // bytes read
	SpeedMs    int     `json:"speedMs"`    // time of the download
	SpeedErr   string  `json:"speedErr"`   // why the download failed ("" when it worked)

	// CUSTOM.md #68: how much the tries that worked differ from each other (average distance from Avg, milliseconds), and
	// where the time goes: direct mode TCP connect / TLS handshake (averages of the tries that worked) and the first
	// byte of one HEAD over the kept connection; node mode dial / handshake inside the tunnel / first byte (averages of the
	// tries that measured them). 0 = not measured.
	Jitter int `json:"jitter"`
	Dial   int `json:"dial"`
	Shake  int `json:"shake"`
	First  int `json:"first"`
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
	Invalid   []string `json:"invalid"`   // words that were neither an address nor a domain
	Skipped   []string `json:"skipped"`   // direct mode: domains, which only the node mode tests
	Cut       int      `json:"cut"`       // addresses over the limit, not tested
	Ref       *IPRef   `json:"ref"`       // node mode
	ECHSource string   `json:"echSource"` // direct mode: the server that gave the ECH config, "" when there is none
	At        int64    `json:"at"`
	Cancelled bool     `json:"cancelled"`
	Error     string   `json:"error"`
	// CUSTOM.md #52: mode "colo": data centre code -> city, as Cloudflare lists them
	Colos map[string]string `json:"colos"`
	// CUSTOM.md #62: mode "colo": data centre code -> country code ("cca2" of the same list)
	ColoCountries map[string]string `json:"coloCountries"`
	// CUSTOM.md #62: mode "probe": the node whose server is the typed domain, through which the exit country was asked
	// ("" when the config has none, then no exit country is shown)
	LandNode string `json:"landNode"`
	// CUSTOM.md #69: mode "config": the addresses the profile uses for its nodes, the servers that gave none, the
	// addresses of resolved domains over the per-domain limit, and the ones left out by a node's `ip-version`
	Config        []ConfigIP   `json:"config"`
	ConfigFail    []ConfigFail `json:"configFail"`
	ConfigOmitted int          `json:"configOmitted"`
	ConfigFamily  int          `json:"configFamily"`
}

// ipPort is the port tested in direct mode (a variable only so that a test can point it at a local server).
var ipPort = "443"

var (
	ipRunLock   sync.Mutex
	ipRunCancel context.CancelFunc
	ipRunID     uint64

	ownerCache = struct {
		sync.Mutex
		byIP map[string][2]string
	}{byIP: make(map[string][2]string)}

	// Public owner lookup is called only by the explicit "look up" action.
	ownerSource = askOwner
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
	rep := IPReport{Mode: req.Mode, Domain: req.Domain, Node: req.Node, Rows: []IPRow{}, Invalid: []string{}, Skipped: []string{}, Nodes: []string{}, Config: []ConfigIP{}, ConfigFail: []ConfigFail{}}

	if req.Mode == "nodes" {
		for name, raw := range ipdelay.RawProxies() {
			if server, _ := raw["server"].(string); server != "" {
				rep.Nodes = append(rep.Nodes, name)
			}
		}

		sort.Strings(rep.Nodes)

		return rep
	}

	if req.Mode == "config" { // CUSTOM.md #69: read the addresses of the profile (iptest_config.go)
		read := readConfigIPs()

		rep.Config = read.IPs
		rep.ConfigFail = read.Fails
		rep.ConfigOmitted = read.Omitted
		rep.ConfigFamily = read.Family
		rep.At = time.Now().UnixMilli()

		return rep
	}

	if req.Mode == "asn" {
		lookupOwners(&rep, req.IPs)

		return rep
	}

	// CUSTOM.md #52: one address per call (the app loops, so it can draw the table and the log after each)
	switch req.Mode {
	case "parse", "probe", "nodeone", "download", "colo":
		return runOne(req, rep)
	}

	list, invalid := parseTargets(req.IPs)

	rep.Invalid = append(rep.Invalid, invalid...)

	if req.Mode == "direct" {
		var addrs []target

		for _, t := range list {
			if t.IsDomain {
				rep.Skipped = append(rep.Skipped, t.Text)
			} else {
				addrs = append(addrs, t)
			}
		}

		if len(addrs) == 0 && len(rep.Skipped) > 0 {
			rep.Error = "domain only node"

			return rep
		}

		list = addrs
	}

	if len(list) > maxIPTest {
		rep.Cut = len(list) - maxIPTest
		list = list[:maxIPTest]
	}

	if len(list) == 0 {
		rep.Error = "no address"

		return rep
	}

	ctx, done := beginRun()
	defer done()

	switch req.Mode {
	case "direct":
		runDirect(ctx, &rep, list)
	case "node":
		runNode(ctx, &rep, list)
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

func runNode(ctx context.Context, rep *IPReport, list []target) {
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

	for i, t := range list {
		if i > 0 && !pause(ctx) {
			return
		}

		if ctx.Err() != nil {
			return
		}

		res := ipdelay.TryIP(rep.Node, t.Text, t.IsDomain)

		row := IPRow{IP: t.Text, IsDomain: t.IsDomain, InHosts: !t.IsDomain && hosts[t.Text], Ms: res.Delay}

		if res.Err == "" {
			row.OK = true
			row.Country = ipdelay.ExitCountry(rep.Node, t.Text, t.IsDomain) // CUSTOM.md #72: asked now, once

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

func runDirect(ctx context.Context, rep *IPReport, list []target) {
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

	for i, t := range list {
		if i > 0 && !pause(ctx) {
			return
		}

		if ctx.Err() != nil {
			return
		}

		row := probeIP(ctx, netip.MustParseAddr(t.Text), rep.Domain, echList)
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
	row.TLS = ipdelay.TLSVersionName(state.Version)
	row.ALPN = state.NegotiatedProtocol

	// the answer of the server (is it Cloudflare, and the data centre): not needed for the address to count as
	// working, so a failure only leaves the fields empty. The operator is not asked here (a tap asks it, mode
	// "asn"); CUSTOM.md #72: nothing is shown from memory any more, so it stays empty until it is asked.
	row.Colo, row.CF = fetchEvidence(ctx, conn, domain)

	return row
}

// handshakeTo connects to addr and does the TLS handshake. The time is from dialing to the end of the handshake.
// The caller owns (and must close) the returned connection when err is nil; it is closed here when there is an error.
func handshakeTo(ctx context.Context, addr, domain string, echList []byte) (*tls.Conn, int, error) {
	return handshakeWithin(ctx, addr, domain, echList, attemptTimeout)
}

// handshakeWithin is handshakeTo with its own time limit (CUSTOM.md #52: the ten tries and the ports take 3 seconds each).
func handshakeWithin(ctx context.Context, addr, domain string, echList []byte, limit time.Duration) (*tls.Conn, int, error) {
	c, ms, _, err := handshakeSplit(ctx, addr, domain, echList, limit)

	return c, ms, err
}

// handshakeSplit (CUSTOM.md #68) is handshakeWithin that also says how many of the milliseconds were the TCP connect
// (0 when it did not get that far); the rest of the total is the TLS handshake.
func handshakeSplit(ctx context.Context, addr, domain string, echList []byte, limit time.Duration) (*tls.Conn, int, int, error) {
	ctx, cancel := context.WithTimeout(ctx, limit)
	defer cancel()

	start := time.Now()

	raw, err := dialer.DialContext(ctx, "tcp", addr) // the socket does not go through the VPN
	if err != nil {
		return nil, int(time.Since(start).Milliseconds()), 0, err
	}

	tcpMs := int(time.Since(start).Milliseconds())

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

		return nil, int(time.Since(start).Milliseconds()), tcpMs, err
	}

	return c, int(time.Since(start).Milliseconds()), tcpMs, nil
}

// fetchEvidence sends HEAD / over the connection that was just made and reads from the answer whether it shows
// Cloudflare (`Cf-Ray` header or `Server: cloudflare`) and the data centre from `Cf-Ray`. It closes the connection.
// ("", false) when the server does not answer or shows neither.
func fetchEvidence(ctx context.Context, conn *tls.Conn, domain string) (string, bool) {
	colo, cf, _ := fetchEvidenceTimed(ctx, conn, domain)

	return colo, cf
}

// fetchEvidenceTimed (CUSTOM.md #68) is fetchEvidence that also says how long the answer's headers took after the
// request was sent on the (already open) connection, in milliseconds, at least 1; 0 when there was no answer.
func fetchEvidenceTimed(ctx context.Context, conn *tls.Conn, domain string) (string, bool, int) {
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
		return "", false, 0
	}

	sent := time.Now()

	resp, err := tr.RoundTrip(req)
	if err != nil {
		return "", false, 0
	}

	first := int(time.Since(sent).Milliseconds())
	if first < 1 {
		first = 1
	}

	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	_ = resp.Body.Close()

	ray := resp.Header.Get("Cf-Ray")

	return coloOf(ray), isCloudflare(ray, resp.Header.Get("Server")), first
}

// lookupOwners (mode "asn") asks who runs the address typed in `text` (one address; a domain or a second word is
// not looked up). The row has the address and the answer; no row and Error "not found" when nothing was found.
func lookupOwners(rep *IPReport, text string) {
	list, _ := parseTargets(text)

	ctx, cancel := context.WithTimeout(context.Background(), 2*asnTimeout+time.Second)
	defer cancel()

	for _, t := range list {
		if t.IsDomain {
			continue
		}

		asn, geo := lookupOwner(ctx, t.Text)
		if asn != "" || geo != "" {
			rep.Rows = append(rep.Rows, IPRow{IP: t.Text, ASN: asn, Geo: geo})
		}

		break
	}

	if len(rep.Rows) == 0 {
		rep.Error = "not found"
	}
}

// lookupASN asks a public service who runs the address (only when the user taps "look up"). "" when it cannot be
// found (the answer is kept for the next run when it is found).
func lookupASN(ctx context.Context, ip string) string {
	asn, _ := lookupOwner(ctx, ip)

	return asn
}

func validCC(code string) bool {
	return len(code) == 2 && code[0] >= 'A' && code[0] <= 'Z' && code[1] >= 'A' && code[1] <= 'Z'
}

// lookupOwner is reached only by the explicit lookup action. Successful or partial results are reused in this
// process; failed results are not cached, so background probing never retries these services.
func lookupOwner(ctx context.Context, ip string) (asn, geo string) {
	if ctx.Err() != nil {
		return "", ""
	}

	ownerCache.Lock()
	if cached, ok := ownerCache.byIP[ip]; ok {
		ownerCache.Unlock()
		return cached[0], cached[1]
	}
	ownerCache.Unlock()

	asn, geo = ownerSource(ctx, ip)
	if asn != "" || geo != "" {
		ownerCache.Lock()
		if cached, ok := ownerCache.byIP[ip]; ok {
			asn, geo = cached[0], cached[1]
		} else {
			ownerCache.byIP[ip] = [2]string{asn, geo}
		}
		ownerCache.Unlock()
	}
	return asn, geo
}

func askOwner(ctx context.Context, ip string) (asn, geo string) {
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

	// ipinfo.io/<ip>/json answers {"org":"AS13335 Cloudflare, Inc.","country":"US",...}
	if body := get("https://ipinfo.io/" + ip + "/json"); len(body) > 0 {
		var v struct {
			Org     string `json:"org"`
			Country string `json:"country"`
		}

		if json.Unmarshal(body, &v) == nil {
			if s := strings.TrimSpace(v.Org); strings.HasPrefix(s, "AS") && len(s) < 200 {
				asn = s
			}

			if c := strings.ToUpper(strings.TrimSpace(v.Country)); validCC(c) {
				geo = c
			}
		}
	}

	if asn == "" || geo == "" {
		if body := get("https://ipwho.is/" + ip + "?fields=success,country_code,connection"); len(body) > 0 {
			var v struct {
				Success     bool   `json:"success"`
				CountryCode string `json:"country_code"`
				Connection  struct {
					ASN int    `json:"asn"`
					Org string `json:"org"`
				} `json:"connection"`
			}

			if json.Unmarshal(body, &v) == nil && v.Success {
				if asn == "" && v.Connection.ASN > 0 {
					asn = asnText(strconv.Itoa(v.Connection.ASN), v.Connection.Org)
				}

				if c := strings.ToUpper(strings.TrimSpace(v.CountryCode)); geo == "" && validCC(c) {
					geo = c
				}
			}
		}
	}

	return asn, geo
}
