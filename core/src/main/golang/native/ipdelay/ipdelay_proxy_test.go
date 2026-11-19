package ipdelay

import (
	"context"
	"strings"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

func TestNewVariantDialerProxyReceivesTunnel(t *testing.T) {
	raw := map[string]any{
		"name":         "test-child",
		"type":         "socks5",
		"server":       "127.0.0.1",
		"port":         1080,
		"dialer-proxy": "missing-parent",
	}

	px := newVariant(raw, "192.0.2.10")
	if px == nil {
		t.Fatal("newVariant returned nil")
	}
	defer closeProxy(px)

	_, err := px.DialContext(context.Background(), &C.Metadata{
		Host:    "example.com",
		DstPort: 80,
	})
	if err == nil {
		t.Fatal("expected the missing parent proxy to be reported")
	}
	if strings.Contains(err.Error(), "tunnel is invalid") {
		t.Fatalf("dialer-proxy did not receive the active tunnel: %v", err)
	}
	if !strings.Contains(err.Error(), "proxyName[missing-parent] not found") {
		t.Fatalf("unexpected dial error: %v", err)
	}
}
