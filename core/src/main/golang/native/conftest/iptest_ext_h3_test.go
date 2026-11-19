package conftest

import "testing"

func TestHTTP3UDPAddress(t *testing.T) {
	for _, test := range []struct {
		ip   string
		want string
	}{
		{ip: "1.2.3.4", want: "1.2.3.4:443"},
		{ip: "2001:db8::1", want: "[2001:db8::1]:443"},
	} {
		remote, udp, err := http3UDPAddress(test.ip, "443")
		if err != nil {
			t.Fatalf("http3UDPAddress(%q): %v", test.ip, err)
		}
		if got := remote.String(); got != test.want {
			t.Errorf("remote address = %q, want %q", got, test.want)
		}
		if got := udp.String(); got != test.want {
			t.Errorf("UDP address = %q, want %q", got, test.want)
		}
	}
}

func TestHTTP3UDPAddressRejectsInvalidInput(t *testing.T) {
	if _, _, err := http3UDPAddress("not-an-ip", "443"); err == nil {
		t.Fatal("expected invalid IP error")
	}
	if _, _, err := http3UDPAddress("1.2.3.4", "70000"); err == nil {
		t.Fatal("expected invalid port error")
	}
}
