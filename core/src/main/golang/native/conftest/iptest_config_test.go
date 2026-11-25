package conftest

import (
	"reflect"
	"testing"
)

// CUSTOM.md #69 / #71: putting the proxy page's addresses together.

func TestMergeDisplayOneRowPerAddress(t *testing.T) {
	r := mergeDisplay([]displayNode{
		{name: "a", key: "x.com", via: "hosts", ips: []displayIP{{"1.1.1.1", false}, {"2.2.2.2", true}}},
		{name: "b", key: "x.com", via: "hosts", chain: true, ips: []displayIP{{"2.2.2.2", false}}},
		{name: "c", via: "server", chain: true, ips: []displayIP{{"3.3.3.3", false}}},
	})

	got := []string{}
	for _, c := range r.IPs {
		got = append(got, c.IP)
	}

	// the one in use comes first, the others keep their order
	if want := []string{"2.2.2.2", "1.1.1.1", "3.3.3.3"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("ips = %v, want %v", got, want)
	}

	if !r.IPs[0].InUse || !reflect.DeepEqual(r.IPs[0].From, []string{"a", "b"}) || r.IPs[0].Chain {
		t.Fatalf("2.2.2.2 = %+v", r.IPs[0])
	}

	if r.IPs[1].InUse || r.IPs[1].Chain || r.IPs[1].Via != "hosts" || r.IPs[1].Name != "x.com" {
		t.Fatalf("1.1.1.1 = %+v", r.IPs[1])
	}

	if !r.IPs[2].Chain || r.IPs[2].Via != "server" {
		t.Fatalf("3.3.3.3 = %+v", r.IPs[2])
	}
}

func TestMergeDisplayFailures(t *testing.T) {
	r := mergeDisplay([]displayNode{
		{name: "b", key: "gone.com", via: "dns"},
		{name: "a", key: "gone.com", via: "dns"},
		{name: "v6", via: "server"}, // an address that is not IPv4: nothing to show, not a failure
		{name: "h", key: "six.com", via: "hosts"},
	})

	if len(r.IPs) != 0 || len(r.Fails) != 2 {
		t.Fatalf("result = %+v", r)
	}

	if f := r.Fails[0]; f.Name != "gone.com" || f.Reason != "failed" || !reflect.DeepEqual(f.Nodes, []string{"a", "b"}) {
		t.Fatalf("fail 0 = %+v", f)
	}

	if f := r.Fails[1]; f.Name != "six.com" || f.Detail == "" {
		t.Fatalf("fail 1 = %+v", f)
	}
}

func TestMergeDisplayIncludesIPv6(t *testing.T) {
	r := mergeDisplay([]displayNode{
		{name: "v6", via: "server", ips: []displayIP{{ip: "2001:db8::1", inUse: true}}},
		{name: "dual", key: "dual.example", via: "dns", ips: []displayIP{{ip: "2001:db8::2"}}},
	})

	if len(r.IPs) != 2 || r.IPs[0].IP != "2001:db8::1" || !r.IPs[0].InUse || r.IPs[1].IP != "2001:db8::2" {
		t.Fatalf("IPv6 rows = %+v", r.IPs)
	}
	if len(r.Fails) != 0 {
		t.Fatalf("IPv6 rows created failures: %+v", r.Fails)
	}
}
