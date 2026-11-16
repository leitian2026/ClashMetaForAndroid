package conftest

// CUSTOM (CUSTOM.md #68, not upstream): small helpers for the "抖动" and "分段耗时" columns of the config check page.
// Pure functions; nothing here talks to the network.

// meanOf is the rounded average of ms, 0 when there is nothing (= not measured).
func meanOf(ms []int) int {
	if len(ms) == 0 {
		return 0
	}

	sum := 0

	for _, v := range ms {
		sum += v
	}

	return (sum + len(ms)/2) / len(ms)
}

// addPhases adds the parts of one try to the three lists; a part that is 0 (not measured in this try) is left out, so it
// does not pull the average of the other tries down.
func addPhases(dials, shakes, firsts []int, dial, shake, first int) ([]int, []int, []int) {
	if dial > 0 {
		dials = append(dials, dial)
	}

	if shake > 0 {
		shakes = append(shakes, shake)
	}

	if first > 0 {
		firsts = append(firsts, first)
	}

	return dials, shakes, firsts
}
