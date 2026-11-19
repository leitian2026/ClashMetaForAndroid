package conftest

import (
	"net/netip"
	"reflect"
	"strconv"
	"testing"
)

func TestParseResolveDomains(t *testing.T) {
	got, invalid := parseResolveDomains("Example.COM\nexample.com.，api.example.com;bad_domain;localhost;1.2.3.4")
	if want := []string{"example.com", "api.example.com"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("domains = %v, want %v", got, want)
	}
	if want := []string{"bad_domain", "localhost", "1.2.3.4"}; !reflect.DeepEqual(invalid, want) {
		t.Fatalf("invalid = %v, want %v", invalid, want)
	}
}

func TestNormalizeResolvedIPs(t *testing.T) {
	input := []netip.Addr{
		netip.MustParseAddr("2.2.2.2"),
		netip.MustParseAddr("1.1.1.1"),
		netip.MustParseAddr("198.18.0.1"),
		netip.MustParseAddr("2001:db8::1"),
		netip.MustParseAddr("1.1.1.1"),
	}
	got, omitted := normalizeResolvedIPs(input)
	if want := []string{"1.1.1.1", "2.2.2.2"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("ips = %v, want %v", got, want)
	}
	if omitted != 0 {
		t.Fatalf("omitted = %d, want 0", omitted)
	}
}

func TestNormalizeResolvedIPsLimit(t *testing.T) {
	input := make([]netip.Addr, 0, 10)
	for i := 10; i >= 1; i-- {
		input = append(input, netip.MustParseAddr("192.0.2."+strconv.Itoa(i)))
	}
	got, omitted := normalizeResolvedIPs(input)
	if len(got) != resolveDomainLimit || omitted != 2 {
		t.Fatalf("len=%d omitted=%d", len(got), omitted)
	}
	if got[0] != "192.0.2.1" || got[len(got)-1] != "192.0.2.8" {
		t.Fatalf("order = %v", got)
	}
}
