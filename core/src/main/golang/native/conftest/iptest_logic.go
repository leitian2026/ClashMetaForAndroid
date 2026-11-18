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

// parseTargets reads only IP addresses separated by line breaks, commas, semicolons, spaces or tabs
// (also the full-width ones). An IPv4 ":port" or a bracketed IPv6 ":port" suffix is discarded; the port is
// never returned or used. Domains and all non-IP words are invalid.
func parseTargets(text string) (list []target, invalid []string) {
	// CUSTOM.md #156 step 28: an inline "#label" suffix belongs to the address on that line,
	// not to the input tokens. Strip it before splitting so labels may contain spaces (e.g. "联通-FRA 2").
	lines := strings.Split(text, "\n")
	for i, line := range lines {
		if comment := strings.IndexByte(line, '#'); comment >= 0 {
			lines[i] = line[:comment]
		}
	}
	text = strings.Join(lines, "\n")

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
		ipText := f
		if strings.HasPrefix(ipText, "[") {
			if close := strings.IndexByte(ipText, ']'); close > 0 {
				ipText = ipText[1:close]
			}
		} else if strings.Count(ipText, ":") == 1 {
			ipText = ipText[:strings.LastIndexByte(ipText, ':')]
		}
		addr, err := netip.ParseAddr(ipText)
		if err != nil {
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

// isCloudflare says whether the answer of a server shows Cloudflare: it has a `Cf-Ray` header, or `Server: cloudflare`.
// Only a positive sign: a server that did not answer, or answered without either, is not called "not Cloudflare".
func isCloudflare(ray, server string) bool {
	return strings.TrimSpace(ray) != "" || strings.EqualFold(strings.TrimSpace(server), "cloudflare")
}

// asnText joins an AS number and its organisation ("AS13335", "Cloudflare, Inc." -> "AS13335 Cloudflare, Inc.").
func asnText(asn, org string) string {
	asn, org = strings.TrimSpace(asn), strings.TrimSpace(org)

	if asn != "" && !strings.HasPrefix(strings.ToUpper(asn), "AS") {
		asn = "AS" + asn
	}

	return strings.TrimSpace(asn + " " + org)
}
