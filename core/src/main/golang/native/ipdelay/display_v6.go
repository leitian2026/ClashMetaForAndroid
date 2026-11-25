package ipdelay

import "net/netip"

const maxDisplayIPv6 = 4

// onlyIPv6 keeps valid, canonical IPv6 addresses and removes duplicates.
func onlyIPv6(values []string) []string {
	out := make([]string, 0, len(values))
	seen := make(map[string]bool, len(values))
	for _, value := range values {
		addr, err := netip.ParseAddr(value)
		if err != nil || !addr.Is6() || addr.Is4In6() {
			continue
		}
		value = addr.String()
		if seen[value] {
			continue
		}
		seen[value] = true
		out = append(out, value)
	}
	return out
}

// pickDisplayIPv6 reserves a separate display quota for IPv6 and puts addresses
// currently in use first. The order within each input list remains stable.
func pickDisplayIPv6(all, inUse []string) []string {
	candidates := onlyIPv6(all)
	if len(candidates) == 0 {
		return nil
	}

	available := make(map[string]bool, len(candidates))
	for _, ip := range candidates {
		available[ip] = true
	}

	out := make([]string, 0, maxDisplayIPv6)
	appendUnique := func(values []string) {
		for _, ip := range onlyIPv6(values) {
			if available[ip] && len(out) < maxDisplayIPv6 {
				out = append(out, ip)
				delete(available, ip)
			}
		}
	}

	appendUnique(inUse)
	appendUnique(candidates)
	return out
}
