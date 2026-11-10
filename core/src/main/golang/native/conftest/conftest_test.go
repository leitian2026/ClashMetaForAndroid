package conftest

import "testing"

func TestVerdict(t *testing.T) {
	ok := Attempt{OK: true}
	acc := Attempt{OK: true, Accepted: true}
	bad := Attempt{Code: "reset"}
	rej := Attempt{Code: "echrejected"}

	cases := []struct {
		e, p Attempt
		want string
	}{
		{acc, bad, VerdictEffective},
		{acc, ok, VerdictAccepted},
		{rej, bad, VerdictRejected},
		{bad, ok, VerdictFailed},
		{bad, bad, VerdictUnknown},
		{ok, ok, VerdictUnknown}, // connected but ECH not accepted: never "effective"
	}

	for i, c := range cases {
		if got := verdict(c.e, c.p); got != c.want {
			t.Errorf("case %d: got %s want %s", i, got, c.want)
		}
	}
}

func TestDecodeConfig(t *testing.T) {
	if _, err := decodeConfig(""); err == nil {
		t.Fatal("empty must fail")
	}
	if b, err := decodeConfig("AAEC"); err != nil || len(b) != 3 {
		t.Fatalf("std: %v %v", b, err)
	}
	if b, err := decodeConfig("AAE"); err != nil || len(b) != 2 {
		t.Fatalf("raw: %v %v", b, err)
	}
}

func TestToInt(t *testing.T) {
	if toInt(443) != 443 || toInt(443.0) != 443 || toInt("443") != 443 || toInt(nil) != 0 {
		t.Fatal("toInt")
	}
}
