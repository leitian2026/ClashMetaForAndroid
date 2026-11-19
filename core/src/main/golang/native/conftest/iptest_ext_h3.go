// CUSTOM (CUSTOM.md #79, not part of upstream): one direct HTTP/3 request per selected IP.
package conftest

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"net/url"
	"strconv"
	"strings"
	"time"

	"cfa/native/errlog"

	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/quic-go"
	"github.com/metacubex/quic-go/http3"
	qtls "github.com/metacubex/tls"
)

func http3UDPAddress(ip, port string) (netip.AddrPort, *net.UDPAddr, error) {
	address, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return netip.AddrPort{}, nil, fmt.Errorf("invalid IP address: %w", err)
	}
	address = address.Unmap()
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return netip.AddrPort{}, nil, fmt.Errorf("invalid UDP port %q", port)
	}
	remote := netip.AddrPortFrom(address, uint16(portNumber))
	return remote, net.UDPAddrFromAddrPort(remote), nil
}

func probeHTTP3(parent context.Context, ip, domain string) IPExtResult {
	result := IPExtResult{Tested: true}
	domain = strings.TrimSpace(domain)
	if domain == "" {
		return http3Failure(result, 0, fmt.Errorf("no domain"))
	}

	remoteAddrPort, remoteAddr, err := http3UDPAddress(ip, ipPort)
	if err != nil {
		return http3Failure(result, 0, err)
	}

	ctx, cancel := context.WithTimeout(parent, probeTimeout)
	defer cancel()
	started := time.Now()
	elapsed := func() int {
		ms := int(time.Since(started).Milliseconds())
		if ms < 1 {
			return 1
		}
		return ms
	}

	// ListenPacket applies mihomo's socket hook, so the QUIC socket follows the same direct
	// (outside-the-VPN) route as the existing TCP IP probes.
	network := "udp4"
	if remoteAddrPort.Addr().Is6() {
		network = "udp6"
	}
	packetConn, err := dialer.ListenPacket(ctx, network, "", remoteAddrPort)
	if err != nil {
		return http3Failure(result, elapsed(), err)
	}
	defer packetConn.Close()

	tlsConfig := &qtls.Config{
		ServerName:         domain,
		InsecureSkipVerify: true,
		NextProtos:         []string{http3.NextProtoH3},
	}
	quicConn, err := quic.Dial(ctx, packetConn, remoteAddr, tlsConfig, &quic.Config{
		HandshakeIdleTimeout: probeTimeout,
		MaxIdleTimeout:       probeTimeout,
		MaxIncomingStreams:   -1,
	})
	if err != nil {
		return http3Failure(result, elapsed(), err)
	}

	handshakeMs := elapsed()
	clientConn := (&http3.Transport{}).NewClientConn(quicConn)
	defer clientConn.CloseWithError(0, "")

	requestURL := (&url.URL{Scheme: "https", Host: domain, Path: "/"}).String()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, requestURL, nil)
	if err != nil {
		return http3Failure(result, handshakeMs, err)
	}

	request.Header.Set("User-Agent", "curl/8.5.0")
	response, err := clientConn.RoundTrip(request)
	if err != nil {
		return http3Failure(result, handshakeMs, err)
	}
	defer response.Body.Close()

	// Any valid HTTP/3 response proves that the protocol was negotiated. An HTTP 4xx/5xx
	// is still a successful protocol check; preserve its status for diagnosis.
	result.Ms = handshakeMs
	result.Status = response.StatusCode
	if response.ProtoMajor != 3 {
		result.Reason = fmt.Sprintf("unexpected response protocol: %s", response.Proto)
		result.Code = errlog.Classify(result.Reason)
		return result
	}
	result.OK = true
	details := []string{}
	if response.Proto != "" {
		details = append(details, response.Proto)
	}
	if server := response.Header.Get("Server"); server != "" {
		details = append(details, "Server: "+server)
	}
	if mitigated := response.Header.Get("Cf-Mitigated"); mitigated != "" {
		details = append(details, "Cf-Mitigated: "+mitigated)
	}
	if ray := response.Header.Get("Cf-Ray"); ray != "" {
		details = append(details, "Cf-Ray: "+ray)
	}
	result.Detail = strings.Join(details, " · ")
	return result
}

func http3Failure(result IPExtResult, ms int, err error) IPExtResult {
	result.Ms = ms
	result.Reason = err.Error()
	result.Code = errlog.Classify(result.Reason)
	return result
}
