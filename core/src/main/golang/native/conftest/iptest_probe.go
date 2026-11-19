// CUSTOM (CUSTOM.md #52, not part of upstream): per-IP work used by the config check page. The app supplies only IP
// addresses; domain names are invalid entries. The dialog's direct/node choice selects the route, while the core modes
// remain "probe" and "nodeone". The app waits 1 to 3 seconds between addresses.
//
// "probe" (direct): ten TLS attempts, one HEAD on the kept connection, then configured port checks.
// "nodeone" (through a node): ten attempts through the selected node; an optional target URL is requested in those
// attempts. "download" measures one selected row; "colo" reads Cloudflare's data-centre list. "asn" is a separate,
// explicit lookup reached only when the user taps the ASN cell.
//
// Power: nothing runs on its own; one call is one IP, CancelIPTest stops the running probe.
import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
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
	probeTries    = 10
	probeParallel = 5
	probeTimeout  = 3 * time.Second
	portTimeout   = 3 * time.Second
	maxPorts      = 12

	defaultPorts       = "443,2053,2083,2087,2096,8443"
	defaultDownloadURL = "https://speed.cloudflare.com/__down?bytes=10485760"
	downloadMaxBytes   = 10 << 20
	downloadMaxTime    = 5 * time.Second
	downloadSkip       = 1 * time.Second

	coloListURL = "https://speed.cloudflare.com/locations"
	coloTimeout = 6 * time.Second
	// CUSTOM.md #62: a browser's User-Agent for the request of the list
	coloUserAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

	echCacheTTL = 2 * time.Minute
)

// PortRes is the result of the handshake to one port of an address.
type PortRes struct {
	Port   int    `json:"port"`
	OK     bool   `json:"ok"`
	Ms     int    `json:"ms"`
	Code   string `json:"code"`   // errlog code, "" when OK
	Reason string `json:"reason"` // the failure in the core's words
}

// stats of the tries that worked (ms) out of `sent`.
// CUSTOM.md #68: Jitter is the average distance of the tries that worked from Avg.
type tryStats struct{ Sent, OK, Loss, Min, Max, Avg, Jitter int }

func summarize(ms []int, sent int) tryStats {
	st := tryStats{Sent: sent, OK: len(ms)}

	if sent > 0 {
		st.Loss = (sent - len(ms)) * 100 / sent
	}

	if len(ms) == 0 {
		return st
	}

	sum := 0
	st.Min, st.Max = ms[0], ms[0]

	for _, v := range ms {
		sum += v

		if v < st.Min {
			st.Min = v
		}

		if v > st.Max {
			st.Max = v
		}
	}

	st.Avg = (sum + len(ms)/2) / len(ms)

	// CUSTOM.md #68: how far the tries are from the average, on average (one slow try among fast ones shows, but does
	// not dominate the way it does in Max)
	dev := 0

	for _, v := range ms {
		if v >= st.Avg {
			dev += v - st.Avg
		} else {
			dev += st.Avg - v
		}
	}

	st.Jitter = (dev + len(ms)/2) / len(ms)

	return st
}

// parsePorts reads the ports typed (separated like the addresses); wrong or repeated ones are dropped, at most
// maxPorts are kept, and an empty result gives the default list.
func parsePorts(text string) []int {
	fields := strings.FieldsFunc(text, func(r rune) bool {
		switch r {
		case '\n', '\r', '\t', ' ', ',', ';', '，', '；', '、', '　':
			return true
		}

		return false
	})

	seen := map[int]bool{}

	var out []int

	for _, f := range fields {
		n, err := strconv.Atoi(f)
		if err != nil || n < 1 || n > 65535 || seen[n] {
			continue
		}

		seen[n] = true

		out = append(out, n)

		if len(out) == maxPorts {
			break
		}
	}

	if len(out) == 0 && strings.TrimSpace(text) != defaultPorts {
		return parsePorts(defaultPorts)
	}

	return out
}

// parseColoData reads city and country code from one decode of Cloudflare's locations list.
func parseColoData(body []byte) (map[string]string, map[string]string) {
	var list []struct {
		IATA string `json:"iata"`
		City string `json:"city"`
		CCA2 string `json:"cca2"`
	}

	colos, countries := map[string]string{}, map[string]string{}
	if json.Unmarshal(body, &list) != nil {
		return colos, countries
	}
	for _, e := range list {
		code := strings.ToUpper(strings.TrimSpace(e.IATA))
		city := strings.TrimSpace(e.City)
		cc := strings.ToUpper(strings.TrimSpace(e.CCA2))
		if code != "" && city != "" {
			colos[code] = city
		}
		if code != "" && validCC(cc) {
			countries[code] = cc
		}
	}
	return colos, countries
}

func parseColos(body []byte) map[string]string {
	colos, _ := parseColoData(body)
	return colos
}

func parseColoCountries(body []byte) map[string]string {
	_, countries := parseColoData(body)
	return countries
}

// beginRun uses a generation token so an older run cannot clear a newer run's cancellation handle.
func beginRun() (context.Context, func()) {
	ctx, cancel := context.WithCancel(context.Background())

	ipRunLock.Lock()
	if ipRunCancel != nil {
		ipRunCancel()
	}
	ipRunID++
	runID := ipRunID
	ipRunCancel = cancel
	ipRunLock.Unlock()

	return ctx, func() {
		cancel()
		ipRunLock.Lock()
		if ipRunID == runID {
			ipRunCancel = nil
		}
		ipRunLock.Unlock()
	}
}

// runOne answers the per-address modes.
func runOne(req IPRequest, rep IPReport) IPReport {
	if req.Mode == "parse" { // reads the list only: nothing to cancel, and a run in progress is left alone
		parseList(req, &rep)

		return rep
	}

	ctx, done := beginRun()
	defer done()

	switch req.Mode {
	case "colo":
		colos, countries, err := fetchColos(ctx)
		if err != nil {
			rep.Error = "colo: " + err.Error()
		} else {
			rep.Colos = colos
			rep.ColoCountries = countries
		}
	case "probe":
		probeDirect(ctx, &rep, req)
	case "nodeone":
		probeNode(ctx, &rep, req)
	case "download":
		downloadOne(ctx, &rep, req)
	}

	rep.Cancelled = ctx.Err() != nil
	rep.At = time.Now().UnixMilli()

	return rep
}

// parseList (mode "parse") reads the typed IP list: accepted addresses, invalid words and the maxIPTest limit.
func parseList(req IPRequest, rep *IPReport) {
	list, invalid := parseTargets(req.IPs)

	rep.Invalid = append(rep.Invalid, invalid...)

	if len(list) > maxIPTest {
		rep.Cut = len(list) - maxIPTest
		list = list[:maxIPTest]
	}

	if len(list) == 0 {
		rep.Error = "no address"

		return
	}

	for _, t := range list {
		rep.Rows = append(rep.Rows, IPRow{IP: t.Text})
	}
}

// firstTarget is the first valid IP address in the typed text.
func firstTarget(text string) (target, bool) {
	list, _ := parseTargets(text)
	if len(list) == 0 {
		return target{}, false
	}
	return list[0], true
}

var coloCache struct {
	sync.Mutex
	colos, countries map[string]string
}

func cloneStringMap(src map[string]string) map[string]string {
	out := make(map[string]string, len(src))
	for key, value := range src {
		out[key] = value
	}
	return out
}

func fetchColos(ctx context.Context) (map[string]string, map[string]string, error) {
	coloCache.Lock()
	if len(coloCache.colos) > 0 {
		colos, countries := cloneStringMap(coloCache.colos), cloneStringMap(coloCache.countries)
		coloCache.Unlock()
		return colos, countries, nil
	}
	coloCache.Unlock()

	ctx, cancel := context.WithTimeout(ctx, coloTimeout)
	defer cancel()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, addr) // the socket does not go through the VPN
			},
			TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
			ForceAttemptHTTP2: true,
		},
	}
	defer client.CloseIdleConnections()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, coloListURL, nil)
	if err != nil {
		return nil, nil, err
	}

	// CUSTOM.md #62: the list is the data of Cloudflare's own speed test page, which answered a bare request (Go's
	// default User-Agent, no Origin) with HTTP 403. The headers of a page of that site are sent now.
	req.Header.Set("User-Agent", coloUserAgent)
	req.Header.Set("Accept", "application/json, text/plain, */*")
	req.Header.Set("Origin", "https://speed.cloudflare.com")
	req.Header.Set("Referer", "https://speed.cloudflare.com/")

	resp, err := client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		// what the answer says about who refused: the log shows it, so a block can be told from a network problem
		extra := ""

		if v := resp.Header.Get("Cf-Mitigated"); v != "" {
			extra += ", cf-mitigated: " + v
		}

		if v := resp.Header.Get("Server"); v != "" {
			extra += ", server: " + v
		}

		if extra != "" {
			extra = " (" + strings.TrimPrefix(extra, ", ") + ")"
		}

		return nil, nil, fmt.Errorf("HTTP %d%s", resp.StatusCode, extra)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, nil, err
	}

	colos, countries := parseColoData(body)
	if len(colos) == 0 {
		return nil, nil, errors.New("no entries")
	}

	coloCache.Lock()
	if len(coloCache.colos) == 0 {
		coloCache.colos = cloneStringMap(colos)
		coloCache.countries = cloneStringMap(countries)
	}
	colos, countries = cloneStringMap(coloCache.colos), cloneStringMap(coloCache.countries)
	coloCache.Unlock()
	return colos, countries, nil
}

// the ECH config of the domain, kept for a short time so that the addresses of one round ask for it once
var echCache struct {
	sync.Mutex
	domain string
	cfg    []byte
	source string
	at     time.Time
}

func echFor(ctx context.Context, domain string) ([]byte, string) {
	echCache.Lock()
	if echCache.domain == domain && time.Since(echCache.at) < echCacheTTL {
		cfg, src := echCache.cfg, echCache.source
		echCache.Unlock()

		return cfg, src
	}
	echCache.Unlock()

	fctx, cancel := context.WithTimeout(ctx, echFetchWait)
	cfg, src, err := fetch(fctx, domain, ipdelay.DoHServers())
	cancel()

	if err != nil {
		cfg, src = nil, ""
	}

	if ctx.Err() == nil { // a cancelled fetch says nothing about the domain
		echCache.Lock()
		echCache.domain, echCache.cfg, echCache.source, echCache.at = domain, cfg, src, time.Now()
		echCache.Unlock()
	}

	return cfg, src
}

// handshakeECH is one handshake to addr within `limit`; with an ECH config the first try offers ECH and, when the
// server refuses it, a plain one follows. `ech` is the state like IPRow.ECH ("" when it failed for another reason).
func handshakeECH(ctx context.Context, addr, domain string, echList []byte, limit time.Duration) (*tls.Conn, int, string, error) {
	conn, ms, _, ech, err := handshakeECHSplit(ctx, addr, domain, echList, limit)

	return conn, ms, ech, err
}

// isTLSVersionError detects a TLS protocol-version negotiation failure, which can occur when ECH forces TLS 1.3
// against an endpoint that only supports TLS 1.2.
func isTLSVersionError(err error) bool {
	if err == nil {
		return false
	}
	message := strings.ToLower(err.Error())
	return strings.Contains(message, "protocol version") || strings.Contains(message, "unsupported protocol")
}

// handshakeECHSplit (CUSTOM.md #68) is handshakeECH that also gives the milliseconds of the TCP connect inside `ms`
// (of the handshake that counted: the plain one after a rejection).
func handshakeECHSplit(ctx context.Context, addr, domain string, echList []byte, limit time.Duration) (*tls.Conn, int, int, string, error) {
	ech := "none"

	conn, ms, tcp, err := handshakeSplit(ctx, addr, domain, echList, limit)

	if len(echList) > 0 {
		var rej *tls.ECHRejectionError

		switch {
		case err == nil && conn.ConnectionState().ECHAccepted:
			ech = "accepted"
		case err == nil:
			ech = "notaccepted"
		case errors.As(err, &rej):
			ech = "rejected"
			conn, ms, tcp, err = handshakeSplit(ctx, addr, domain, nil, limit)
		case isTLSVersionError(err):
			ech = ""
			conn, ms, tcp, err = handshakeSplit(ctx, addr, domain, nil, limit)
		default:
			ech = ""
		}
	}

	return conn, ms, tcp, ech, err
}

// forEachTry runs `n` calls of fn, up to `workers` at a time.
func forEachTry(ctx context.Context, n, workers int, fn func(i int)) {
	jobs := make(chan int)

	var wg sync.WaitGroup

	for w := 0; w < workers; w++ {
		wg.Add(1)

		go func() {
			defer wg.Done()

			for i := range jobs {
				if ctx.Err() == nil {
					fn(i)
				}
			}
		}()
	}

	for i := 0; i < n; i++ {
		jobs <- i
	}

	close(jobs)
	wg.Wait()
}

func setStats(row *IPRow, st tryStats) {
	row.Sent, row.Loss, row.Min, row.Max, row.Avg = st.Sent, st.Loss, st.Min, st.Max, st.Avg
	row.Ms = st.Avg
	row.Jitter = st.Jitter // CUSTOM.md #68
}

func probeDirect(ctx context.Context, rep *IPReport, req IPRequest) {
	if rep.Domain == "" {
		rep.Error = "no domain"

		return
	}

	t, ok := firstTarget(req.IPs)
	if !ok {
		rep.Error = "no address"

		return
	}

	echList, source := echFor(ctx, rep.Domain)
	rep.ECHSource = source

	ip := netip.MustParseAddr(t.Text)
	row := IPRow{IP: ip.String(), ECH: "none"}
	addr := net.JoinHostPort(row.IP, ipPort)

	var (
		mu      sync.Mutex
		keep    *tls.Conn
		keepECH string
		okMs    []int
		tcpMs   []int // CUSTOM.md #68: of the tries that worked, the TCP connect and the TLS handshake (the rest of ms)
		tlsMs   []int
		lastErr error
	)

	forEachTry(ctx, probeTries, probeParallel, func(int) {
		conn, ms, tcp, ech, err := handshakeECHSplit(ctx, addr, rep.Domain, echList, probeTimeout)

		mu.Lock()
		defer mu.Unlock()

		if err != nil {
			lastErr = err

			return
		}

		okMs = append(okMs, ms)

		if ms >= tcp { // CUSTOM.md #68
			tcpMs, tlsMs = append(tcpMs, tcp), append(tlsMs, ms-tcp)
		}

		if keep == nil {
			keep, keepECH = conn, ech
		} else {
			_ = conn.Close()
		}
	})

	if ctx.Err() != nil {
		if keep != nil {
			_ = keep.Close()
		}

		row.Sent = probeTries
		rep.Rows = []IPRow{row}

		return
	}

	setStats(&row, summarize(okMs, probeTries))

	if keep == nil {
		if lastErr != nil {
			row.Reason = lastErr.Error()
			row.Code = errlog.Classify(row.Reason)
		}

		rep.Rows = []IPRow{row}

		return
	}

	row.OK = true
	row.ECH = keepECH

	state := keep.ConnectionState()
	row.TLS = ipdelay.TLSVersionName(state.Version)
	row.ALPN = state.NegotiatedProtocol
	row.Colo, row.CF, row.First = fetchEvidenceTimed(ctx, keep, rep.Domain) // closes the connection; CUSTOM.md #68: one HEAD, one sample
	row.Dial, row.Shake = meanOf(tcpMs), meanOf(tlsMs)
	row.InHosts = configHosts()[row.IP]

	row.Ports = probePorts(ctx, row.IP, rep.Domain, parsePorts(req.Ports), row)

	// CUSTOM.md #62: the exit country (落地) is seen from outside, so it needs a request through a node: the node of
	// the config whose server is the typed domain, with this address as its server. Without such a node it stays empty.
	if ctx.Err() == nil {
		if node := nodeOfDomain(rep.Domain); node != "" {
			rep.LandNode = node
			row.Country = ipdelay.ExitCountry(node, row.IP, false)
		}
	}

	rep.Rows = []IPRow{row}
}

// nodeOfDomain matches the configured node's actual server. A matching SNI alone does not identify the entry route.
func nodeOfDomain(domain string) string {
	domain = strings.TrimSpace(domain)
	if domain == "" {
		return ""
	}

	raws := ipdelay.RawProxies()
	names := make([]string, 0, len(raws))

	for name := range raws {
		names = append(names, name)
	}

	sort.Strings(names)

	for _, name := range names {
		server, _ := raws[name]["server"].(string)
		server = strings.TrimSuffix(strings.TrimSpace(server), ".")
		if server != "" && strings.EqualFold(server, strings.TrimSuffix(domain, ".")) {
			return name
		}
	}

	return ""
}

// probePorts tries a handshake on every port of the list (SNI = domain, no ECH), in parallel. The port the ten tries
// used is not tried again: its result is theirs.
func probePorts(ctx context.Context, ip, domain string, ports []int, main IPRow) []PortRes {
	out := make([]PortRes, len(ports))

	var wg sync.WaitGroup

	for i, p := range ports {
		out[i].Port = p

		if strconv.Itoa(p) == ipPort {
			out[i].OK, out[i].Ms = main.OK, main.Avg

			continue
		}

		wg.Add(1)

		go func(i, p int) {
			defer wg.Done()

			conn, ms, err := handshakeWithin(ctx, net.JoinHostPort(ip, strconv.Itoa(p)), domain, nil, portTimeout)
			out[i].Ms = ms

			if err != nil {
				out[i].Reason = err.Error()
				out[i].Code = errlog.Classify(out[i].Reason)

				return
			}

			_ = conn.Close()

			out[i].OK = true
		}(i, p)
	}

	wg.Wait()

	return out
}

func probeNode(ctx context.Context, rep *IPReport, req IPRequest) {
	if rep.Node == "" {
		rep.Error = "no node"

		return
	}

	t, ok := firstTarget(req.IPs)
	if !ok {
		rep.Error = "no address"

		return
	}

	hosts := map[string]bool{}
	for _, ip := range ipdelay.NodeAddresses(rep.Node) {
		hosts[ip] = true
	}

	// CUSTOM.md #66: the reference is measured against the node's own test URL, so it is no comparison for a target address
	if req.Target == "" {
		for _, r := range ipdelay.Get(rep.Node) {
			if r.Delay > 0 && (rep.Ref == nil || r.Delay < rep.Ref.Ms) {
				rep.Ref = &IPRef{IP: r.IP, Ms: r.Delay, At: r.At}
			}
		}
	}

	var (
		mu       sync.Mutex
		okMs     []int
		lastErr  string
		statuses = map[int]int{} // CUSTOM.md #66: status -> how many tries got it
		location string
		ray      string
		soft     string // CUSTOM.md #67
		softNote string
		detail   *ipdelay.TargetResult
		tlsName  string
		alpn     string
		dials    []int // CUSTOM.md #68: the parts of the tries that worked (the ones that did not measure a part are left out of it)
		shakes   []int
		firsts   []int
	)

	forEachTry(ctx, probeTries, probeParallel, func(int) {
		if req.Target != "" { // CUSTOM.md #66: the tries go to the target address; any answer counts as reached
			res := ipdelay.TryTarget(rep.Node, t.Text, false, req.Target)

			mu.Lock()
			defer mu.Unlock()

			if res.Err != "" {
				lastErr = res.Err

				return
			}

			okMs = append(okMs, res.Delay)
			dials, shakes, firsts = addPhases(dials, shakes, firsts, res.Ph.Dial, res.Ph.Shake, res.Ph.First)
			statuses[res.Status]++
			if detail == nil {
				copy := res
				detail = &copy
			}

			if location == "" {
				location = res.Location
			}

			if ray == "" {
				ray = res.CfRay
			}

			if soft == "" {
				soft, softNote = res.Soft, res.SoftNote
			}

			return
		}

		res := ipdelay.TryIP(rep.Node, t.Text, false)

		mu.Lock()
		defer mu.Unlock()

		if res.Err != "" {
			lastErr = res.Err

			return
		}

		okMs = append(okMs, res.Delay)
		dials, shakes, firsts = addPhases(dials, shakes, firsts, res.Ph.Dial, res.Ph.Shake, res.Ph.First) // CUSTOM.md #68
		if tlsName == "" {
			tlsName, alpn = res.Ph.TLS, res.Ph.ALPN
		}
	})

	row := IPRow{IP: t.Text, InHosts: hosts[t.Text]}

	if raw := ipdelay.RawProxies()[rep.Node]; raw != nil {
		if p := raw["port"]; p != nil {
			row.Port = fmt.Sprint(p)
		}
	}

	if ctx.Err() != nil {
		row.Sent = probeTries
		rep.Rows = []IPRow{row}

		return
	}

	st := summarize(okMs, probeTries)
	setStats(&row, st)

	if st.OK > 0 {
		row.OK = true
		row.Dial, row.Shake, row.First = meanOf(dials), meanOf(shakes), meanOf(firsts) // CUSTOM.md #68
		if req.Target == "" {
			row.TLS, row.ALPN = tlsName, alpn
		}

		if req.Target != "" { // CUSTOM.md #66
			best := 0

			for code, n := range statuses {
				if n > statuses[best] || (n == statuses[best] && code < best) {
					best = code
				}
			}

			row.TargetStatus = best
			row.TargetNote = location
			row.TargetColo = coloOf(ray)
			row.TargetSoft, row.TargetSoftNote = soft, softNote
			if detail != nil {
				row.TargetTLS = detail.TLS
				row.TargetALPN = detail.ALPN
				row.TLS, row.ALPN = detail.TLS, detail.ALPN
				row.TargetCertSubject = detail.CertSubject
				row.TargetCertIssuer = detail.CertIssuer
				row.TargetCertNotBefore = detail.CertNotBefore
				row.TargetCertNotAfter = detail.CertNotAfter
				row.TargetCertDNSNames = detail.CertDNSNames
				row.TargetCertIPAddresses = detail.CertIPAddresses
				row.TargetCertVerification = detail.CertVerification
				row.TargetServer = detail.Server
				row.TargetCfRay = detail.CfRay
				row.TargetContentType = detail.ContentType
				row.TargetRetryAfter = detail.RetryAfter
				row.TargetCfMitigated = detail.CfMitigated
			}

			// the exit country is asked through the node as before (the target address is not asked for it)
			row.Country = ipdelay.ExitCountry(rep.Node, t.Text, false)
		} else if ctx.Err() == nil {
			// CUSTOM.md #72: once per address, now (it was asked on every try, and the proxy page's memory was used first)
			row.Country = ipdelay.ExitCountry(rep.Node, t.Text, false)
		}

		if rep.Ref != nil && st.Avg < rep.Ref.Ms {
			row.Faster = rep.Ref.Ms - st.Avg
		}

		// CUSTOM.md #63: the data centre, the operator and the registered country of an address that works
		nodeOwner(ctx, &row, rep.Node)
	} else {
		row.Reason = lastErr
		row.Code = errlog.Classify(lastErr)
	}

	rep.Rows = []IPRow{row}
}

// nodeSNI (CUSTOM.md #63) is the node's own domain: its `servername`, else `sni`, else `server`, the first of them that is
// a name and not an address; "" when it has none.
func nodeSNI(node string) string {
	raw := ipdelay.RawProxies()[node]

	for _, key := range []string{"servername", "sni", "server"} {
		v, _ := raw[key].(string)
		v = strings.TrimSpace(v)

		if v == "" {
			continue
		}

		if _, err := netip.ParseAddr(strings.Trim(v, "[]")); err == nil {
			continue
		}

		return v
	}

	return ""
}

// nodeOwner reads the entry data centre of an address tested through a node. The address is connected once more
// directly, with the node's own domain as SNI, and its `Cf-Ray` is read. This does not query IP registration ownership;
// that is done only by the explicit lookup action.
func nodeOwner(ctx context.Context, row *IPRow, node string) {
	if ctx.Err() != nil {
		return
	}

	if sni := nodeSNI(node); sni == "" {
		row.ColoErr = "nosni"
	} else {
		port := ipPort

		if n, err := strconv.Atoi(row.Port); err == nil && n > 0 && n < 65536 {
			port = row.Port
		}

		conn, _, _, err := handshakeECH(ctx, net.JoinHostPort(row.IP, port), sni, nil, probeTimeout)
		if err != nil {
			row.ColoErr = err.Error()
		} else {
			row.Colo, row.CF = fetchEvidence(ctx, conn, sni) // closes the connection

			if row.Colo == "" {
				row.ColoErr = "no Cf-Ray"
			}
		}
	}
}

func downloadOne(ctx context.Context, rep *IPReport, req IPRequest) {
	t, ok := firstTarget(req.IPs)
	if !ok {
		rep.Error = "no address"

		return
	}

	raw := strings.TrimSpace(req.URL)
	if raw == "" {
		raw = defaultDownloadURL
	}

	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		rep.Error = "bad url"

		return
	}

	row := IPRow{IP: t.Text}

	var res ipdelay.DownloadResult

	if rep.Node != "" {
		res = ipdelay.DownloadVia(ctx, rep.Node, t.Text, false, raw, downloadMaxBytes, downloadMaxTime, downloadSkip)
	} else {
		port := u.Port()
		if port == "" {
			if u.Scheme == "https" {
				port = "443"
			} else {
				port = "80"
			}
		}

		dctx, cancel := context.WithTimeout(ctx, probeTimeout)
		conn, err := dialer.DialContext(dctx, "tcp", net.JoinHostPort(t.Text, port)) // not through the VPN
		cancel()

		if err != nil {
			res = ipdelay.DownloadResult{Err: err.Error()}
		} else {
			res = ipdelay.MeasureDownload(ctx, conn, raw, downloadMaxBytes, downloadMaxTime, downloadSkip)
		}
	}

	row.SpeedBytes, row.SpeedMs = res.Bytes, res.Ms

	if res.Err != "" {
		row.SpeedErr = res.Err
	} else {
		row.Speed = res.MBps
	}

	rep.Rows = []IPRow{row}
}
