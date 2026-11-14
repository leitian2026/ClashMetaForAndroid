package conftest

// CUSTOM (CUSTOM.md #52, not upstream): "test other IPs", one address per call. The app loops over the typed list
// (waiting the random 1 to 3 seconds between two itself), so it can draw the table and write a log line after each
// address. Modes, all of RunIP:
//
//   - "probe" (direct, one address): ten tries (up to five at once, each at most 3 seconds) -> loss, min, max, average;
//     on the first one that works, the TLS version, ALPN, ECH, `Cf-Ray` data centre and "is Cloudflare"; then, for an
//     address that works, the ports of the list (handshake only, in parallel, 3 seconds each) and the operator (AS)
//     from the public services (from memory when asked before);
//   - "nodeone" (through a node, one address or domain): the same ten tries with ipdelay.TryIP;
//   - "download" (one address, only when the user taps "测速" on its row): at most 10 MB or 5 seconds, the first
//     second left out, straight to the address (SNI and Host from the URL) or, with a node, through it;
//   - "colo": the data centre list of Cloudflare, code -> city, fetched online (the app keeps it in memory).
//
// Power: nothing runs on its own, one call is one address, CancelIPTest stops the one running.

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
type tryStats struct{ Sent, OK, Loss, Min, Max, Avg int }

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

// parseColos reads Cloudflare's list ([{"iata":"HKG","city":"Hong Kong","cca2":"HK",...},...]) into code -> city.
func parseColos(body []byte) map[string]string {
	var list []struct {
		IATA string `json:"iata"`
		City string `json:"city"`
	}

	out := map[string]string{}

	if json.Unmarshal(body, &list) != nil {
		return out
	}

	for _, e := range list {
		code, city := strings.ToUpper(strings.TrimSpace(e.IATA)), strings.TrimSpace(e.City)
		if code != "" && city != "" {
			out[code] = city
		}
	}

	return out
}

// beginRun registers the cancel of a run (an earlier one still going is stopped); done() unregisters it.
func beginRun() (context.Context, func()) {
	ctx, cancel := context.WithCancel(context.Background())

	ipRunLock.Lock()
	if ipRunCancel != nil {
		ipRunCancel()
	}

	ipRunCancel = cancel
	ipRunLock.Unlock()

	return ctx, func() {
		cancel()

		ipRunLock.Lock()
		ipRunCancel = nil
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
		colos, err := fetchColos(ctx)
		if err != nil {
			rep.Error = "colo: " + err.Error()
		} else {
			rep.Colos = colos
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

// parseList (mode "parse") reads the typed text: the entries to test come back as rows (IP and IsDomain), the same
// way RunIP reads them (the domains of a direct run in Skipped, more than maxIPTest cut, words not understood).
func parseList(req IPRequest, rep *IPReport) {
	list, invalid := parseTargets(req.IPs)

	rep.Invalid = append(rep.Invalid, invalid...)

	if req.Direct {
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

			return
		}

		list = addrs
	}

	if len(list) > maxIPTest {
		rep.Cut = len(list) - maxIPTest
		list = list[:maxIPTest]
	}

	if len(list) == 0 {
		rep.Error = "no address"

		return
	}

	for _, t := range list {
		rep.Rows = append(rep.Rows, IPRow{IP: t.Text, IsDomain: t.IsDomain})
	}
}

// firstTarget is the first entry of the typed text (an address; a domain only when `domains`).
func firstTarget(text string, domains bool) (target, bool) {
	list, _ := parseTargets(text)

	for _, t := range list {
		if !t.IsDomain || domains {
			return t, true
		}
	}

	return target{}, false
}

func fetchColos(ctx context.Context) (map[string]string, error) {
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
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}

	colos := parseColos(body)
	if len(colos) == 0 {
		return nil, errors.New("no entries")
	}

	return colos, nil
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
	ech := "none"

	conn, ms, err := handshakeWithin(ctx, addr, domain, echList, limit)

	if len(echList) > 0 {
		var rej *tls.ECHRejectionError

		switch {
		case err == nil && conn.ConnectionState().ECHAccepted:
			ech = "accepted"
		case err == nil:
			ech = "notaccepted"
		case errors.As(err, &rej):
			ech = "rejected"

			conn, ms, err = handshakeWithin(ctx, addr, domain, nil, limit)
		default:
			ech = ""
		}
	}

	return conn, ms, ech, err
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
}

func probeDirect(ctx context.Context, rep *IPReport, req IPRequest) {
	if rep.Domain == "" {
		rep.Error = "no domain"

		return
	}

	t, ok := firstTarget(req.IPs, false)
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
		lastErr error
	)

	forEachTry(ctx, probeTries, probeParallel, func(int) {
		conn, ms, ech, err := handshakeECH(ctx, addr, rep.Domain, echList, probeTimeout)

		mu.Lock()
		defer mu.Unlock()

		if err != nil {
			lastErr = err

			return
		}

		okMs = append(okMs, ms)

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
	row.TLS = tlsVersion(state.Version)
	row.ALPN = state.NegotiatedProtocol
	row.Colo, row.CF = fetchEvidence(ctx, keep, rep.Domain) // closes the connection
	row.InHosts = configHosts()[row.IP]

	row.Ports = probePorts(ctx, row.IP, rep.Domain, parsePorts(req.Ports), row)

	// the operator, from the public services (a failure, or a limit they set, only leaves it empty)
	actx, cancel := context.WithTimeout(ctx, 2*asnTimeout+time.Second)
	row.ASN = lookupASN(actx, row.IP)
	cancel()

	rep.Rows = []IPRow{row}
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

	t, ok := firstTarget(req.IPs, true)
	if !ok {
		rep.Error = "no address"

		return
	}

	hosts := map[string]bool{}
	for _, ip := range ipdelay.NodeAddresses(rep.Node) {
		hosts[ip] = true
	}

	for _, r := range ipdelay.Get(rep.Node) {
		if r.Delay > 0 && (rep.Ref == nil || r.Delay < rep.Ref.Ms) {
			rep.Ref = &IPRef{IP: r.IP, Ms: r.Delay, At: r.At}
		}
	}

	var (
		mu      sync.Mutex
		okMs    []int
		country string
		lastErr string
	)

	forEachTry(ctx, probeTries, probeParallel, func(int) {
		res := ipdelay.TryIP(rep.Node, t.Text, t.IsDomain)

		mu.Lock()
		defer mu.Unlock()

		if res.Err != "" {
			lastErr = res.Err

			return
		}

		okMs = append(okMs, res.Delay)

		if country == "" {
			country = res.Country
		}
	})

	row := IPRow{IP: t.Text, IsDomain: t.IsDomain, InHosts: !t.IsDomain && hosts[t.Text], Country: country}

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

		if rep.Ref != nil && st.Avg < rep.Ref.Ms {
			row.Faster = rep.Ref.Ms - st.Avg
		}
	} else {
		row.Reason = lastErr
		row.Code = errlog.Classify(lastErr)
	}

	rep.Rows = []IPRow{row}
}

func downloadOne(ctx context.Context, rep *IPReport, req IPRequest) {
	t, ok := firstTarget(req.IPs, rep.Node != "")
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

	row := IPRow{IP: t.Text, IsDomain: t.IsDomain}

	var res ipdelay.DownloadResult

	if rep.Node != "" {
		res = ipdelay.DownloadVia(ctx, rep.Node, t.Text, t.IsDomain, raw, downloadMaxBytes, downloadMaxTime, downloadSkip)
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
