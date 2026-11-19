package ipdelay

// CUSTOM (CUSTOM.md #68, not part of upstream): where the time of one request through a node went. `requestVia` (used
// by other callers too) is left as it is; TryIP and TryTarget use what is here so the config check page can show the three
// parts next to the total. Nothing here runs on its own and no extra request is made: the times are read from the
// request that was made anyway (net/http/httptrace).

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// Phases are milliseconds, 0 = not measured (e.g. a plain http address has no TLS part):
//   - Dial: `DialContext` of the node, from the start until it returned. For a node whose tunnel is only really opened
//     with the first read or write (the error of an xhttp node comes out of the request, not out of the dial) this is
//     small and the tunnel set-up lands in Shake.
//   - Shake: the TLS handshake with the target inside the tunnel, from its start to its end (it includes whatever
//     the tunnel needs first).
//   - First: from the request being written to the first byte of the answer (the target's own time plus the way back).
type Phases struct {
	Dial, Shake, First int
	TLS, ALPN string
}

// phaseClock holds the moments (unix nanoseconds, 0 = not seen) the transport reports. The callbacks may run in other
// goroutines than the one that reads them, so they are stored and loaded atomically.
type phaseClock struct {
	tlsStart, tlsDone, wrote, first int64
	tlsState tls.ConnectionState // read after the request completes
}

func TLSVersionName(version uint16) string {
	switch version {
	case tls.VersionTLS13:
		return "1.3"
	case tls.VersionTLS12:
		return "1.2"
	case tls.VersionTLS11:
		return "1.1"
	case tls.VersionTLS10:
		return "1.0"
	default:
		return ""
	}
}

func (p *phaseClock) trace() *httptrace.ClientTrace {
	return &httptrace.ClientTrace{
		TLSHandshakeStart: func() { atomic.StoreInt64(&p.tlsStart, time.Now().UnixNano()) },
		TLSHandshakeDone:  func(state tls.ConnectionState, _ error) {
			p.tlsState = state
			atomic.StoreInt64(&p.tlsDone, time.Now().UnixNano())
		},
		WroteRequest:      func(httptrace.WroteRequestInfo) { atomic.StoreInt64(&p.wrote, time.Now().UnixNano()) },
		GotFirstResponseByte: func() {
			atomic.StoreInt64(&p.first, time.Now().UnixNano())
		},
	}
}

// phases is called after the request returned; `dial` is how long the node's DialContext took.
func (p *phaseClock) phases(dial time.Duration) Phases {
	ph := Phases{Dial: phaseMs(dial.Nanoseconds())}

	if s, d := atomic.LoadInt64(&p.tlsStart), atomic.LoadInt64(&p.tlsDone); s > 0 && d >= s {
		ph.Shake = phaseMs(d - s)
	}

	if w, f := atomic.LoadInt64(&p.wrote), atomic.LoadInt64(&p.first); w > 0 && f >= w {
		ph.First = phaseMs(f - w)
	}

	return ph
}

// phaseMs rounds nanoseconds to milliseconds, at least 1 (a part that was measured is never shown as "not measured").
func phaseMs(ns int64) int {
	v := int((ns + 500000) / 1000000)
	if v < 1 {
		v = 1
	}

	return v
}

// requestPhased is `requestVia` without the body and with the three parts: GET rawURL through px, the redirects not
// followed. The elapsed time is from the start of the dial to the response headers, like `requestVia`.
func requestPhased(px C.Proxy, rawURL string, timeout time.Duration) (int, time.Duration, Phases, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return 0, 0, Phases{}, err
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
		return 0, 0, Phases{}, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	start := time.Now()

	conn, err := px.DialContext(ctx, &C.Metadata{
		Host:    u.Hostname(),
		DstPort: uint16(portNum),
	})
	if err != nil {
		return 0, 0, Phases{}, err
	}
	defer conn.Close()

	dialed := time.Since(start)

	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives: true,
		// Latency probe only: nothing secret is sent, and the system CA store is not available inside the core on Android.
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: u.Hostname()},
		ForceAttemptHTTP2: true,
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return 0, 0, Phases{}, err
	}

	var clock phaseClock

	req = req.WithContext(httptrace.WithClientTrace(req.Context(), clock.trace()))
	req.Header.Set("User-Agent", "curl/8.5.0")

	resp, err := client.Do(req)
	if err != nil {
		return 0, 0, Phases{}, err
	}
	defer resp.Body.Close()

	elapsed := time.Since(start)
	ph := clock.phases(dialed)
	state := clock.tlsState
	ph.TLS = TLSVersionName(state.Version)
	ph.ALPN = state.NegotiatedProtocol

	return resp.StatusCode, elapsed, ph, nil
}
