package conftest

// CUSTOM (CUSTOM.md #43, not upstream): the parts of "test other IPs" that need nothing from the core (reading
// the typed list, ordering the rows, reading the data centre from a header), kept apart so that they can be
// tested alone.

import (
	"net/netip"
	"sort"
	"strings"
)

// maxIPTest is the most addresses one run tests; the rest of the list is counted, not tested.
const maxIPTest = 20

// parseIPList reads what the user typed: addresses separated by line breaks, commas, semicolons, spaces or tabs
// (also the full-width ones). `1.2.3.4:443` and `[2606::1]:443` are read as their address. Duplicates are
// dropped. Words that are not an address come back in `invalid`, in the order written.
func parseIPList(text string) (ips []netip.Addr, invalid []string) {
	fields := strings.FieldsFunc(text, func(r rune) bool {
		switch r {
		case '\n', '\r', '\t', ' ', ',', ';', '，', '；', '、', '　':
			return true
		}

		return false
	})

	seen := map[netip.Addr]bool{}

	for _, f := range fields {
		var addr netip.Addr

		if ap, err := netip.ParseAddrPort(f); err == nil {
			addr = ap.Addr()
		} else if a, err := netip.ParseAddr(strings.Trim(f, "[]")); err == nil {
			addr = a
		} else {
			invalid = append(invalid, f)

			continue
		}

		addr = addr.Unmap()

		if addr.Zone() != "" {
			invalid = append(invalid, f)

			continue
		}

		if !seen[addr] {
			seen[addr] = true

			ips = append(ips, addr)
		}
	}

	return ips, invalid
}

// sortRows puts the addresses that work first, quickest first; the ones that failed keep the order they were typed.
func sortRows(rows []IPRow) {
	sort.SliceStable(rows, func(i, j int) bool {
		a, b := rows[i], rows[j]

		if a.OK != b.OK {
			return a.OK
		}

		if !a.OK {
			return false
		}

		return a.Ms < b.Ms
	})
}

// coloOf reads the data centre from a Cloudflare `Cf-Ray` header ("8a1b2c3d4e5f6a7b-HKG" -> "HKG"); "" when the
// header is not of that shape.
func coloOf(ray string) string {
	i := strings.LastIndexByte(ray, '-')
	if i < 0 {
		return ""
	}

	code := ray[i+1:]
	if len(code) < 3 || len(code) > 4 {
		return ""
	}

	for _, c := range code {
		if c < 'A' || c > 'Z' {
			return ""
		}
	}

	return code
}

// asnText joins an AS number and its organisation ("AS13335", "Cloudflare, Inc." -> "AS13335 Cloudflare, Inc.").
func asnText(asn, org string) string {
	asn, org = strings.TrimSpace(asn), strings.TrimSpace(org)

	if asn != "" && !strings.HasPrefix(strings.ToUpper(asn), "AS") {
		asn = "AS" + asn
	}

	return strings.TrimSpace(asn + " " + org)
}
