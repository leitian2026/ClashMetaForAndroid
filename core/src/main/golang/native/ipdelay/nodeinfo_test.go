package ipdelay

import (
	"reflect"
	"testing"
)

func TestIPv6Nodes(t *testing.T) {
	lock.Lock()
	proxies = map[string]map[string]any{
		"v6-literal": {"server": "2001:db8::1"},
		"v6-hosts":   {"server": "a.example.com"},
		"v6-flag":    {"server": "b.example.com", "ip-version": "ipv6"},
		"mixed":      {"server": "c.example.com"},
		"v4":         {"server": "1.2.3.4"},
		"domain":     {"server": "d.example.com"},
		"prefer":     {"server": "e.example.com", "ip-version": "ipv6-prefer"},
	}
	nodeIPs = map[string][]string{
		"v6-literal": {"2001:db8::1"},
		"v6-hosts":   {"2001:db8::2", "2001:db8::3"},
		"mixed":      {"2001:db8::4", "5.6.7.8"},
		"v4":         {"1.2.3.4"},
	}
	lock.Unlock()

	got := IPv6Nodes()
	want := []string{"v6-flag", "v6-hosts", "v6-literal"}

	if !reflect.DeepEqual(got, want) {
		t.Errorf("IPv6Nodes() = %v, want %v", got, want)
	}
}

func TestNodeInfoTimes(t *testing.T) {
	MarkNetworkChanged()
	MarkTested()

	info := NodeInfo()
	if info.TestedAt == 0 || info.NetworkChangedAt == 0 || info.TestedAt < info.NetworkChangedAt {
		t.Errorf("times: %+v", info)
	}
}
