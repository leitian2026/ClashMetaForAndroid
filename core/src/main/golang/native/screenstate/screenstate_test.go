package screenstate

import "testing"

func TestInteractiveAndListeners(t *testing.T) {
	if !Interactive() {
		t.Fatal("screen state should default to interactive")
	}

	var got []bool
	RegisterListener(func(value bool) {
		got = append(got, value)
	})

	SetInteractive(false)
	if Interactive() {
		t.Fatal("screen state should be off after SetInteractive(false)")
	}

	SetInteractive(true)
	if !Interactive() {
		t.Fatal("screen state should be on after SetInteractive(true)")
	}

	if len(got) != 2 || got[0] || !got[1] {
		t.Fatalf("listener values = %v, want [false true]", got)
	}
}
