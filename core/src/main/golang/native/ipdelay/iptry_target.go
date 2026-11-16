package ipdelay

// CUSTOM (CUSTOM.md #66, not part of upstream): the "target address" of the through-node test. One call is one request
// to the address the user typed (a site they really use), through node `name` with its `server` replaced by the tested
// address, like TryIP. Unlike TryIP it keeps what the site answered (status, redirect, Cloudflare's `Cf-Ray` and
// `Server`), so the page can show the reaction of the site itself. Nothing here runs on its own; one call is one request.

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// TargetResult is the outcome of TryTarget. Err is "" when the site answered at all (whatever the status).
type TargetResult struct {
	Delay    int    // milliseconds from dialing until the response headers came
	Status   int    // the HTTP status
	Location string // the `Location` header of a redirect, "" otherwise
	CfRay    string // `Cf-Ray` of the answer, "" when it has none (the site is not behind Cloudflare)
	Server   string // `Server` of the answer
	// CUSTOM.md #67: what the first part of the page says (SniffPage): SniffRefresh / SniffScript / SniffRegion, "" when nothing
	Soft     string
	SoftNote string // the address, or the words that gave it away
	Err      string // the failure in the core's own words
	Ph       Phases // CUSTOM.md #68: where the delay went (0 = not measured)
}

// TryTarget does `GET rawURL` through node `name` with its server replaced by `server` (an address, or a domain when
// isDomain, tested with a copy under another name like TryIP). Redirects are not followed: the first answer is the
// reaction. TLS is not verified (the system CA store is not available inside the core), nothing secret is sent.
func TryTarget(name, server string, isDomain bool, rawURL string) TargetResult {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return TargetResult{Err: "node not found"}
	}

	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return TargetResult{Err: "bad url"}
	}

	port := u.Port()
	if port == "" {
		if u.Scheme == "https" {
			port = "443"
		} else {
			port = "80"
		}
	}

	portNum, err := strconv.ParseUint(port, 10, 16)
	if err != nil {
		return TargetResult{Err: "bad url"}
	}

	if isDomain {
		cp := make(map[string]any, len(raw))
		for k, v := range raw {
			cp[k] = v
		}

		cp["name"] = name + " (test)"
		raw = cp
	}

	atomic.AddInt32(&selfDialing, 1) // our own sockets are not real traffic
	defer atomic.AddInt32(&selfDialing, -1)

	px := newVariant(raw, server)
	if px == nil {
		return TargetResult{Err: "cannot build the node with this server"}
	}
	defer closeProxy(px)

	ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
	defer cancel()

	start := time.Now()

	conn, err := px.DialContext(ctx, &C.Metadata{Host: u.Hostname(), DstPort: uint16(portNum)})
	if err != nil {
		return TargetResult{Err: err.Error()}
	}
	defer conn.Close()

	dialed := time.Since(start) // CUSTOM.md #68

	tr := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives: true,
		TLSClientConfig:   &tls.Config{InsecureSkipVerify: true, ServerName: u.Hostname()},
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{
		Transport:     tr,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return TargetResult{Err: err.Error()}
	}

	var clock phaseClock // CUSTOM.md #68

	req = req.WithContext(httptrace.WithClientTrace(req.Context(), clock.trace()))
	req.Header.Set("User-Agent", "curl/8.5.0")

	resp, err := client.Do(req)
	if err != nil {
		return TargetResult{Err: err.Error()}
	}
	defer resp.Body.Close()

	delay := int(time.Since(start).Milliseconds())
	if delay <= 0 {
		delay = 1
	}

	res := TargetResult{
		Delay:    delay,
		Status:   resp.StatusCode,
		Location: strings.TrimSpace(resp.Header.Get("Location")),
		CfRay:    strings.TrimSpace(resp.Header.Get("Cf-Ray")),
		Server:   strings.TrimSpace(resp.Header.Get("Server")),
		Ph:       clock.phases(dialed),
	}

	// CUSTOM.md #67: the first part of the page (a failed or cut read just means less text; the delay above is already taken)
	if body, _ := io.ReadAll(io.LimitReader(resp.Body, sniffMax)); len(body) > 0 {
		res.Soft, res.SoftNote = SniffPage(body)
	}

	return res
}
