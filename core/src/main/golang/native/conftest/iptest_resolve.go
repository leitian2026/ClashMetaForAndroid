package conftest

// CUSTOM (CUSTOM.md #178, not upstream): explicit, user-triggered A- and AAAA-record lookup for the
// test-other-IPs dialog. It uses the running core resolver and never starts background work.

import (
	"context"
	"errors"
	"net/netip"
	"sort"
	"strings"
	"sync"
	"time"

	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/component/resolver"
	mihomoDNS "github.com/metacubex/mihomo/dns"
)

const (
	resolveDomainLimit     = 8
	resolveIPv6DomainLimit = 4
	resolveConcurrency     = 4
	resolveTotalLimit      = 10 * time.Second
)

var fakeIPPrefix = netip.MustParsePrefix("198.18.0.0/15")

// IPResolveResult is one domain's IPv4 and IPv6 answers in mode "resolve".
type IPResolveResult struct {
	Domain                 string   `json:"domain"`
	IPs                    []string `json:"ips"`
	IPsV6                  []string `json:"ipsV6"`
	Omitted                int      `json:"omitted"`
	Error                  string   `json:"error"`
	FromHosts              bool     `json:"fromHosts"`
	CacheBypassUnsupported bool     `json:"cacheBypassUnsupported"`
}

// parseResolveDomains splits, validates, normalizes and deduplicates the user's domain list.
func parseResolveDomains(text string) (domains, invalid []string) {
	seen := map[string]bool{}
	fields := strings.FieldsFunc(text, func(r rune) bool {
		switch r {
		case '\n', '\r', '\t', ' ', ',', ';', '，', '；', '、', '\u3000':
			return true
		}
		return false
	})
	for _, field := range fields {
		domain := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(field)), ".")
		if !validResolveDomain(domain) {
			invalid = append(invalid, field)
			continue
		}
		if !seen[domain] {
			seen[domain] = true
			domains = append(domains, domain)
		}
	}
	return domains, invalid
}

func validResolveDomain(domain string) bool {
	if len(domain) == 0 || len(domain) > 253 {
		return false
	}
	labels := strings.Split(domain, ".")
	if len(labels) < 2 {
		return false
	}
	lastHasLetter := false
	last := labels[len(labels)-1]
	for _, label := range labels {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for i := 0; i < len(label); i++ {
			c := label[i]
			if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' {
				if label == last && c >= 'a' && c <= 'z' {
					lastHasLetter = true
				}
				continue
			}
			return false
		}
	}
	return lastHasLetter
}

// normalizeResolvedIPs keeps usable IPv4 addresses, filters fake-ip, sorts and caps the result.
// It is pure so its ordering and filtering can be tested without a running core.
func normalizeResolvedIPs(input []netip.Addr) (ips []string, omitted int) {
	unique := make(map[netip.Addr]bool, len(input))
	addresses := make([]netip.Addr, 0, len(input))
	for _, address := range input {
		if !address.IsValid() {
			continue
		}
		address = address.Unmap()
		if !address.Is4() || fakeIPPrefix.Contains(address) || unique[address] {
			continue
		}
		unique[address] = true
		addresses = append(addresses, address)
	}
	sort.Slice(addresses, func(i, j int) bool { return addresses[i].Less(addresses[j]) })
	if len(addresses) > resolveDomainLimit {
		omitted = len(addresses) - resolveDomainLimit
		addresses = addresses[:resolveDomainLimit]
	}
	ips = make([]string, 0, len(addresses))
	for _, address := range addresses {
		ips = append(ips, address.String())
	}
	return ips, omitted
}

// normalizeResolvedIPv6 keeps usable IPv6 addresses, filters the profile's fake-ip pools,
// sorts, deduplicates and caps the result independently from IPv4.
func normalizeResolvedIPv6(input []netip.Addr, prefixes []string) []string {
	unique := make(map[netip.Addr]bool, len(input))
	addresses := make([]netip.Addr, 0, len(input))
	for _, address := range input {
		if !address.IsValid() || !address.Is6() || address.Is4In6() {
			continue
		}
		if inAnyPrefix(address.String(), prefixes) || unique[address] {
			continue
		}
		unique[address] = true
		addresses = append(addresses, address)
	}
	sort.Slice(addresses, func(i, j int) bool { return addresses[i].Less(addresses[j]) })
	if len(addresses) > resolveIPv6DomainLimit {
		addresses = addresses[:resolveIPv6DomainLimit]
	}
	ips := make([]string, 0, len(addresses))
	for _, address := range addresses {
		ips = append(ips, address.String())
	}
	return ips
}

func resolveOneDomain(ctx context.Context, domain string, hosts map[string]any, fakePrefixes []string) IPResolveResult {
	result := IPResolveResult{Domain: domain, IPs: []string{}, IPsV6: []string{}}
	var addresses4, addresses6 []netip.Addr

	if raw, found := hosts[domain]; found {
		result.FromHosts = true
		for _, value := range ipdelay.ToStrings(raw) {
			if address, err := netip.ParseAddr(strings.TrimSpace(value)); err == nil {
				if address.Is4() || address.Is4In6() {
					addresses4 = append(addresses4, address.Unmap())
				} else if address.Is6() {
					addresses6 = append(addresses6, address)
				}
			}
		}
		if len(addresses4)+len(addresses6) == 0 {
			result.Error = "hosts_no_ipv4"
			return result
		}
	} else {
		type familyResult struct {
			addresses               []netip.Addr
			err                     error
			cacheBypassUnsupported  bool
		}
		var ipv4, ipv6 familyResult
		var wg sync.WaitGroup
		wg.Add(2)

		go func() {
			defer wg.Done()
			ipv4.addresses, ipv4.err = mihomoDNS.LookupIPv4Fresh(ctx, domain)
			if errors.Is(ipv4.err, mihomoDNS.ErrFreshUnsupported) {
				ipv4.cacheBypassUnsupported = true
				ipv4.addresses, ipv4.err = resolver.LookupIPv4(ctx, domain)
			}
		}()
		go func() {
			defer wg.Done()
			ipv6.addresses, ipv6.err = mihomoDNS.LookupIPv6Fresh(ctx, domain)
			if errors.Is(ipv6.err, mihomoDNS.ErrFreshUnsupported) {
				ipv6.cacheBypassUnsupported = true
				ipv6.addresses, ipv6.err = resolver.LookupIPv6(ctx, domain)
			}
		}()
		wg.Wait()

		result.CacheBypassUnsupported = ipv4.cacheBypassUnsupported || ipv6.cacheBypassUnsupported
		addresses4, addresses6 = ipv4.addresses, ipv6.addresses
		if ctx.Err() != nil {
			result.Error = "timeout"
			return result
		}
		if len(addresses4)+len(addresses6) == 0 {
			switch {
			case ipv4.err != nil && !errors.Is(ipv4.err, resolver.ErrIPNotFound):
				result.Error = ipv4.err.Error()
			case ipv6.err != nil && !errors.Is(ipv6.err, resolver.ErrIPNotFound) &&
				!errors.Is(ipv6.err, resolver.ErrIPv6Disabled):
				result.Error = ipv6.err.Error()
			default:
				result.Error = "no_ipv4"
			}
			return result
		}
	}

	result.IPs, result.Omitted = normalizeResolvedIPs(addresses4)
	result.IPsV6 = normalizeResolvedIPv6(addresses6, fakePrefixes)
	if len(result.IPs)+len(result.IPsV6) == 0 {
		result.Error = "no_ipv4"
	}
	return result
}

func runResolve(req IPRequest, rep IPReport) IPReport {
	domains, invalid := parseResolveDomains(req.Domains)
	rep.Invalid = invalid
	rep.Resolved = make([]IPResolveResult, len(domains))
	if len(domains) == 0 {
		rep.Error = "no valid domains"
		return rep
	}

	base, done := beginRun()
	defer done()
	ctx, cancel := context.WithTimeout(base, resolveTotalLimit)
	defer cancel()

	hosts := ipdelay.RawHosts()
	fakePrefixes := fakeRanges(parseDNSConfig(ipdelay.DNSTree()))
	sem := make(chan struct{}, resolveConcurrency)
	var wg sync.WaitGroup
	for i, domain := range domains {
		wg.Add(1)
		go func(index int, name string) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				rep.Resolved[index] = IPResolveResult{Domain: name, IPs: []string{}, IPsV6: []string{}, Error: "timeout"}
				return
			}
			defer func() { <-sem }()
			rep.Resolved[index] = resolveOneDomain(ctx, name, hosts, fakePrefixes)
		}(i, domain)
	}
	wg.Wait()

	if errors.Is(ctx.Err(), context.Canceled) {
		rep.Cancelled = true
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		for i := range rep.Resolved {
			if rep.Resolved[i].Domain == "" {
				rep.Resolved[i] = IPResolveResult{Domain: domains[i], IPs: []string{}, IPsV6: []string{}, Error: "timeout"}
			}
		}
	}
	rep.At = time.Now().UnixMilli()
	return rep
}
