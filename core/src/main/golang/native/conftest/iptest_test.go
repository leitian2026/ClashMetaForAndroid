package conftest

import (
	"net/netip"
	"reflect"
	"testing"
)

func ips(list ...string) []target {
	out := make([]target, len(list))
	for i, s := range list {
		out[i] = target{Text: netip.MustParseAddr(s).String()}
	}

	return out
}

func TestParseTargets(t *testing.T) {
	dom := func(names ...string) []target {
		out := make([]target, len(names))
		for i, n := range names {
			out[i] = target{Text: n, IsDomain: true}
		}

		return out
	}

	cases := []struct {
		in      string
		want    []target
		invalid []string
	}{
		{"1.1.1.1\n2.2.2.2", ips("1.1.1.1", "2.2.2.2"), nil},
		{"1.1.1.1, 2.2.2.2;3.3.3.3  4.4.4.4\t5.5.5.5", ips("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4", "5.5.5.5"), nil},
		{"1.1.1.1，2.2.2.2、3.3.3.3；4.4.4.4", ips("1.1.1.1", "2.2.2.2", "3.3.3.3", "4.4.4.4"), nil},
		{"1.1.1.1:443 [2606:4700::1]:443 [2606:4700::2] 2606:4700::3", ips("1.1.1.1", "2606:4700::1", "2606:4700::2", "2606:4700::3"), nil},
		{"1.1.1.1 1.1.1.1 ::ffff:1.1.1.1", ips("1.1.1.1"), nil},
		{"abc 1.1.1.1 300.1.1.1 fe80::1%eth0 1.2.3", ips("1.1.1.1"), []string{"abc", "300.1.1.1", "fe80::1%eth0", "1.2.3"}},
		{"  \n\r\n ", nil, nil},

		// domains
		{"example.com", dom("example.com"), nil},
		{"Example.COM. example.com geng-xin.uf0.workers.dev", dom("example.com", "geng-xin.uf0.workers.dev"), nil},
		{"example.com:8443 1.1.1.1 b.example.org", append(append(dom("example.com"), ips("1.1.1.1")...), dom("b.example.org")...), nil},
		{"localhost a_b.com -a.com a-.com a..com 1.2.3.4:abc 2606:4700::zz 例子.com", nil, []string{"localhost", "a_b.com", "-a.com", "a-.com", "a..com", "1.2.3.4:abc", "2606:4700::zz", "例子.com"}},
	}

	for i, c := range cases {
		got, invalid := parseTargets(c.in)
		if !reflect.DeepEqual(got, c.want) || !reflect.DeepEqual(invalid, c.invalid) {
			t.Errorf("case %d: got %v %v, want %v %v", i, got, invalid, c.want, c.invalid)
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

func TestIsCloudflare(t *testing.T) {
	cases := []struct {
		ray, server string
		want        bool
	}{
		{"8a1b2c3d4e5f6a7b-HKG", "", true},
		{"", "cloudflare", true},
		{"", " Cloudflare ", true},
		{"", "nginx", false},
		{"", "", false},
	}

	for _, c := range cases {
		if got := isCloudflare(c.ray, c.server); got != c.want {
			t.Errorf("isCloudflare(%q, %q) = %v, want %v", c.ray, c.server, got, c.want)
		}
	}
}
