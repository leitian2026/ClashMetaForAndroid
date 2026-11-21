package ipdelay

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestSpeedOf(t *testing.T) {
	// 1 MB in the first second, 4 MB more in the next 4 seconds: 1 MB/s counted after the first second
	if v := speedOf(5e6, 1e6, 5*time.Second, time.Second, true); v < 0.99 || v > 1.01 {
		t.Errorf("%v", v)
	}

	// ended before the skip: the whole
	if v := speedOf(10e6, 0, 500*time.Millisecond, 0, false); v < 19.9 || v > 20.1 {
		t.Errorf("%v", v)
	}

	// ended right after the skip (less than 200 ms left): the whole
	if v := speedOf(2e6, 1.9e6, 1100*time.Millisecond, time.Second, true); v < 1.8 || v > 1.83 {
		t.Errorf("%v", v)
	}
}

func TestMeasureDownloadLimits(t *testing.T) {
	// endless stream, about 1 MB/s: stops at the time limit, with the first 0.3 s left out of the speed
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		chunk := make([]byte, 10_000)
		for {
			if _, err := w.Write(chunk); err != nil {
				return
			}

			w.(http.Flusher).Flush()
			time.Sleep(10 * time.Millisecond)
		}
	}))
	defer srv.Close()

	conn, err := net.Dial("tcp", srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}

	start := time.Now()
	res := MeasureDownload(context.Background(), conn, srv.URL, 10<<20, 1500*time.Millisecond, 300*time.Millisecond)

	if time.Since(start) > 2500*time.Millisecond || res.Err != "" || res.Short || res.Bytes < 500_000 || res.MBps < 0.3 || res.MBps > 1.2 {
		t.Errorf("%+v after %v", res, time.Since(start))
	}

	// the byte limit
	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(make([]byte, 1<<20)) }))
	defer srv2.Close()

	conn, _ = net.Dial("tcp", srv2.Listener.Addr().String())
	res = MeasureDownload(context.Background(), conn, srv2.URL, 256<<10, 5*time.Second, time.Second)

	if res.Err != "" || res.Short || res.Bytes < 256<<10 || res.Bytes > 256<<10+64<<10 {
		t.Errorf("%+v", res)
	}

	// a completed file smaller than the limit is explicitly marked short
	srv4 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("small file"))
	}))
	defer srv4.Close()

	conn, _ = net.Dial("tcp", srv4.Listener.Addr().String())
	res = MeasureDownload(context.Background(), conn, srv4.URL, 1<<20, 5*time.Second, time.Second)
	if res.Err != "" || !res.Short || res.Bytes != int64(len("small file")) {
		t.Errorf("%+v", res)
	}

	// a bad status
	srv3 := httptest.NewServer(http.NotFoundHandler())
	defer srv3.Close()

	conn, _ = net.Dial("tcp", srv3.Listener.Addr().String())

	if res = MeasureDownload(context.Background(), conn, srv3.URL, 1<<20, time.Second, 0); res.Err != "HTTP 404" {
		t.Errorf("%+v", res)
	}
}
