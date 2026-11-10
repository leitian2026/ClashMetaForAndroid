package errlog

import (
	"strings"
	"testing"
	"time"
)

func reset() { Clear() }

func TestParseDialWithRule(t *testing.T) {
	reset()
	record(false, "[TCP] dial 香港 01 (match DomainSuffix/example.com) 198.18.0.1:5000 --> www.example.com:443 error: dial tcp 1.2.3.4:443: i/o timeout", time.Unix(100, 0))
	s := Query()
	if len(s.Items) != 1 {
		t.Fatalf("items=%d", len(s.Items))
	}
	e := s.Items[0]
	if e.Kind != KindConnect || e.Node != "香港 01" || e.Rule != "DomainSuffix/example.com" ||
		e.Dest != "www.example.com:443" || e.Code != CodeTimeout || e.Network != "TCP" || e.Level != "warn" {
		t.Fatalf("bad entry %+v", e)
	}
}

func TestParseDialWithoutRule(t *testing.T) {
	reset()
	record(false, "[UDP] dial DIRECT 10.0.0.2:1 --> 8.8.8.8:53 error: connection refused", time.Unix(100, 0))
	e := Query().Items[0]
	if e.Node != "DIRECT" || e.Rule != "" || e.Code != CodeRefused || e.Network != "UDP" {
		t.Fatalf("bad entry %+v", e)
	}
}

func TestMergeAndOrder(t *testing.T) {
	reset()
	line := "[TCP] dial A (match Match/) 1.1.1.1:1 --> x.com:443 error: EOF"
	record(false, line, time.Unix(100, 0))
	record(true, "[Config] something broke", time.Unix(200, 0))
	record(false, line, time.Unix(300, 0))
	s := Query()
	if s.Total != 3 || len(s.Items) != 2 {
		t.Fatalf("total=%d items=%d", s.Total, len(s.Items))
	}
	if s.Items[0].Count != 2 || s.Items[0].Time != 300000 || s.Items[0].First != 100000 || s.Items[0].Code != CodeEOF {
		t.Fatalf("bad merge %+v", s.Items[0])
	}
	if s.Items[1].Kind != KindOther || s.Items[1].Tag != "Config" || s.Items[1].Level != "error" {
		t.Fatalf("bad other %+v", s.Items[1])
	}
}

func TestRingLimit(t *testing.T) {
	reset()
	for i := 0; i < maxEntries+50; i++ {
		record(false, "[TCP] dial N (match M/) 1:1 --> h"+strings.Repeat("x", i%7)+string(rune('a'+i%26))+string(rune('A'+i/26))+":1 error: timeout", time.Unix(int64(i), 0))
	}
	if n := len(Query().Items); n > maxEntries {
		t.Fatalf("items=%d", n)
	}
}

func TestClassify(t *testing.T) {
	cases := map[string]string{
		"dial tcp: lookup a.com: no such host":          CodeDNS,
		"context deadline exceeded":                     CodeTimeout,
		"read tcp 1:2->3:4: connection reset by peer":   CodeReset,
		"tls: failed to verify certificate: x509: bad":  CodeTLS,
		"connect: network is unreachable":               CodeNetwork,
		"EOF":                                           CodeEOF,
		"something strange":                             CodeOther,
	}
	for in, want := range cases {
		if got := classify(in); got != want {
			t.Errorf("%q: got %s want %s", in, got, want)
		}
	}
}

func TestClipUTF8(t *testing.T) {
	s := clip(strings.Repeat("节", 300))
	if !strings.HasSuffix(s, "…") || strings.Contains(s, "�") {
		t.Fatalf("bad clip")
	}
}

func TestClear(t *testing.T) {
	record(false, "[DNS] fail", time.Now())
	Clear()
	if s := Query(); s.Total != 0 || len(s.Items) != 0 {
		t.Fatalf("not cleared")
	}
}

func TestFold(t *testing.T) {
	reset()

	now := time.Unix(500, 0)
	v6 := "[TCP] dial DIRECT (match DomainSuffix/challenges.cloudflare.com) 127.0.0.1:41160 --> a.challenges.cloudflare.com:443 error: dial tcp [2606:4700::6812:1192]:443: connect: network is unreachable"

	record(false, v6, now)
	record(false, v6, now.Add(time.Second))
	record(false, "[TCP] dial N (match M/) 1:1 --> h.com:443 error: context canceled", now)
	record(false, "[TCP] dial N (match M/) 1:1 --> h.com:443 error: write tcp 1:2->3:4: write: broken pipe", now)

	// not folded: an IPv4 address in the same error, a plain IPv4 unreachable, and a reset
	record(false, "[TCP] dial N (match M/) 1:1 --> h.com:443 error: dial tcp 1.2.3.4:443: i/o timeout\ndial tcp [2606:4700::1]:443: connect: network is unreachable", now)
	record(false, "[TCP] dial N (match M/) 1:1 --> h.com:443 error: dial tcp 1.2.3.4:443: connect: network is unreachable", now)
	record(false, "[TCP] dial N (match M/) 1:1 --> h.com:443 error: read tcp 1:2->3:4: connection reset by peer", now)

	s := Query()
	if len(s.Items) != 3 || s.Total != 3 {
		t.Fatalf("items=%d total=%d", len(s.Items), s.Total)
	}

	if len(s.Folded) != 2 || s.Folded[0].Kind != FoldNoIPv6 || s.Folded[0].Count != 2 ||
		s.Folded[0].Time != now.Add(time.Second).UnixMilli() || s.Folded[1].Kind != FoldClosed || s.Folded[1].Count != 2 {
		t.Fatalf("folded %+v", s.Folded)
	}

	Clear()

	if s := Query(); len(s.Folded) != 0 {
		t.Fatalf("not cleared %+v", s.Folded)
	}
}
