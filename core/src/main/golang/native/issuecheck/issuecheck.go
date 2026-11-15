// Package issuecheck finds problems of the running configuration by asking (CUSTOM.md #53, not upstream).
//
// The core logs a failed single DNS server or a dead node address only at DEBUG level, so these two cannot be read
// from the log; they are probed. A probe is one short round (a DNS question to each server of the profile's dns:
// section, through the proxy group when the profile sends that server through one; one TCP connect to each literal
// address of a TCP node, one latency request through the node itself for the others: UDP types, nodes behind a
// dialer-proxy, nodes written with a domain) and it runs only when asked: after a profile was loaded
// and after the network changed (Trigger). There is no timer. A failure is checked once more a few seconds later
// before it is reported, and nothing is reported when the network itself is down.
package issuecheck

import (
	"context"
	"net"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/conftest"
	"cfa/native/errlog"
	"cfa/native/ipdelay"
	"cfa/native/issues"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	settle      = 3 * time.Second // after the trigger: let the new config / network settle
	recheck     = 8 * time.Second
	dialTimeout = 3 * time.Second
	maxPerNode  = 8
	maxTotal    = 40
	parallel    = 8
	probeDomain = "www.cloudflare.com"
)

var (
	running int32
	pending int32

	// the test replaces these
	dnsProbe  = func() []conftest.DNSServer { return conftest.ProbeIssues(probeDomain) }
	dialProbe = func(addr string) error {
		ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
		defer cancel()

		c, err := dialer.DialContext(ctx, "tcp", addr)
		if err == nil {
			_ = c.Close()
		}

		return err
	}
	nodeProbe = func(node, server string, isDomain bool) string {
		return ipdelay.ProbeNode(context.Background(), node, server, isDomain)
	}
	sleep = time.Sleep

	rawProxies = ipdelay.RawProxies
	nodeAddrs  = ipdelay.NodeAddresses
)

// udpTypes are the node types that do not use TCP to the server, a TCP connect says nothing about them.
var udpTypes = map[string]bool{"hysteria": true, "hysteria2": true, "tuic": true, "wireguard": true, "masque": true, "trusttunnel": true}

// Trigger runs one round soon. Calls while a round runs make exactly one more round after it.
func Trigger() {
	if !atomic.CompareAndSwapInt32(&running, 0, 1) {
		atomic.StoreInt32(&pending, 1)
		return
	}

	go func() {
		for {
			func() {
				defer func() { _ = recover() }()

				sleep(settle)
				round()
			}()

			atomic.StoreInt32(&running, 0)

			if atomic.SwapInt32(&pending, 0) == 0 || !atomic.CompareAndSwapInt32(&running, 0, 1) {
				return
			}
		}
	}()
}

func round() {
	if offline() {
		return
	}

	checkDNS()
	checkNodes()
}

func offline() bool {
	return dialProbe("1.1.1.1:443") != nil && dialProbe("223.5.5.5:443") != nil
}

func failedServers() map[string]conftest.DNSServer {
	out := map[string]conftest.DNSServer{}

	for _, s := range dnsProbe() {
		if s.Kind == "unsupported" || s.OK {
			continue
		}

		out[s.Address] = s
	}

	return out
}

func checkDNS() {
	first := failedServers()
	if len(first) > 0 {
		sleep(recheck)

		first = failedServers() // reported only when it fails twice
	}

	keep := map[string]bool{}

	for addr, s := range first {
		keep[addr] = true
		code := s.Code
		reason := s.Reason

		if code == "" {
			code = errlog.Classify(reason)
		}

		issues.Report(issues.KindDoH, addr, code, reason, s.Kind)
	}

	issues.ResolveExcept(issues.KindDoH, keep)
}

type target struct {
	node, hostport string
	addr           string // the address (or domain) tested
	viaNode        bool   // tested through the node (latency request) instead of a TCP connect
	isDomain       bool
}

func portOf(raw map[string]any) string {
	switch v := raw["port"].(type) {
	case int:
		return strconv.Itoa(v)
	case int64:
		return strconv.FormatInt(v, 10)
	case uint64:
		return strconv.FormatUint(v, 10)
	case float64:
		return strconv.Itoa(int(v))
	case string:
		return v
	}

	return ""
}

func targets() []target {
	var out []target

	raws := rawProxies()
	names := make([]string, 0, len(raws))

	for n := range raws {
		names = append(names, n)
	}

	sort.Strings(names)

	for _, name := range names {
		raw := raws[name]
		port := portOf(raw)

		typ, _ := raw["type"].(string)
		_, hasDialer := raw["dialer-proxy"]
		addrs := nodeAddrs(name)

		if len(addrs) > maxPerNode {
			addrs = addrs[:maxPerNode]
		}

		withPort := func(a string) string {
			if port == "" {
				return a
			}

			return net.JoinHostPort(a, port)
		}

		switch {
		case !udpTypes[strings.ToLower(typ)] && !hasDialer && len(addrs) > 0 && port != "":
			for _, a := range addrs { // a plain TCP node with literal addresses: one connect each
				out = append(out, target{node: name, addr: a, hostport: withPort(a)})
			}
		case len(addrs) > 0: // UDP type / behind a dialer-proxy: a latency request through the node, per address
			for _, a := range addrs {
				out = append(out, target{node: name, addr: a, hostport: withPort(a), viaNode: true})
			}
		default: // the server is a domain the profile gives no addresses for: test it as written
			server, _ := raw["server"].(string)
			if server == "" {
				continue
			}

			_, perr := netip.ParseAddr(server)
			out = append(out, target{node: name, addr: server, hostport: withPort(server), viaNode: true, isDomain: perr != nil})
		}
	}

	if len(out) > maxTotal {
		out = out[:maxTotal]
	}

	return out
}

type probed struct {
	t   target
	msg string // "" works, "skip" not judged, else the failure
}

func probeOne(t target) string {
	if t.viaNode {
		return nodeProbe(t.node, t.addr, t.isDomain)
	}

	if err := dialProbe(t.hostport); err != nil {
		return err.Error()
	}

	return ""
}

func probeAll(ts []target) []probed {
	res := make([]probed, len(ts))
	sem := make(chan struct{}, parallel)

	var wg sync.WaitGroup

	for i, t := range ts {
		wg.Add(1)

		sem <- struct{}{}

		go func(i int, t target) {
			defer wg.Done()
			defer func() { <-sem }()
			defer func() {
				if recover() != nil {
					res[i] = probed{t, "skip"}
				}
			}()

			res[i] = probed{t, probeOne(t)}
		}(i, t)
	}

	wg.Wait()

	return res
}

func failed(res []probed) []probed {
	var out []probed

	for _, r := range res {
		if r.msg == "" || r.msg == "skip" {
			continue
		}

		// no route / no IPv6 on this network: the network, not the node
		if m := strings.ToLower(r.msg); strings.Contains(m, "network is unreachable") || strings.Contains(m, "no route to host") {
			continue
		}

		out = append(out, r)
	}

	return out
}

func checkNodes() {
	ts := targets()
	if len(ts) == 0 {
		issues.ResolveExcept(issues.KindIP, map[string]bool{})
		return
	}

	bad := failed(probeAll(ts))
	if len(bad) > 0 {
		sleep(recheck)

		again := make([]target, len(bad))
		for i, b := range bad {
			again[i] = b.t
		}

		bad = failed(probeAll(again)) // reported only when it fails twice
	}

	keep := map[string]bool{}

	for _, b := range bad {
		keep[b.t.hostport] = true
		issues.Report(issues.KindIP, b.t.hostport, errlog.Classify(b.msg), b.msg, b.t.node)
	}

	issues.ResolveExcept(issues.KindIP, keep)
}
