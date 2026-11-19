package conftest

import "testing"

func TestWebsocketAcceptRFC6455Example(t *testing.T) {
	const key = "dGhlIHNhbXBsZSBub25jZQ=="
	const want = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
	if got := websocketAccept(key); got != want {
		t.Fatalf("websocketAccept(%q) = %q, want %q", key, got, want)
	}
}

func TestIPExtEnabled(t *testing.T) {
	if !ipExtEnabled("ws, H3, mtu", "h3") {
		t.Fatal("ipExtEnabled did not match a trimmed extension")
	}
	if ipExtEnabled("ws, H3, mtu", "quic") {
		t.Fatal("ipExtEnabled matched an extension that was not requested")
	}
}
