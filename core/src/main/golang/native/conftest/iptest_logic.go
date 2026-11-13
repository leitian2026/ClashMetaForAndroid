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

// target is one entry of the typed list: an address (Text is the address as netip writes it) or a domain (Text is
// the name in lower case, without the final dot).
type target struct {
	Text     string
	IsDomain bool
}

// parseTargets reads what the user typed: addresses and domains separated by line breaks, commas, semicolons,
// spaces or tabs (also the full-width ones). `1.1.1.1:443`, `[2606::1]:443` and `example.com:443` are read as their
// address or name (the port is not used). Duplicates are dropped. Words that are neither come back in `invalid`, in
// the order written.
func parseTargets(text string) (list []target, invalid []string) {
	fields := strings.FieldsFunc(text, func(r rune) bool {
		switch r {
		case '\n', '\r', '\t', ' ', ',', ';', '，', '；', '、', '\u3000':
			return true
		}

		return false
	})

	seen := map[string]bool{}

	add := func(t target) {
		if !seen[t.Text] {
			seen[t.Text] = true

			list = append(list, t)
		}
	}

	for _, f := range fields {
		var addr netip.Addr

		if ap, err := netip.ParseAddrPort(f); err == nil {
			addr = ap.Addr()
		} else if a, err := netip.ParseAddr(strings.Trim(f, "[]")); err == nil {
			addr = a
		} else if name, ok := domainOf(f); ok {
			add(target{Text: name, IsDomain: true})

			continue
		} else {
			invalid = append(invalid, f)

			continue
		}

		addr = addr.Unmap()

		if addr.Zone() != "" {
			invalid = append(invalid, f)

			continue
		}

		add(target{Text: addr.String()})
	}

	return list, invalid
}

// domainOf reads a host name: at least two labels of letters, digits and hyphens (a hyphen is not first or last),
// the last label with a letter in it (so `1.2.3` and `300.1.1.1` are not names). A final dot and a `:port` are
// dropped. Names with other characters (internationalised ones must be written in punycode) are not accepted.
func domainOf(word string) (string, bool) {
	if i := strings.LastIndexByte(word, ':'); i > 0 {
		port := word[i+1:]

		if port != "" && strings.Trim(port, "0123456789") == "" {
			word = word[:i]
		}
	}

	name := strings.ToLower(strings.TrimSuffix(word, "."))

	if len(name) == 0 || len(name) > 253 {
		return "", false
	}

	labels := strings.Split(name, ".")
	if len(labels) < 2 {
		return "", false
	}

	for _, l := range labels {
		if len(l) == 0 || len(l) > 63 || l[0] == '-' || l[len(l)-1] == '-' {
			return "", false
		}

		for _, c := range l {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return "", false
			}
		}
	}

	last := labels[len(labels)-1]
	if strings.Trim(last, "0123456789-") == "" {
		return "", false
	}

	return name, true
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
