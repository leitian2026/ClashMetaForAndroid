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
	"net"
	"net/http"
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

	httpTimeout  = 4 * time.Second
	asnTimeout   = 4 * time.Second
	echFetchWait = 8 * time.Second
)

// IPRequest is what the app sends (as the filter of the query, with "kind":"ip").
type IPRequest struct {
	Kind   string `json:"kind"`
	Mode   string `json:"mode"`   // "nodes", "config", "asn", "parse", "probe", "nodeone", "download" or "colo"
	Domain string `json:"domain"` // direct: SNI and Host
	Node   string `json:"node"`   // node: the node to copy
	IPs    string `json:"ips"`    // the text as typed
	// CUSTOM.md #52: the per-address modes "probe", "nodeone", "download", "colo" (iptest_probe.go)
	Ports string `json:"ports"` // probe: the ports to try, as typed ("" = the default list)
	URL   string `json:"url"`   // download: the address to download ("" = the default)
	// CUSTOM.md #66: nodeone: the address of a site the user really uses; the tries go to it through the node ("" = the node's own test URL)
	Target string `json:"target"`
}

// IPRow is the result for one address.
type IPRow struct {
	IP       string `json:"ip"` // the address; IsDomain is retained for saved-result schema compatibility
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
	Invalid   []string `json:"invalid"`   // words that were not IP addresses
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
	// CUSTOM.md #69: mode "config": the addresses the profile uses for its nodes and the servers that gave none.
	Config     []ConfigIP   `json:"config"`
	ConfigFail []ConfigFail `json:"configFail"`
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
	rep := IPReport{Mode: req.Mode, Domain: req.Domain, Node: req.Node, Rows: []IPRow{}, Invalid: []string{}, Nodes: []string{}, Config: []ConfigIP{}, ConfigFail: []ConfigFail{}}

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

	rep.Error = "unknown mode"
	return rep
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
