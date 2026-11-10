package conftest

import (
	"context"
	"encoding/binary"
	"errors"
	"testing"
)

func TestVerdict(t *testing.T) {
	ok := Attempt{OK: true}
	acc := Attempt{OK: true, Accepted: true}
	bad := Attempt{Code: "reset"}
	rej := Attempt{Code: "echrejected"}

	cases := []struct {
		e, p Attempt
		want string
	}{
		{acc, bad, VerdictEffective},
		{acc, ok, VerdictAccepted},
		{rej, bad, VerdictRejected},
		{bad, ok, VerdictFailed},
		{bad, bad, VerdictUnknown},
		{ok, ok, VerdictUnknown}, // connected but ECH not accepted: never "effective"
	}

	for i, c := range cases {
		if got := verdict(c.e, c.p); got != c.want {
			t.Errorf("case %d: got %s want %s", i, got, c.want)
		}
	}
}

func TestDecodeConfig(t *testing.T) {
	if _, err := decodeConfig(""); err == nil {
		t.Fatal("empty must fail")
	}
	if b, err := decodeConfig("AAEC"); err != nil || len(b) != 3 {
		t.Fatalf("std: %v %v", b, err)
	}
	if b, err := decodeConfig("AAE"); err != nil || len(b) != 2 {
		t.Fatalf("raw: %v %v", b, err)
	}
}

func TestHelpers(t *testing.T) {
	if toInt(443) != 443 || toInt(443.0) != 443 || toInt("443") != 443 || toInt(nil) != 0 {
		t.Fatal("toInt")
	}
	if s := toStrings([]any{"h2", 3, "http/1.1"}); len(s) != 2 || s[1] != "http/1.1" {
		t.Fatalf("toStrings %v", s)
	}
	if firstString(nil, "", " a ", "b") != "a" {
		t.Fatal("firstString")
	}
}

func TestBuildQuery(t *testing.T) {
	q, err := buildHTTPSQuery("cloudflare-ech.com.")
	if err != nil {
		t.Fatal(err)
	}
	want := []byte{0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
		14, 'c', 'l', 'o', 'u', 'd', 'f', 'l', 'a', 'r', 'e', '-', 'e', 'c', 'h',
		3, 'c', 'o', 'm', 0, 0, 65, 0, 1}
	if string(q) != string(want) {
		t.Fatalf("query %v", q)
	}
	if _, err := buildHTTPSQuery(""); err == nil {
		t.Fatal("empty name must fail")
	}
}

// reply builds a DNS reply with one HTTPS answer whose name is a pointer to the question.
func reply(rcode byte, params []byte) []byte {
	q, _ := buildHTTPSQuery("a.com")
	m := append([]byte(nil), q...)
	m[2], m[3] = 0x81, 0x80|rcode
	binary.BigEndian.PutUint16(m[6:], 1) // one answer

	rdata := append([]byte{0, 1, 0}, params...) // priority 1, target "." , params
	rr := []byte{0xC0, 12, 0, 65, 0, 1, 0, 0, 0, 60}
	rr = binary.BigEndian.AppendUint16(rr, uint16(len(rdata)))

	return append(append(m, rr...), rdata...)
}

func param(key uint16, value []byte) []byte {
	b := binary.BigEndian.AppendUint16(nil, key)
	b = binary.BigEndian.AppendUint16(b, uint16(len(value)))

	return append(b, value...)
}

func TestParseECH(t *testing.T) {
	alpn := param(1, []byte{2, 'h', '2'})
	ech := param(5, []byte{9, 8, 7})

	got, err := parseECH(reply(0, append(append([]byte(nil), alpn...), ech...)))
	if err != nil || string(got) != string([]byte{9, 8, 7}) {
		t.Fatalf("got %v %v", got, err)
	}

	if _, err := parseECH(reply(0, alpn)); err == nil {
		t.Fatal("no ech param must fail")
	}
	if _, err := parseECH(reply(3, ech)); err == nil {
		t.Fatal("NXDOMAIN must fail")
	}
	if _, err := parseECH([]byte{1, 2, 3}); err == nil {
		t.Fatal("short must fail")
	}

	// truncated input must never panic
	full := reply(0, ech)
	for i := 0; i < len(full); i++ {
		_, _ = parseECH(full[:i])
	}
}

func TestPrepare(t *testing.T) {
	var asked string

	fetch = func(_ context.Context, name string, _ []string) ([]byte, string, error) {
		asked = name
		return []byte{1}, "https://doh.example/dns-query", nil
	}

	node := map[string]any{
		"type": "vless", "server": "1.2.3.4", "port": 443, "alpn": []any{"h3"},
		"servername": "inner.example",
		"ech-opts":   map[string]any{"enable": true, "query-server-name": "cloudflare-ech.com"},
	}

	it, list, alpn := prepare("n", node, "x.workers.dev", nil)
	if it.Status != StatusTested || asked != "cloudflare-ech.com" || it.Source != "https://doh.example/dns-query" ||
		len(list) != 1 || alpn[0] != "h3" || len(it.Targets) != 1 || it.Targets[0].Addr != "1.2.3.4:443" {
		t.Fatalf("bad %+v %v %v asked=%s", it, list, alpn, asked)
	}

	// no query-server-name: the node's servername is asked
	node["ech-opts"] = map[string]any{"enable": true}
	delete(node, "alpn")

	_, _, alpn = prepare("n", node, "x.workers.dev", nil)
	if asked != "inner.example" || alpn[0] != "h2" {
		t.Fatalf("asked=%s alpn=%v", asked, alpn)
	}

	// a written config is used and nothing is fetched
	asked = ""
	node["ech-opts"] = map[string]any{"enable": true, "config": "AAEC"}

	it, list, _ = prepare("n", node, "x.workers.dev", nil)
	if it.Status != StatusTested || it.Source != "config" || asked != "" || len(list) != 3 {
		t.Fatalf("bad %+v", it)
	}

	node["ech-opts"] = map[string]any{"enable": false}
	if it, _, _ = prepare("n", node, "d", nil); it.Status != StatusDisabled {
		t.Fatalf("disabled: %+v", it)
	}

	node["type"] = "hysteria2"
	if it, _, _ = prepare("n", node, "d", nil); it.Status != StatusUnsupported {
		t.Fatalf("unsupported: %+v", it)
	}

	node["type"] = "vless"
	node["ech-opts"] = map[string]any{"enable": true}
	fetch = func(context.Context, string, []string) ([]byte, string, error) { return nil, "", errors.New("boom") }

	if it, _, _ = prepare("n", node, "d", nil); it.Status != StatusFetchFailed || it.Error != "boom" {
		t.Fatalf("fetchfailed: %+v", it)
	}
}
