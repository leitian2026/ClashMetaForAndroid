package conftest

import (
	"context"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"
)

// The DNS check must ask a DoH server the way the core does (GET, ?dns=base64url, id 0, empty User-Agent).
func TestDohExchangeLikeCore(t *testing.T) {
	var gotMethod, gotUA, gotAccept string

	var gotMsg []byte

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotUA, gotAccept = r.Method, r.Header.Get("User-Agent"), r.Header.Get("Accept")
		gotMsg, _ = base64.RawURLEncoding.DecodeString(r.URL.Query().Get("dns"))
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write([]byte{0, 0, 0x81, 0x80})
	}))
	defer srv.Close()

	q, err := buildQuery("example.com", 1, 0x1234)
	if err != nil {
		t.Fatal(err)
	}

	body, ct, err := dohExchangeLikeCore(context.Background(), srv.URL+"/dns-query", q)
	if err != nil {
		t.Fatal(err)
	}

	if gotMethod != http.MethodGet || gotUA != "" || gotAccept != "application/dns-message" {
		t.Errorf("request shape: %s UA=%q Accept=%q", gotMethod, gotUA, gotAccept)
	}

	if len(gotMsg) != len(q) || gotMsg[0] != 0 || gotMsg[1] != 0 || string(gotMsg[2:]) != string(q[2:]) {
		t.Errorf("message must be the query with id 0: %x vs %x", gotMsg, q)
	}

	if ct != "application/dns-message" || len(body) != 4 {
		t.Errorf("reply: %q %x", ct, body)
	}
}
