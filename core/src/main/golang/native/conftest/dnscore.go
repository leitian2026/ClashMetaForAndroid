package conftest

// CUSTOM (CUSTOM.md #26, not upstream): the parts of the DNS check that talk to the core itself: asking the core's
// own resolver (the same call as the controller's /dns/query, hub/route/dns.go) and clearing its caches (the same
// calls as POST /cache/dns/flush and /cache/fakeip/flush, hub/route/cache.go). Checked against mihomo commit
// 3f27ef39f8c96bbfbc8b0b9bb10f5c9ef60149d8.
//
// Power: nothing here runs on its own; a question is one tap, a flush is one tap.

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"cfa/native/errlog"

	"github.com/metacubex/mihomo/component/resolver"
	D "github.com/miekg/dns"
)

// Ids that closeCustomConnection takes to clear the caches (see connections.go).
const (
	FlushDNSID    = "!flush-dns"
	FlushFakeIPID = "!flush-fakeip"
)

// askCore asks the core's resolver for A and AAAA, both at once. It never panics: a core without a loaded
// profile has no resolver, which shows as Asked == false.
func askCore(ctx context.Context, domain string) (out CoreAnswer) {
	out = CoreAnswer{A: []string{}, AAAA: []string{}, Matches: []string{}}

	defer func() {
		if r := recover(); r != nil {
			out.OK = false
			out.Reason = fmt.Sprint(r)
		}
	}()

	res := resolver.DefaultResolver
	if res == nil {
		return out
	}

	out.Asked = true

	type reply struct {
		resp *D.Msg
		err  error
		ms   int
	}

	var replies [2]reply

	var wg sync.WaitGroup

	for i, qtype := range []uint16{D.TypeA, D.TypeAAAA} {
		wg.Add(1)

		go func(i int, qtype uint16) {
			defer wg.Done()

			defer func() {
				if r := recover(); r != nil {
					replies[i].err = fmt.Errorf("%v", r)
				}
			}()

			msg := new(D.Msg)
			msg.SetQuestion(D.Fqdn(domain), qtype)

			qctx, cancel := context.WithTimeout(ctx, resolver.DefaultDNSTimeout)
			defer cancel()

			start := time.Now()
			resp, err := res.ExchangeContext(qctx, msg)

			replies[i] = reply{resp: resp, err: err, ms: elapsed(start)}
		}(i, qtype)
	}

	wg.Wait()

	var firstErr error

	for i, r := range replies {
		if r.err != nil || r.resp == nil {
			if firstErr == nil {
				firstErr = r.err
				if firstErr == nil {
					firstErr = errors.New("no answer")
				}
			}

			continue
		}

		if r.resp.Rcode != 0 && r.resp.Rcode != 3 {
			if firstErr == nil {
				firstErr = fmt.Errorf("%s", rcodeName(r.resp.Rcode))
			}

			continue
		}

		if !out.OK || r.ms < out.Ms {
			out.Ms = r.ms
		}

		out.OK = true
		out.Rcode = r.resp.Rcode
		out.Rname = rcodeName(r.resp.Rcode)

		for _, rr := range r.resp.Answer {
			switch v := rr.(type) {
			case *D.A:
				if i == 0 {
					out.A = append(out.A, v.A.String())
				}
			case *D.AAAA:
				if i == 1 {
					out.AAAA = append(out.AAAA, v.AAAA.String())
				}
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

// FlushDNS clears the core's DNS cache. The core does it in the background, so this returns at once.
func FlushDNS() (ok bool) {
	defer func() {
		if r := recover(); r != nil {
			ok = false
		}
	}()

	resolver.ClearCache()

	return true
}

// FlushFakeIP forgets which fake address stands for which domain (and clears the DNS cache too, whose answers go
// with it). Apps that still hold an old fake address get a wrong or no mapping afterwards, so the caller closes
// the open connections as well.
func FlushFakeIP() (ok bool) {
	defer func() {
		if r := recover(); r != nil {
			ok = false
		}
	}()

	if err := resolver.FlushFakeIP(); err != nil {
		return false
	}

	resolver.ClearCache()

	return true
}
