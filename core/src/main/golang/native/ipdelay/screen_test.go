package ipdelay

import "testing"

func TestPendingScreenWorkWaitsUntilInteractive(t *testing.T) {
	var pending pendingScreenWork

	pending.markReach()
	pending.markReach()
	pending.markNotify()
	pending.markNotify()

	if reach, notify := pending.take(false); reach || notify {
		t.Fatalf("screen-off take = (%v, %v), want (false, false)", reach, notify)
	}
	if !pending.reach || !pending.notify {
		t.Fatal("screen-off take cleared pending work")
	}

	reach, notify := pending.take(true)
	if !reach || !notify {
		t.Fatalf("screen-on take = (%v, %v), want (true, true)", reach, notify)
	}

	reach, notify = pending.take(true)
	if reach || notify {
		t.Fatalf("second take = (%v, %v), want (false, false)", reach, notify)
	}
}

func TestPendingScreenWorkCanBeClearedIndependently(t *testing.T) {
	var pending pendingScreenWork
	pending.markReach()
	pending.markNotify()

	pending.reach = false
	reach, notify := pending.take(true)
	if reach || !notify {
		t.Fatalf("take after clearing reach = (%v, %v), want (false, true)", reach, notify)
	}
}
