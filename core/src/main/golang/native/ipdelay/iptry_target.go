package ipdelay

// CUSTOM (CUSTOM.md #66, not part of upstream): the "target address" of the through-node test. One call is one request
// to the address the user typed (a site they really use), through node `name` with its `server` replaced by the tested
// address, like TryIP. Unlike TryIP it keeps what the site answered (status, redirect, Cloudflare's `Cf-Ray` and
// `Server`), so the page can show the reaction of the site itself. Nothing here runs on its own; one call is one request.

import (
	"strings"
	"sync/atomic"
	"time"
)

// TargetResult is the outcome of TryTarget. Err is "" when the site answered at all (whatever the status).
type TargetResult struct {
	Delay            int
	Status           int
	Location         string
	CfRay            string
	Server           string
	TLS              string
	ALPN             string
	CertSubject      string
	CertIssuer       string
	CertNotBefore    string
	CertNotAfter     string
	CertDNSNames     string
	CertIPAddresses  string
	CertVerification string
	ContentType      string
	RetryAfter       string
	CfMitigated      string
	Soft             string
	SoftNote         string
	Err              string
	Ph               Phases
}

// TryTarget does one GET through the selected node. Redirects are not followed. TLS verification is skipped because
// the Android system CA store is not available inside the core; no secret is sent.
func TryTarget(name, server string, isDomain bool, rawURL string) TargetResult {
	lock.RLock()
	raw := proxies[name]
	lock.RUnlock()
	if raw == nil {
		return TargetResult{Err: "node not found"}
	}
	if isDomain {
		raw = copyNodeForDomain(raw, name)
	}

	atomic.AddInt32(&selfDialing, 1)
	defer atomic.AddInt32(&selfDialing, -1)

	px := newVariant(raw, server)
	if px == nil {
		return TargetResult{Err: "cannot build the node with this server"}
	}
	defer closeProxy(px)

	result, err := requestSnapshotVia(px, rawURL, testTimeout, sniffMax)
	if err != nil {
		return TargetResult{Err: err.Error()}
	}

	delay := int(result.Elapsed.Milliseconds())
	if delay <= 0 {
		delay = 1
	}
	res := TargetResult{
		Delay:            delay,
		Status:           result.Status,
		Location:         strings.TrimSpace(result.Header.Get("Location")),
		CfRay:            strings.TrimSpace(result.Header.Get("Cf-Ray")),
		Server:           strings.TrimSpace(result.Header.Get("Server")),
		TLS:              result.Ph.TLS,
		ALPN:             result.Ph.ALPN,
		CertVerification: "skipped",
		ContentType:      strings.TrimSpace(result.Header.Get("Content-Type")),
		RetryAfter:       strings.TrimSpace(result.Header.Get("Retry-After")),
		CfMitigated:      strings.TrimSpace(result.Header.Get("CF-Mitigated")),
		Ph:               result.Ph,
	}
	if len(result.State.PeerCertificates) > 0 {
		cert := result.State.PeerCertificates[0]
		res.CertSubject = cert.Subject.String()
		res.CertIssuer = cert.Issuer.String()
		res.CertNotBefore = cert.NotBefore.UTC().Format(time.RFC3339)
		res.CertNotAfter = cert.NotAfter.UTC().Format(time.RFC3339)
		res.CertDNSNames = strings.Join(cert.DNSNames, ", ")
		ips := make([]string, 0, len(cert.IPAddresses))
		for _, ip := range cert.IPAddresses {
			ips = append(ips, ip.String())
		}
		res.CertIPAddresses = strings.Join(ips, ", ")
	}
	if len(result.Body) > 0 {
		res.Soft, res.SoftNote = SniffPage(result.Body)
	}
	return res
}
