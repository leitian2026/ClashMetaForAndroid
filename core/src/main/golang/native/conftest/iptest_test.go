package conftest

import (
	"net/netip"
	"reflect"
	"testing"
)

func addrs(list ...string) []netip.Addr {
	out := make([]netip.Addr, len(list))
	for i, s := range list {
		out[i] = netip.MustParseAddr(s)
	}

	return out
}

func TestParseIPList(t *testing.T) {
	cases := []struct {
		in      string
		ips     []netip.Addr
		invalid []string
	}{
		{"1.1.1.1\n2.2.2.2", addrs("1.1.1.1", "2.2.2.2"), nil},
		{"1.1.1.1, 2.2.2.2;3.3.3.3  4.4.4.4\t5.5.5.5", addrs("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5"), nil},
		{"1.1.1.1，2.2.2.2、3.3.3.3；4.4.4.4", addrs("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"), nil},
		{"1.1.1.1:443 [2606:4700::1]:443 [2606:4700::2] 2606:4700::3", addrs("1.1.1.1", "2606:4700::1", "2606:4700::2", "2606:4700::3"), nil},
		{"1.1.1.1 1.1.1.1 ::ffff:1.1.1.1", addrs("1.1.1.1"), nil},
		{"abc 1.1.1.1 300.1.1.1 fe80::1%eth0 1.2.3", addrs("1.1.1.1"), []string{"abc", "300.1.1.1", "fe80::1%eth0", "1.2.3"}},
		{"  \n\r\n ", nil, nil},
	}

	for i, c := range cases {
		ips, invalid := parseIPList(c.in)
		if !reflect.DeepEqual(ips, c.ips) || !reflect.DeepEqual(invalid, c.invalid) {
			t.Errorf("case %d: got %v %v, want %v %v", i, ips, invalid, c.ips, c.invalid)
		}
	}
}

func TestSortRows(t *testing.T) {
	rows := []IPRow{
		{IP: "a", OK: false},
		{IP: "b", OK: true, Ms: 90},
		{IP: "c", OK: false},
		{IP: "d", OK: true, Ms: 40},
		{IP: "e", OK: true, Ms: 90},
	}

	sortRows(rows)

	got := ""
	for _, r := range rows {
		got += r.IP
	}

	// quickest first (ties keep the order typed), the failed ones last in the order typed
	if got != "dbeac" {
		t.Errorf("got %s", got)
	}
}

func TestColoOf(t *testing.T) {
	cases := map[string]string{
		"8a1b2c3d4e5f6a7b-HKG": "HKG",
		"8a1b2c3d4e5f6a7b-LAX": "LAX",
		"8a1b2c3d4e5f6a7b-hkg": "",
		"8a1b2c3d4e5f6a7b":     "",
		"":                     "",
		"abc-TOOLONG":          "",
		"abc-H1G":              "",
	}

	for in, want := range cases {
		if got := coloOf(in); got != want {
			t.Errorf("coloOf(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestAsnText(t *testing.T) {
	cases := [][3]string{
		{"13335", "Cloudflare, Inc.", "AS13335 Cloudflare, Inc."},
		{"AS13335", " Cloudflare ", "AS13335 Cloudflare"},
		{"", "Org only", "Org only"},
		{"13335", "", "AS13335"},
	}

	for _, c := range cases {
		if got := asnText(c[0], c[1]); got != c[2] {
			t.Errorf("asnText(%q,%q) = %q, want %q", c[0], c[1], got, c[2])
		}
	}
}
