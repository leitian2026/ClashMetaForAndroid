package conftest

// CUSTOM (CUSTOM.md #25, not upstream): the DNS check of the config check page. It reads only the `dns:` and
// `hosts:` sections of the profile (never the rest) and, once per tap, really asks every DNS server written in
// `dns:` for the A and AAAA records of a domain the user typed. For that domain it then says whether a `hosts:`
// entry answers it, whether the core would hand out a fake-ip, and, when `dns.listen` is set, what the core's own
// DNS server really answers.
//
// The servers are asked directly, with the core's dialer (like the ECH lookup): a server the core only reaches
// through a proxy group (`#name`) is still asked directly here, and the report says so.
//
// Power: nothing here runs on its own; one call is one test run.

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/rand"
	"net"
	"strings"
	"sync"
	"time"

	"cfa/native/errlog"
	"cfa/native/ipdelay"

	"github.com/metacubex/mihomo/component/dialer"
)

const (
	dnsTimeout  = 5 * time.Second
	dnsParallel = 6
)

// DNSServer is the result for one server address. Roles lists the sections it is written in.
type DNSServer struct {
	Address string   `json:"address"`
	Roles   []string `json:"roles"`
	Kind    string   `json:"kind"`   // doh, dot, tcp, udp, unsupported
	Scheme  string   `json:"scheme"` // of an unsupported server
	Via     string   `json:"via"`    // the core reaches it through this proxy group; the check asked it directly
	H3      bool     `json:"h3"`     // written with h3=true; the check used HTTP/2
	OK      bool     `json:"ok"`     // it answered with NOERROR or NXDOMAIN
	Ms      int      `json:"ms"`
	Rcode   int      `json:"rcode"`
	Rname   string   `json:"rname"`
	A       []string `json:"a"`    // IPv4 answers
	AAAA    []string `json:"aaaa"` // IPv6 answers
	Fake    bool     `json:"fake"` // an answer lies in fake-ip-range
	Code    string   `json:"code"` // errlog codes, "" when OK
	Reason  string   `json:"reason"`
}

// DNSReport is the answer of RunDNS.
type DNSReport struct {
	Domain       string      `json:"domain"`
	Mode         string      `json:"mode"` // enhanced-mode as written
	FilterMode   string      `json:"filterMode"`
	FakeRange    string      `json:"fakeRange"` // fake-ip-range as written
	Listen       string      `json:"listen"`    // dns.listen as written
	HostsKey     string      `json:"hostsKey"`  // the hosts: entry that covers the domain
	HostsValues  []string    `json:"hostsValues"`
	FilterHit    string      `json:"filterHit"`   // the fake-ip-filter entry that covers the domain
	FilterUnk    string      `json:"filterUnk"`   // a fake-ip-filter entry the check cannot judge
	Final        string      `json:"final"`       // hosts, fakeip, real, unknown: from the profile alone
	Kernel       *DNSServer  `json:"kernel"`      // the core's own DNS server (dns.listen), when it answered
	KernelFinal  string      `json:"kernelFinal"` // fakeip or real, from what it answered; "" without an answer
	Servers      []DNSServer `json:"servers"`
	NoServerNote bool        `json:"noServerNote"` // the dns: section lists no server at all

	FakeRange6   string `json:"fakeRange6"`   // fake-ip-range6 as written
	UseHosts     bool   `json:"useHosts"`     // dns.use-hosts: the DNS server answers from hosts:
	IPv6         bool   `json:"ipv6"`         // dns.ipv6
	RespectRules bool   `json:"respectRules"` // dns.respect-rules
	AliasTo      string `json:"aliasTo"`      // a hosts entry points the domain at this other domain, which is what the rest looks at
	AAAA         string `json:"aaaa"`         // what the core answers an AAAA question: fake6, nopool6, ipv6off or "" (real / hosts)

	// CUSTOM.md #26
	Verdict DNSVerdict  `json:"verdict"` // the answers of all servers read together
	Policy  PolicyMatch `json:"policy"`  // the nameserver-policy entry that covers the domain
	Core    CoreAnswer  `json:"core"`    // what the core's own resolver answers
}

// RunAny runs the check the request asks for: the DNS check for kind "dns", the ECH check otherwise.
func RunAny(filter string) any {
	var req Request
	_ = json.Unmarshal([]byte(filter), &req)

	if req.Kind == "dns" {
		return RunDNS(strings.TrimSpace(req.Domain))
	}

	return Run(filter)
}

// RunDNS asks every DNS server of the profile about the domain. It blocks until all of them answered or timed out.
func RunDNS(domain string) DNSReport {
	domain = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(domain), "."))

	cfg := parseDNSConfig(ipdelay.DNSTree())
	rep := DNSReport{
		Domain:       domain,
		Mode:         cfg.Mode,
		FakeRange:    cfg.FakeRange,
		FakeRange6:   cfg.FakeRange6,
		FilterMode:   cfg.FilterMode,
		Listen:       cfg.Listen,
		UseHosts:     cfg.UseHosts,
		IPv6:         cfg.IPv6,
		RespectRules: cfg.RespectRules,
		HostsValues:  []string{},
		Servers:      []DNSServer{},
	}

	// The core's DNS server looks in hosts: first (only when dns.use-hosts is true), like Hosts.Search in
	// component/resolver/host.go: an entry whose single value is not an IP is an alias, and the search goes on
	// with that name. The chain ends in addresses (hosts answers) or at a name that is not in hosts: (the core then
	// looks that name up the normal way, so everything below is about that name).
	target := domain
	hostsAnswers := false

	if cfg.UseHosts {
		hosts := ipdelay.RawHosts()
		seen := map[string]bool{domain: true}

		for hops := 0; hops < 8; hops++ {
			key, values := matchHosts(hosts, target)
			if key == "" {
				break // not in hosts
			}

			if hops == 0 || !hostsAnswers {
				rep.HostsKey, rep.HostsValues = key, values
			}

			alias, isAlias := hostsAlias(values)
			if !isAlias {
				rep.HostsKey, rep.HostsValues = key, values
				hostsAnswers = true

				break
			}

			if seen[alias] {
				break // alias loop
			}

			seen[alias] = true
			target = alias
		}

		if target != domain {
			rep.AliasTo = target
		}
	}

	hostsKey := ""
	if hostsAnswers {
		hostsKey = rep.HostsKey
	}

	rep.FilterHit, rep.FilterUnk = filterMatch(cfg.Filter, target)

	// CUSTOM.md #26: which nameserver-policy entry covers the name the servers are asked about
	var policies []policyEntry

	for _, e := range ipdelay.PolicyEntries() {
		policies = append(policies, policyEntry{Key: e.Key, Servers: e.Servers})
	}

	rep.Policy = matchPolicy(policies, target)
	rep.Final = inferFinal(cfg, hostsKey, rep.FilterHit, rep.FilterUnk)

	switch {
	case rep.Final == finalFakeIP && cfg.FakeRange6 == "":
		rep.AAAA = "nopool6" // fake-ip without an IPv6 pool: AAAA gets an empty answer
	case rep.Final == finalFakeIP:
		rep.AAAA = "fake6"
	case rep.Final == finalReal && !cfg.IPv6:
		rep.AAAA = "ipv6off" // dns.ipv6 is false: AAAA gets an empty answer
	}

	// one entry per distinct address, with every section it is written in
	type entry struct {
		spec  serverSpec
		roles []string
	}

	var order []string

	entries := map[string]*entry{}

	for _, ref := range cfg.Servers {
		e, found := entries[ref.Address]
		if !found {
			e = &entry{spec: parseServer(ref.Address)}
			entries[ref.Address] = e
			order = append(order, ref.Address)
		}

		dup := false

		for _, r := range e.roles {
			dup = dup || r == ref.Role
		}

		if !dup {
			e.roles = append(e.roles, ref.Role)
		}
	}

	rep.NoServerNote = len(order) == 0

	results := make([]DNSServer, len(order))

	var wg sync.WaitGroup

	sem := make(chan struct{}, dnsParallel)

	for i, addr := range order {
		wg.Add(1)
		sem <- struct{}{}

		go func(i int, e *entry) {
			defer wg.Done()
			defer func() { <-sem }()

			results[i] = probeServer(context.Background(), e.spec, target, fakeRanges(cfg))
			results[i].Roles = e.roles
		}(i, entries[addr])
	}

	// CUSTOM.md #26: what the core's own resolver answers (the controller's /dns/query)
	wg.Add(1)

	go func() {
		defer wg.Done()

		rep.Core = askCore(context.Background(), target)
	}()

	// the core's own DNS server: what an app really gets (hosts, fake-ip and all)
	if port := listenPort(cfg.Listen); port != "" {
		wg.Add(1)

		go func() {
			defer wg.Done()

			spec := serverSpec{Raw: cfg.Listen, Kind: kindUDP, HostPort: net.JoinHostPort("127.0.0.1", port), Host: "127.0.0.1"}
			kernel := probeWith(context.Background(), spec, domain, fakeRanges(cfg), func(ctx context.Context, network, addr string) (net.Conn, error) {
				var d net.Dialer

				return d.DialContext(ctx, network, addr) // loopback: no need for the core's dialer
			})
			kernel.Roles = []string{"dns.listen"}

			if kernel.OK {
				rep.Kernel = &kernel

				if kernel.Fake {
					rep.KernelFinal = finalFakeIP
				} else if len(kernel.A)+len(kernel.AAAA) > 0 {
					rep.KernelFinal = finalReal
				}
			}
		}()
	}

	wg.Wait()

	rep.Servers = append(rep.Servers, results...)

	// CUSTOM.md #26
	rep.Verdict = analyzeVerdict(results, target)
	rep.Core.Matches = coreMatches(rep.Core, results)

	return rep
}

// fakeRanges are the fake-ip pools (IPv4 and IPv6) as written, without the empty ones.
func fakeRanges(cfg dnsConfig) []string {
	var out []string

	for _, r := range []string{cfg.FakeRange, cfg.FakeRange6} {
		if r != "" {
			out = append(out, r)
		}
	}

	return out
}

// inAnyPrefix tells whether the address lies in one of the CIDRs.
func inAnyPrefix(addr string, prefixes []string) bool {
	for _, p := range prefixes {
		if inPrefix(addr, p) {
			return true
		}
	}

	return false
}

// listenPort returns the port of `dns.listen` ("0.0.0.0:1053", ":1053", "[::]:1053"); "" when there is none.
func listenPort(listen string) string {
	listen = strings.TrimSpace(listen)
	if listen == "" {
		return ""
	}

	_, port, err := net.SplitHostPort(listen)
	if err != nil || port == "" || port == "0" {
		return ""
	}

	return port
}

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

func probeServer(ctx context.Context, spec serverSpec, domain string, fakeRanges []string) DNSServer {
	return probeWith(ctx, spec, domain, fakeRanges, func(ctx context.Context, network, addr string) (net.Conn, error) {
		return dialer.DialContext(ctx, network, addr) // the socket does not go through the VPN
	})
}

type exchangeResult struct {
	ans dnsAnswer
	ms  int
	err error
}

// probeWith asks one server for A and AAAA (both at once).
func probeWith(ctx context.Context, spec serverSpec, domain string, fakeRanges []string, dial dialFunc) DNSServer {
	out := DNSServer{
		Address: spec.Raw,
		Kind:    spec.Kind,
		Scheme:  spec.Scheme,
		Via:     spec.Via,
		H3:      spec.H3,
		A:       []string{},
		AAAA:    []string{},
		Roles:   []string{},
	}

	if spec.Kind == kindUnsupported {
		out.Code = "unsupported"

		return out
	}

	var wg sync.WaitGroup

	var res [2]exchangeResult

	for i, qtype := range []uint16{typeA, typeAAAA} {
		wg.Add(1)

		go func(i int, qtype uint16) {
			defer wg.Done()

			res[i] = ask(ctx, spec, domain, qtype, dial)
		}(i, qtype)
	}

	wg.Wait()

	// a server counts as reachable when at least one of the two questions got a proper answer
	var firstErr error

	for i := range res {
		r := res[i]

		if r.err != nil {
			if firstErr == nil {
				firstErr = r.err
			}

			continue
		}

		if r.ans.Rcode != 0 && r.ans.Rcode != 3 {
			if firstErr == nil {
				firstErr = fmt.Errorf("%s", rcodeName(r.ans.Rcode))
			}

			continue
		}

		if !out.OK || r.ms < out.Ms {
			out.Ms = r.ms
		}

		out.OK = true
		out.Rcode = r.ans.Rcode
		out.Rname = rcodeName(r.ans.Rcode)

		for _, a := range r.ans.Addrs {
			if i == 0 {
				out.A = append(out.A, a)
			} else {
				out.AAAA = append(out.AAAA, a)
			}

			if inAnyPrefix(a, fakeRanges) {
				out.Fake = true
			}
		}
	}

	if !out.OK && firstErr != nil {
		out.Reason = firstErr.Error()
		out.Code = errlog.Classify(out.Reason)

		if strings.Contains(out.Reason, "REFUSED") || strings.Contains(out.Reason, "SERVFAIL") ||
			strings.Contains(out.Reason, "FORMERR") || strings.Contains(out.Reason, "NOTIMP") ||
			strings.Contains(out.Reason, "RCODE") {
			out.Code = "rcode"
		}
	}

	return out
}

func ask(ctx context.Context, spec serverSpec, domain string, qtype uint16, dial dialFunc) exchangeResult {
	id := uint16(rand.Intn(65536))
	if spec.Kind == kindDoH {
		id = 0 // the core sends DoH queries with id 0 (RFC 8484)
	}

	query, err := buildQuery(domain, qtype, id)
	if err != nil {
		return exchangeResult{err: err}
	}

	ctx, cancel := context.WithTimeout(ctx, dnsTimeout)
	defer cancel()

	start := time.Now()

	var msg []byte

	switch spec.Kind {
	case kindDoH:
		var contentType string

		msg, contentType, err = dohExchangeLikeCore(ctx, spec.URL, query)
		if err == nil {
			ans, perr := parseReply(msg, id)
			if perr != nil {
				// a Worker that answers JSON, HTML or something else instead of a DNS message: say what it sent
				perr = fmt.Errorf("%s (Content-Type: %s, %s)", perr.Error(), orNone(contentType), preview(msg))
			}

			return exchangeResult{ans: ans, ms: elapsed(start), err: perr}
		}
	case kindUDP:
		msg, err = udpExchange(ctx, dial, spec.HostPort, query)
		if err == nil {
			ans, perr := parseReply(msg, id)
			if perr == nil && ans.Truncated {
				msg, err = streamExchange(ctx, dial, spec.HostPort, query, nil) // too big for UDP: ask again over TCP
				if err == nil {
					ans, perr = parseReply(msg, id)
				}
			}

			if err == nil {
				return exchangeResult{ans: ans, ms: elapsed(start), err: perr}
			}
		}
	case kindTCP:
		msg, err = streamExchange(ctx, dial, spec.HostPort, query, nil)
	case kindDoT:
		msg, err = streamExchange(ctx, dial, spec.HostPort, query, &tls.Config{ServerName: spec.Host, MinVersion: tls.VersionTLS12})
	default:
		err = errors.New("unsupported server type")
	}

	if err != nil {
		return exchangeResult{err: err, ms: elapsed(start)}
	}

	ans, perr := parseReply(msg, id)

	return exchangeResult{ans: ans, ms: elapsed(start), err: perr}
}

func elapsed(start time.Time) int {
	ms := int(time.Since(start) / time.Millisecond)
	if ms < 1 {
		ms = 1
	}

	return ms
}

func orNone(s string) string {
	if s == "" {
		return "none"
	}

	return s
}

// preview shows the start of a body that is not a DNS message, printable characters only.
func preview(body []byte) string {
	const max = 60

	if len(body) > max {
		body = body[:max]
	}

	var b strings.Builder

	for _, c := range body {
		if c >= 0x20 && c < 0x7f {
			b.WriteByte(c)
		} else {
			b.WriteByte('.')
		}
	}

	return b.String()
}

func udpExchange(ctx context.Context, dial dialFunc, addr string, query []byte) ([]byte, error) {
	conn, err := dial(ctx, "udp", addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	if _, err := conn.Write(query); err != nil {
		return nil, err
	}

	buf := make([]byte, 4096)

	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}

	return buf[:n], nil
}

// streamExchange sends the message with the 2-byte length prefix of DNS over TCP, inside TLS when tlsCfg is set.
func streamExchange(ctx context.Context, dial dialFunc, addr string, query []byte, tlsCfg *tls.Config) ([]byte, error) {
	raw, err := dial(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	defer raw.Close()

	conn := raw

	if deadline, ok := ctx.Deadline(); ok {
		_ = raw.SetDeadline(deadline)
	}

	if tlsCfg != nil {
		tc := tls.Client(raw, tlsCfg)
		if err := tc.HandshakeContext(ctx); err != nil {
			return nil, err
		}

		conn = tc
	}

	frame := make([]byte, 2, 2+len(query))
	binary.BigEndian.PutUint16(frame, uint16(len(query)))

	if _, err := conn.Write(append(frame, query...)); err != nil {
		return nil, err
	}

	var head [2]byte

	if _, err := io.ReadFull(conn, head[:]); err != nil {
		return nil, err
	}

	size := int(binary.BigEndian.Uint16(head[:]))
	if size == 0 {
		return nil, errors.New("empty reply")
	}

	body := make([]byte, size)

	if _, err := io.ReadFull(conn, body); err != nil {
		return nil, err
	}

	return body, nil
}
