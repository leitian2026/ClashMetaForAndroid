package ipdelay

import (
	"reflect"
	"testing"
)

func TestOnlyIPv6(t *testing.T) {
	got := onlyIPv6([]string{
		"2001:db8::1",
		"192.0.2.1",
		"2001:0db8:0:0:0:0:0:1",
		"::ffff:192.0.2.1",
		"not-an-ip",
		"2001:db8::2",
	})
	want := []string{"2001:db8::1", "2001:db8::2"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("onlyIPv6() = %v, want %v", got, want)
	}
}

func TestPickDisplayIPv6PrioritizesInUseAndCapsAtFour(t *testing.T) {
	all := []string{"2001:db8::1", "2001:db8::2", "2001:db8::3", "2001:db8::4", "2001:db8::5"}
	inUse := []string{"2001:db8::5", "2001:db8::2"}
	got := pickDisplayIPv6(all, inUse)
	want := []string{"2001:db8::5", "2001:db8::2", "2001:db8::1", "2001:db8::3"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("pickDisplayIPv6() = %v, want %v", got, want)
	}
}

func TestPickDisplayIPv6EmptyAndDuplicateInputs(t *testing.T) {
	if got := pickDisplayIPv6([]string{"192.0.2.1", "bad"}, nil); len(got) != 0 {
		t.Fatalf("IPv4-only input returned %v", got)
	}

	got := pickDisplayIPv6(
		[]string{"2001:db8::1", "2001:db8::1", "2001:db8::2"},
		[]string{"2001:db8::2", "2001:db8::2"},
	)
	want := []string{"2001:db8::2", "2001:db8::1"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("pickDisplayIPv6() = %v, want %v", got, want)
	}
}
