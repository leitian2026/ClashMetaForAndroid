package conftest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/url"
	"testing"
	"time"
)

func serveLocalTLS(t *testing.T, h2 bool) (netip.Addr, string) {
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "example.com" {
			t.Errorf("Host = %q", r.Host)
		}
		w.Header().Set("Cf-Ray", "8a1b2c3d4e5f6a7b-HKG")
	}))
	srv.EnableHTTP2 = h2
	srv.StartTLS()
	t.Cleanup(srv.Close)
	u, _ := url.Parse(srv.URL)
	host, port, _ := net.SplitHostPort(u.Host)
	return netip.MustParseAddr(host), port
}

func TestProbeLocal(t *testing.T) {
	for _, h2 := range []bool{true, false} {
		ip, port := serveLocalTLS(t, h2)
		ipPort = port
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		row := probeIP(ctx, ip, "example.com", nil)
		cancel()
		want := "http/1.1"
		if h2 {
			want = "h2"
		}
		if !row.OK || row.ALPN != want || row.TLS != "1.3" || row.Colo != "HKG" || row.ECH != "none" || row.Ms < 0 {
			t.Errorf("h2=%v: %+v", h2, row)
		}
		t.Logf("%+v", row)
	}
}

func TestProbeDead(t *testing.T) {
	ipPort = "1" // nothing listens
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	row := probeIP(ctx, netip.MustParseAddr("127.0.0.1"), "example.com", nil)
	if row.OK || row.Reason == "" {
		t.Errorf("%+v", row)
	}
}

func TestRunIPModes(t *testing.T) {
	rep := RunIP(`{"kind":"ip","mode":"nodes"}`)
	if rep.Mode != "nodes" || rep.Nodes == nil {
		t.Errorf("%+v", rep)
	}
	rep = RunIP(`{"kind":"ip","mode":"direct","domain":"","ips":"1.1.1.1"}`)
	if rep.Error != "no domain" {
		t.Errorf("%+v", rep)
	}
	rep = RunIP(`{"kind":"ip","mode":"node","node":"","ips":"1.1.1.1 bad"}`)
	if rep.Error != "no node" || len(rep.Invalid) != 1 {
		t.Errorf("%+v", rep)
	}
	rep = RunIP(`{"kind":"ip","mode":"direct","domain":"x","ips":"  "}`)
	if rep.Error != "no address" {
		t.Errorf("%+v", rep)
	}
}

func TestCancelNothing(t *testing.T) {
	if CancelIPTest() {
		t.Error("nothing was running")
	}
}

func TestRunIPCancel(t *testing.T) {
	ip, port := serveLocalTLS(t, true)
	ipPort = port
	if ip.String() != "127.0.0.1" {
		t.Skip("local server not on 127.0.0.1")
	}

	done := make(chan IPReport, 1)
	start := time.Now()

	go func() {
		done <- RunIP(`{"kind":"ip","mode":"direct","domain":"example.com","ips":"127.0.0.1 127.0.0.1 127.0.0.2 127.0.0.3 127.0.0.4"}`)
	}()

	// the first address is tested at once, then the run waits 1 to 3 seconds before the next one
	time.Sleep(600 * time.Millisecond)

	if !CancelIPTest() {
		t.Fatal("nothing to cancel")
	}

	select {
	case rep := <-done:
		if !rep.Cancelled || len(rep.Rows) != 1 || time.Since(start) > 2*time.Second {
			t.Errorf("%+v after %v", rep, time.Since(start))
		}
	case <-time.After(5 * time.Second):
		t.Fatal("did not stop")
	}

	if CancelIPTest() {
		t.Error("the run is over, nothing to cancel")
	}
}
