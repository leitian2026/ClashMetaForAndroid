package ipdelay

import "testing"

func TestParsePolicyKeepsOrder(t *testing.T) {
	data := []byte(`{"enable":true,"nameserver":["1.1.1.1"],"nameserver-policy":{"zzz.com":"1.1.1.1","geosite:cn":["223.5.5.5","119.29.29.29"],"aaa.com":["8.8.8.8"]},"ipv6":false}`)

	got := parsePolicy(data)

	if len(got) != 3 || got[0].Key != "zzz.com" || got[1].Key != "geosite:cn" || got[2].Key != "aaa.com" {
		t.Fatalf("order lost: %+v", got)
	}

	if len(got[1].Servers) != 2 || got[0].Servers[0] != "1.1.1.1" {
		t.Fatalf("servers wrong: %+v", got)
	}
}

func TestParsePolicyNone(t *testing.T) {
	for _, data := range []string{`{"enable":true}`, `{"nameserver-policy":null}`, `[]`, ``} {
		if got := parsePolicy([]byte(data)); len(got) != 0 {
			t.Errorf("parsePolicy(%s) = %+v, want none", data, got)
		}
	}
}
