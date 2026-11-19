package ipdelay

// CUSTOM (CUSTOM.md #68, not part of upstream): one shared HTTP/TLS request path for TryIP and TryTarget.
// The two callers differ only in what they read from the same response; connection setup, TLS tracing, redirects,
// and timing must stay identical.

import (
	"context"
	"errors"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strconv"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

var errBadTargetURL = errors.New("bad url")

type requestSnapshot struct {
	Status  int
	Elapsed time.Duration
	Header  http.Header
	State   tls.ConnectionState
	Ph      Phases
	Body    []byte
}

func requestSnapshotVia(px C.Proxy, rawURL string, timeout time.Duration, bodyLimit int64) (requestSnapshot, error) {
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return requestSnapshot{}, errBadTargetURL
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
		return requestSnapshot{}, errBadTargetURL
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	start := time.Now()
	conn, err := px.DialContext(ctx, &C.Metadata{Host: u.Hostname(), DstPort: uint16(portNum)})
	if err != nil {
		return requestSnapshot{}, err
	}
	defer conn.Close()

	dialed := time.Since(start)
	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives:  true,
		TLSClientConfig:    &tls.Config{InsecureSkipVerify: true, ServerName: u.Hostname()},
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
		return requestSnapshot{}, err
	}

	var clock phaseClock
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), clock.trace()))
	req.Header.Set("User-Agent", "curl/8.5.0")

	resp, err := client.Do(req)
	if err != nil {
		return requestSnapshot{}, err
	}
	defer resp.Body.Close()

	result := requestSnapshot{
		Status:  resp.StatusCode,
		Elapsed: time.Since(start),
		Header:  resp.Header.Clone(),
		State:   clock.connectionState(),
		Ph:      clock.phases(dialed),
	}
	if bodyLimit > 0 {
		result.Body, _ = io.ReadAll(io.LimitReader(resp.Body, bodyLimit))
	}
	return result, nil
}
