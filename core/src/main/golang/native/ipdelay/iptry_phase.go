package ipdelay

// CUSTOM (CUSTOM.md #68, not part of upstream): where the time of one request through a node went. `requestVia` (used
// by other callers too) is left as it is; TryIP and TryTarget use what is here so the config check page can show the three
// parts next to the total. Nothing here runs on its own and no extra request is made: the times are read from the
// request that was made anyway (net/http/httptrace).

import (
	"crypto/tls"
	"net/http"
	"net/http/httptrace"
	"sync"
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
	tlsMu    sync.RWMutex
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
			p.tlsMu.Lock()
			p.tlsState = state
			p.tlsMu.Unlock()
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

	state := p.connectionState()
	ph.TLS = TLSVersionName(state.Version)
	ph.ALPN = state.NegotiatedProtocol

	return ph
}

func (p *phaseClock) connectionState() tls.ConnectionState {
	p.tlsMu.RLock()
	defer p.tlsMu.RUnlock()
	return p.tlsState
}

// phaseMs rounds nanoseconds to milliseconds, at least 1 (a part that was measured is never shown as "not measured").
func phaseMs(ns int64) int {
	v := int((ns + 500000) / 1000000)
	if v < 1 {
		v = 1
	}

	return v
}

// requestPhased keeps the existing TryIP result shape while sharing the HTTP/TLS request path with TryTarget.
func requestPhased(px C.Proxy, rawURL string, timeout time.Duration) (int, time.Duration, Phases, error) {
	result, err := requestSnapshotVia(px, rawURL, timeout, 0)
	if err != nil {
		return 0, 0, Phases{}, err
	}
	return result.Status, result.Elapsed, result.Ph, nil
}
