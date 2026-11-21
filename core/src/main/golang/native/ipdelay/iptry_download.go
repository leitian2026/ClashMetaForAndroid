package ipdelay

// CUSTOM (CUSTOM.md #52, not part of upstream): the download test of "test other IPs". One call is one download, made
// only when the user taps "下载测速" on a row; nothing here runs on its own.
//
// The first byte has its own 10-second context timeout. Once a body byte arrives, maxDur and skip start from that
// instant; the download then stops at maxBytes or maxDur, whichever comes first.

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"sync/atomic"
	"time"

	"github.com/metacubex/mihomo/log"
	C "github.com/metacubex/mihomo/constant"
)

const firstByteTimeout = 10 * time.Second

// DownloadResult is the outcome of a download test. Err is "" when some bytes came.
type DownloadResult struct {
	Bytes int64
	Ms    int
	MBps  float64
	Err   string
	Short bool // true when the completed response is smaller than maxBytes
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

// MeasureDownload does GET rawURL over conn (closed here). HTTP/1.1, one connection.
// The caller has already made the connection; the first-byte wait begins here.
func MeasureDownload(ctx context.Context, conn net.Conn, rawURL string, maxBytes int64, maxDur, skip time.Duration) DownloadResult {
	firstByteCtx, cancelFirstByte := context.WithTimeout(ctx, firstByteTimeout)
	defer cancelFirstByte()

	return measureDownload(ctx, firstByteCtx, cancelFirstByte, conn, rawURL, maxBytes, maxDur, skip)
}

// measureDownload shares the first-byte deadline with DownloadVia when that caller must establish the node connection
// first. After the first body byte, it switches to a fresh maxDur context using context timeouts only.
func measureDownload(ctx, firstByteCtx context.Context, cancelFirstByte context.CancelFunc, conn net.Conn, rawURL string, maxBytes int64, maxDur, skip time.Duration) DownloadResult {
	defer conn.Close()

	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return DownloadResult{Err: "bad url"}
	}

	requestCtx, cancelRequest := context.WithCancel(ctx)
	defer cancelRequest()

	firstByteReceived := make(chan struct{})
	go func() {
		select {
		case <-firstByteCtx.Done():
			select {
			case <-firstByteReceived:
			default:
				cancelRequest()
			}
		case <-requestCtx.Done():
		}
	}()

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

	req, err := http.NewRequestWithContext(requestCtx, http.MethodGet, rawURL, nil)
	if err != nil {
		return DownloadResult{Err: err.Error()}
	}

	req.Header.Set("User-Agent", "curl/8.5.0")

	requestStart := time.Now()

	resp, err := client.Do(req)
	if err != nil {
		if firstByteCtx.Err() != nil && ctx.Err() == nil {
			return DownloadResult{Err: "timeout", Ms: int(time.Since(requestStart).Milliseconds())}
		}
		return DownloadResult{Err: err.Error(), Ms: int(time.Since(requestStart).Milliseconds())}
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return DownloadResult{Err: fmt.Sprintf("HTTP %d", resp.StatusCode), Ms: int(time.Since(requestStart).Milliseconds())}
	}

	var (
		total, atSkip int64
		tSkip         time.Duration
		skipped       bool
		firstByteAt   time.Time
		cancelDownload context.CancelFunc
		readErr        error
		lastProgressAt time.Time
	)

	buf := make([]byte, 32*1024)

	for total < maxBytes {
		n, err := resp.Body.Read(buf)
		if n > 0 && firstByteAt.IsZero() {
			firstByteAt = time.Now()
			close(firstByteReceived)
			cancelFirstByte()

			downloadCtx, stopDownload := context.WithTimeout(ctx, maxDur)
			cancelDownload = stopDownload
			go func() {
				select {
				case <-downloadCtx.Done():
					cancelRequest()
				case <-requestCtx.Done():
				}
			}()
		}
		total += int64(n)

		if n > 0 && !firstByteAt.IsZero() {
			now := time.Now()
			elapsed := now.Sub(firstByteAt)
			if elapsed >= 250*time.Millisecond && (lastProgressAt.IsZero() || now.Sub(lastProgressAt) >= 250*time.Millisecond) {
				log.Infoln("[APP] IP_DOWNLOAD_PROGRESS|%d|%d", total, elapsed.Milliseconds())
				lastProgressAt = now
			}
		}

		if !firstByteAt.IsZero() && !skipped && time.Since(firstByteAt) >= skip {
			skipped, atSkip, tSkip = true, total, time.Since(firstByteAt)
		}

		if err != nil {
			readErr = err
			break // the end, the download timeout or a cut connection: what came is what counts
		}
	}

	if cancelDownload != nil {
		cancelDownload()
	}

	elapsed := time.Since(requestStart)
	if !firstByteAt.IsZero() {
		elapsed = time.Since(firstByteAt)
	}

	if total == 0 {
		if firstByteCtx.Err() != nil && ctx.Err() == nil {
			return DownloadResult{Err: "timeout", Ms: int(elapsed.Milliseconds())}
		}
		return DownloadResult{Err: "no data", Ms: int(elapsed.Milliseconds())}
	}

	short := total > 0 && total < maxBytes && (readErr == io.EOF || (resp.ContentLength >= 0 && resp.ContentLength < maxBytes && total >= resp.ContentLength))

	return DownloadResult{Bytes: total, Ms: int(elapsed.Milliseconds()), MBps: speedOf(total, atSkip, elapsed, tSkip, skipped), Short: short}
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

	firstByteCtx, cancelFirstByte := context.WithTimeout(ctx, firstByteTimeout)
	defer cancelFirstByte()

	conn, err := px.DialContext(firstByteCtx, &C.Metadata{Host: u.Hostname(), DstPort: uint16(portNum)})
	if err != nil {
		if firstByteCtx.Err() != nil && ctx.Err() == nil {
			return DownloadResult{Err: "timeout"}
		}
		return DownloadResult{Err: err.Error()}
	}

	return measureDownload(ctx, firstByteCtx, cancelFirstByte, conn, rawURL, maxBytes, maxDur, skip)
}
