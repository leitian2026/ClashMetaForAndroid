package conftest

// CUSTOM (CUSTOM.md #53, not upstream): the DNS part of the runtime problem check (package issuecheck). Like RunDNS it
// asks every server of the profile's `dns:` section, but it only asks (no hosts / fake-ip / listen analysis) and it
// reaches a server that the profile sends through a proxy group (`#name`) the way the core does: through that group.
// A server whose `#name` is no proxy or group of the profile (an interface name, say) and a UDP server behind a proxy
// are not asked: there is no honest way to ask them from here, so they are not judged.
//
// Nothing here runs on its own; one call asks every server once.

import (
	"context"
	"net"
	"net/netip"
	"strconv"
	"sync"

	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/component/dialer"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

// ProbeIssues asks each distinct server of the profile for `domain` once and returns the ones that were asked.
func ProbeIssues(domain string) []DNSServer {
	cfg := parseDNSConfig(ipdelay.DNSTree())

	var specs []serverSpec

	seen := map[string]bool{}

	for _, ref := range cfg.Servers {
		if seen[ref.Address] {
			continue
		}

		seen[ref.Address] = true

		spec := parseServer(ref.Address)
		if spec.Kind == kindUnsupported {
			continue
		}

		if spec.Via != "" {
			px := tunnel.Proxies()[spec.Via]
			if px == nil || spec.Kind == kindUDP {
				continue
			}

			spec.Dial = viaDial(px)
		}

		specs = append(specs, spec)
	}

	out := make([]DNSServer, len(specs))

	var wg sync.WaitGroup

	sem := make(chan struct{}, dnsParallel)

	for i, spec := range specs {
		wg.Add(1)
		sem <- struct{}{}

		go func(i int, spec serverSpec) {
			defer wg.Done()
			defer func() { <-sem }()
			defer func() { _ = recover() }()

			dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, addr) // straight out, not through the VPN
			}
			if spec.Dial != nil {
				dial = spec.Dial
			}

			ipdelay.WithSelfDial(func() { // our own sockets are not real traffic
				out[i] = probeWith(context.Background(), spec, domain, nil, dial)
			})
		}(i, spec)
	}

	wg.Wait()

	return out
}

func viaDial(px C.Proxy) dialFunc {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(addr)
		if err != nil {
			return nil, err
		}

		n, err := strconv.ParseUint(port, 10, 16)
		if err != nil {
			return nil, err
		}

		md := &C.Metadata{DstPort: uint16(n)}

		if ip, perr := netip.ParseAddr(host); perr == nil {
			md.DstIP = ip.Unmap()
		} else {
			md.Host = host
		}

		return px.DialContext(ctx, md)
	}
}
