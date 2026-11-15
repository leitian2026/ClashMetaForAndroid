// Package issuecheck finds problems of the running configuration by asking (CUSTOM.md #53, not upstream).
//
// The core logs a failed single DNS server or a dead node address only at DEBUG level, so these two cannot be read
// from the log; they are probed. A probe is one short round (a DNS question to each server of the profile's dns:
// section, one TCP connect to each literal node address) and it runs only when asked: after a profile was loaded
// and after the network changed (Trigger). There is no timer. A failure is checked once more a few seconds later
// before it is reported, and nothing is reported when the network itself is down.
package issuecheck

import (
	"context"
	"net"
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
	dnsProbe  = func() conftest.DNSReport { return conftest.RunDNS(probeDomain) }
	dialProbe = func(addr string) error {
		ctx, cancel := context.WithTimeout(context.Background(), dialTimeout)
		defer cancel()

		c, err := dialer.DialContext(ctx, "tcp", addr)
		if err == nil {
			_ = c.Close()
		}

		return err
	}
	sleep = time.Sleep
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
	rep := dnsProbe()

	for _, s := range rep.Servers {
		if s.Kind == "unsupported" || s.Via != "" || s.OK {
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

type target struct{ node, addr, hostport string }

func targets() []target {
	var out []target

	raws := ipdelay.RawProxies()
	names := make([]string, 0, len(raws))

	for n := range raws {
		names = append(names, n)
	}

	sort.Strings(names)

	for _, name := range names {
		raw := raws[name]

		if t, _ := raw["type"].(string); udpTypes[strings.ToLower(t)] {
			continue
		}

		if _, ok := raw["dialer-proxy"]; ok {
			continue
		}

		var port string

		switch v := raw["port"].(type) {
		case int:
			port = strconv.Itoa(v)
		case int64:
			port = strconv.FormatInt(v, 10)
		case uint64:
			port = strconv.FormatUint(v, 10)
		case float64:
			port = strconv.Itoa(int(v))
		case string:
			port = v
		}

		if port == "" {
			continue
		}

		addrs := ipdelay.NodeAddresses(name)
		if len(addrs) > maxPerNode {
			addrs = addrs[:maxPerNode]
		}

		for _, a := range addrs {
			out = append(out, target{name, a, net.JoinHostPort(a, port)})
		}
	}

	if len(out) > maxTotal {
		out = out[:maxTotal]
	}

	return out
}

type probed struct {
	t   target
	err error
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
			defer func() { _ = recover() }()

			res[i] = probed{t, dialProbe(t.hostport)}
		}(i, t)
	}

	wg.Wait()

	return res
}

func failed(res []probed) []probed {
	var out []probed

	for _, r := range res {
		if r.err == nil {
			continue
		}

		// no route / no IPv6 on this network: the network, not the node
		if m := strings.ToLower(r.err.Error()); strings.Contains(m, "network is unreachable") || strings.Contains(m, "no route to host") {
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
		issues.Report(issues.KindIP, b.t.hostport, errlog.Classify(b.err.Error()), b.err.Error(), b.t.node)
	}

	issues.ResolveExcept(issues.KindIP, keep)
}
