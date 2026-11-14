package ipdelay

// CUSTOM (CUSTOM.md #52, not part of upstream): the download test of "test other IPs". One call is one download, made
// only when the user taps "测速" on a row; nothing here runs on its own.
//
// MeasureDownload reads a URL over a connection that is already made (the caller decides where it goes: straight to
// the tested address, or through a node), stops at maxBytes or maxDur, whichever comes first, and leaves out the first
// `skip` (TCP / TLS set-up and slow start) when it works out the speed.

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync/atomic"
	"time"

	C "github.com/metacubex/mihomo/constant"
)

// DownloadResult is the outcome of a download test. Err is "" when some bytes came.
type DownloadResult struct {
	Bytes int64
	Ms    int     // from the start of the request to the end
	MBps  float64 // megabytes (10^6 bytes) per second after the first `skip`
	Err   string
}

// speedOf works out the sustained speed: the bytes after `skip` over the time after `skip`; when the whole download
// ended before that (or there is nothing to measure after it), the bytes over the whole time.
func speedOf(total, atSkip int64, elapsed, tSkip time.Duration, skipped bool) float64 {
	if elapsed <= 0 {
		return 0
	}

	if skipped && elapsed-tSkip >= 200*time.Millisecond && total > atSkip {
		return float64(total-atSkip) / (elapsed - tSkip).Seconds() / 1e6
	}

	return float64(total) / elapsed.Seconds() / 1e6
}

// MeasureDownload does `GET rawURL` over conn (closed here). HTTP/1.1, one connection.
func MeasureDownload(ctx context.Context, conn net.Conn, rawURL string, maxBytes int64, maxDur, skip time.Duration) DownloadResult {
	defer conn.Close()

	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return DownloadResult{Err: "bad url"}
	}

	ctx, cancel := context.WithTimeout(ctx, maxDur)
	defer cancel()

	tr := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return conn, nil
		},
		DisableKeepAlives: true,
		// one HTTP/1.1 connection, no multiplexing
		TLSNextProto:    map[string]func(string, *tls.Conn) http.RoundTripper{},
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true, ServerName: u.Hostname()},
	}
	defer tr.CloseIdleConnections()

	client := &http.Client{Transport: tr, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return DownloadResult{Err: err.Error()}
	}

	req.Header.Set("User-Agent", "curl/8.5.0")

	start := time.Now()

	resp, err := client.Do(req)
	if err != nil {
		return DownloadResult{Err: err.Error(), Ms: int(time.Since(start).Milliseconds())}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return DownloadResult{Err: fmt.Sprintf("HTTP %d", resp.StatusCode), Ms: int(time.Since(start).Milliseconds())}
	}

	var (
		total, atSkip int64
		tSkip         time.Duration
		skipped       bool
	)

	buf := make([]byte, 32*1024)

	for total < maxBytes {
		n, err := resp.Body.Read(buf)
		total += int64(n)

		if !skipped && time.Since(start) >= skip {
			skipped, atSkip, tSkip = true, total, time.Since(start)
		}

		if err != nil {
			break // the end, the time limit or a cut connection: what came is what counts
		}
	}

	elapsed := time.Since(start)

	if total == 0 {
		if ctx.Err() != nil {
			return DownloadResult{Err: "timeout", Ms: int(elapsed.Milliseconds())}
		}

		return DownloadResult{Err: "no data", Ms: int(elapsed.Milliseconds())}
	}

	return DownloadResult{Bytes: total, Ms: int(elapsed.Milliseconds()), MBps: speedOf(total, atSkip, elapsed, tSkip, skipped)}
}

// DownloadVia does the download through node `name` with its `server` replaced by `server` (like TryIP): the request
// goes the way real traffic of that node goes.
func DownloadVia(ctx context.Context, name, server string, isDomain bool, rawURL string, maxBytes int64, maxDur, skip time.Duration) DownloadResult {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()

	if raw == nil {
		return DownloadResult{Err: "node not found"}
	}

	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" {
		return DownloadResult{Err: "bad url"}
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
		return DownloadResult{Err: "bad url"}
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
		return DownloadResult{Err: "cannot build the node with this server"}
	}
	defer closeProxy(px)

	dctx, cancel := context.WithTimeout(ctx, maxDur)
	defer cancel()

	conn, err := px.DialContext(dctx, &C.Metadata{Host: u.Hostname(), DstPort: uint16(portNum)})
	if err != nil {
		return DownloadResult{Err: err.Error()}
	}

	return MeasureDownload(ctx, conn, rawURL, maxBytes, maxDur, skip)
}
