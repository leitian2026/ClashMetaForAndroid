package conftest

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"reflect"
	"strconv"
	"testing"
	"time"
)

func TestSummarize(t *testing.T) {
	st := summarize([]int{100, 200, 300}, 10)
	if st.Loss != 70 || st.Min != 100 || st.Max != 300 || st.Avg != 200 || st.OK != 3 || st.Sent != 10 {
		t.Errorf("%+v", st)
	}

	st = summarize(nil, 10)
	if st.Loss != 100 || st.Avg != 0 {
		t.Errorf("%+v", st)
	}

	if st = summarize([]int{1, 2}, 2); st.Loss != 0 || st.Avg != 2 { // 1.5 rounds up
		t.Errorf("%+v", st)
	}
}

func TestParsePorts(t *testing.T) {
	cases := map[string][]int{
		"":                  {443, 2053, 2083, 2087, 2096, 8443},
		"  ":                {443, 2053, 2083, 2087, 2096, 8443},
		"443, 8443;443":     {443, 8443},
		"0 65536 abc 80，81": {80, 81},
		"abc":               {443, 2053, 2083, 2087, 2096, 8443},
	}

	for in, want := range cases {
		if got := parsePorts(in); !reflect.DeepEqual(got, want) {
			t.Errorf("%q: %v want %v", in, got, want)
		}
	}

	if got := parsePorts("1 2 3 4 5 6 7 8 9 10 11 12 13 14"); len(got) != maxPorts {
		t.Errorf("%v", got)
	}
}

func TestParseColos(t *testing.T) {
	got := parseColos([]byte(`[{"iata":"hkg","city":"Hong Kong","cca2":"HK"},{"iata":"","city":"x"},{"iata":"LAX","city":"Los Angeles"}]`))
	if !reflect.DeepEqual(got, map[string]string{"HKG": "Hong Kong", "LAX": "Los Angeles"}) {
		t.Errorf("%v", got)
	}

	if len(parseColos([]byte(`nope`))) != 0 {
		t.Error("garbage")
	}
}

// CUSTOM.md #62: the country of a data centre, from "cca2" of the same list
func TestParseColoCountries(t *testing.T) {
	got := parseColoCountries([]byte(`[{"iata":"hkg","city":"Hong Kong","cca2":"hk"},{"iata":"","cca2":"US"},{"iata":"LAX","city":"Los Angeles"},{"iata":"NRT","cca2":"JPN"}]`))
	if !reflect.DeepEqual(got, map[string]string{"HKG": "HK"}) {
		t.Errorf("%v", got)
	}

	if len(parseColoCountries([]byte(`nope`))) != 0 {
		t.Error("garbage")
	}
}

func TestProbeDirectLocal(t *testing.T) {
	ip, port := serveLocalTLS(t, true)
	ipPort = port
	asnCache.Store(ip.String(), "AS1 Test")
	t.Cleanup(func() { ipPort = "443" })

	rep := RunIP(`{"kind":"ip","mode":"probe","domain":"example.com","ips":"` + ip.String() + `","ports":"` + port + ` 1"}`)

	if rep.Error != "" || len(rep.Rows) != 1 {
		t.Fatalf("%+v", rep)
	}

	r := rep.Rows[0]
	if !r.OK || r.Sent != 10 || r.Loss != 0 || r.Min <= 0 && r.Max < r.Min || r.Avg < r.Min || r.Avg > r.Max ||
		r.Colo != "HKG" || !r.CF || r.ALPN != "h2" || r.ASN != "AS1 Test" {
		t.Errorf("%+v", r)
	}

	if len(r.Ports) != 2 || !r.Ports[0].OK || r.Ports[1].OK || r.Ports[1].Port != 1 || r.Ports[1].Reason == "" {
		t.Errorf("ports %+v", r.Ports)
	}
}

func TestProbeDirectDead(t *testing.T) {
	ipPort = "1"
	t.Cleanup(func() { ipPort = "443" })

	rep := RunIP(`{"kind":"ip","mode":"probe","domain":"example.com","ips":"127.0.0.1"}`)
	if len(rep.Rows) != 1 || rep.Rows[0].OK || rep.Rows[0].Loss != 100 || rep.Rows[0].Reason == "" || len(rep.Rows[0].Ports) != 0 {
		t.Errorf("%+v", rep)
	}
}

func TestProbeErrors(t *testing.T) {
	if rep := RunIP(`{"kind":"ip","mode":"probe","ips":"127.0.0.1"}`); rep.Error != "no domain" {
		t.Errorf("%+v", rep)
	}

	if rep := RunIP(`{"kind":"ip","mode":"probe","domain":"example.com","ips":"foo.example.org"}`); rep.Error != "no address" {
		t.Errorf("%+v", rep)
	}

	if rep := RunIP(`{"kind":"ip","mode":"nodeone","ips":"127.0.0.1"}`); rep.Error != "no node" {
		t.Errorf("%+v", rep)
	}

	if rep := RunIP(`{"kind":"ip","mode":"download","ips":"127.0.0.1","url":"ftp://x/y"}`); rep.Error != "bad url" {
		t.Errorf("%+v", rep)
	}
}

func TestProbeCancel(t *testing.T) {
	// a listener that accepts and says nothing: the handshakes hang until they time out or are cancelled
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })

	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			_ = c
		}
	}()

	_, port, _ := net.SplitHostPort(ln.Addr().String())
	ipPort = port
	t.Cleanup(func() { ipPort = "443" })

	done := make(chan IPReport)

	go func() { done <- RunIP(`{"kind":"ip","mode":"probe","domain":"example.com","ips":"127.0.0.1"}`) }()

	time.Sleep(300 * time.Millisecond)

	if !CancelIPTest() {
		t.Fatal("nothing to cancel")
	}

	select {
	case rep := <-done:
		if !rep.Cancelled {
			t.Errorf("%+v", rep)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("cancel did not stop the run")
	}
}

func TestDownloadDirectLocal(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "example.com:"+strconv.Itoa(portOf(r)) {
			t.Errorf("Host = %q", r.Host)
		}
		_, _ = w.Write(make([]byte, 3<<20))
	}))
	t.Cleanup(srv.Close)

	u, _ := url.Parse(srv.URL)
	host, port, _ := net.SplitHostPort(u.Host)

	rep := RunIP(`{"kind":"ip","mode":"download","ips":"` + host + `","url":"https://example.com:` + port + `/x"}`)
	if rep.Error != "" || len(rep.Rows) != 1 {
		t.Fatalf("%+v", rep)
	}

	r := rep.Rows[0]
	if r.SpeedErr != "" || r.Speed <= 0 || r.SpeedBytes != 3<<20 {
		t.Errorf("%+v", r)
	}

	t.Logf("%+v", r)
}

func portOf(r *http.Request) int {
	_, p, _ := net.SplitHostPort(r.Context().Value(http.LocalAddrContextKey).(net.Addr).String())
	n, _ := strconv.Atoi(p)

	return n
}

func TestDownloadDead(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = ctx

	rep := RunIP(`{"kind":"ip","mode":"download","ips":"127.0.0.1","url":"https://example.com:1/x"}`)
	if len(rep.Rows) != 1 || rep.Rows[0].SpeedErr == "" || rep.Rows[0].Speed != 0 {
		t.Errorf("%+v", rep)
	}
}

func TestParseList(t *testing.T) {
	rep := RunIP(`{"kind":"ip","mode":"parse","direct":true,"ips":"1.1.1.1 example.com bad! 1.0.0.1:443"}`)
	if rep.Error != "" || len(rep.Rows) != 2 || rep.Rows[1].IP != "1.0.0.1" || len(rep.Skipped) != 1 || len(rep.Invalid) != 1 {
		t.Errorf("%+v", rep)
	}

	rep = RunIP(`{"kind":"ip","mode":"parse","direct":false,"ips":"1.1.1.1 example.com"}`)
	if len(rep.Rows) != 2 || !rep.Rows[1].IsDomain {
		t.Errorf("%+v", rep)
	}

	if rep = RunIP(`{"kind":"ip","mode":"parse","direct":true,"ips":"example.com"}`); rep.Error != "domain only node" {
		t.Errorf("%+v", rep)
	}

	if rep = RunIP(`{"kind":"ip","mode":"parse","ips":"zzz"}`); rep.Error != "no address" {
		t.Errorf("%+v", rep)
	}
}
