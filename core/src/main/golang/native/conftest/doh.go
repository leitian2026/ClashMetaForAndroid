package conftest

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/metacubex/mihomo/component/dialer"
)

// Public DoH servers used after the ones written in the profile did not answer.
var fallbackDoH = []string{
	"https://223.5.5.5/dns-query",
	"https://1.12.12.12/dns-query",
	"https://1.1.1.1/dns-query",
}

const (
	typeHTTPS   = 65
	paramECH    = 5 // SvcParamKey "ech"
	dohTimeout  = 5 * time.Second
	maxDNSReply = 64 * 1024
)

// buildHTTPSQuery makes the wire form of "HTTPS record of name, recursion desired".
func buildHTTPSQuery(name string) ([]byte, error) {
	name = strings.TrimSuffix(name, ".")
	if name == "" || len(name) > 253 {
		return nil, errors.New("bad name")
	}

	b := []byte{0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0} // id 0, RD, one question

	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 {
			return nil, errors.New("bad name")
		}

		b = append(b, byte(len(label)))
		b = append(b, label...)
	}

	b = append(b, 0, 0, typeHTTPS, 0, 1) // type HTTPS, class IN

	return b, nil
}

// skipName returns the offset after the (possibly compressed) name at off.
func skipName(msg []byte, off int) (int, error) {
	for hops := 0; hops < 128; hops++ {
		if off >= len(msg) {
			return 0, errors.New("short name")
		}

		n := int(msg[off])

		switch {
		case n == 0:
			return off + 1, nil
		case n&0xC0 == 0xC0:
			if off+2 > len(msg) {
				return 0, errors.New("short pointer")
			}

			return off + 2, nil
		case n&0xC0 != 0:
			return 0, errors.New("bad label")
		default:
			off += 1 + n
		}
	}

	return 0, errors.New("name too long")
}

// parseECH finds the ECHConfigList in the HTTPS answers of a DNS reply.
func parseECH(msg []byte) ([]byte, error) {
	if len(msg) < 12 {
		return nil, errors.New("short reply")
	}

	if rcode := msg[3] & 0x0F; rcode != 0 {
		return nil, fmt.Errorf("DNS error code %d (no such domain or refused)", rcode)
	}

	qd := int(binary.BigEndian.Uint16(msg[4:]))
	an := int(binary.BigEndian.Uint16(msg[6:]))
	off := 12

	var err error

	for i := 0; i < qd; i++ {
		if off, err = skipName(msg, off); err != nil {
			return nil, err
		}

		off += 4
	}

	for i := 0; i < an; i++ {
		if off, err = skipName(msg, off); err != nil {
			return nil, err
		}

		if off+10 > len(msg) {
			return nil, errors.New("short record")
		}

		rtype := binary.BigEndian.Uint16(msg[off:])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8:]))
		off += 10

		if off+rdlen > len(msg) {
			return nil, errors.New("short record data")
		}

		rdata := msg[off : off+rdlen]
		off += rdlen

		if rtype != typeHTTPS || len(rdata) < 3 {
			continue
		}

		// priority (2), target name (not compressed), then key / length / value
		p, err := skipName(rdata, 2)
		if err != nil {
			continue
		}

		for p+4 <= len(rdata) {
			key := binary.BigEndian.Uint16(rdata[p:])
			l := int(binary.BigEndian.Uint16(rdata[p+2:]))
			p += 4

			if p+l > len(rdata) {
				break
			}

			if key == paramECH && l > 0 {
				return append([]byte(nil), rdata[p:p+l]...), nil
			}

			p += l
		}
	}

	return nil, errors.New("the HTTPS record has no ech parameter (or there is no HTTPS record)")
}

// fetchECH asks DoH servers (the profile's own first) for the ECH config of name. It returns the config and
// the server that answered; the error lists why each server failed.
func fetchECH(ctx context.Context, name string, servers []string) ([]byte, string, error) {
	query, err := buildHTTPSQuery(name)
	if err != nil {
		return nil, "", err
	}

	all := append(append([]string(nil), servers...), fallbackDoH...)

	var reasons []string

	for _, server := range all {
		cfg, err := dohQuery(ctx, server, query)
		if err == nil {
			return cfg, server, nil
		}

		reasons = append(reasons, server+": "+err.Error())
	}

	return nil, "", errors.New(strings.Join(reasons, "; "))
}

func dohQuery(ctx context.Context, server string, query []byte) ([]byte, error) {
	body, _, err := dohExchange(ctx, server, query)
	if err != nil {
		return nil, err
	}

	return parseECH(body)
}

// dohExchange sends one DNS message to a DoH server and returns the raw reply and its Content-Type (CUSTOM.md
// #25: the DNS check reads the answer records, the ECH lookup reads the HTTPS record).
func dohExchange(ctx context.Context, server string, query []byte) ([]byte, string, error) {
	return dohDo(ctx, server, query, false)
}

// dohExchangeLikeCore asks the way the core's own DoH client does (component/dialer... dns/doh.go in mihomo
// 3f27ef39): GET ?dns=<base64url without padding>, message id 0, Accept: application/dns-message, empty
// User-Agent. A server or Worker that only works for this shape works for the core and for nobody else, so the
// DNS check must use it, not POST.
func dohExchangeLikeCore(ctx context.Context, server string, query []byte) ([]byte, string, error) {
	return dohDo(ctx, server, query, true)
}

func dohDo(ctx context.Context, server string, query []byte, like bool) ([]byte, string, error) {
	ctx, cancel := context.WithTimeout(ctx, dohTimeout)
	defer cancel()

	client := &http.Client{
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return dialer.DialContext(ctx, network, addr) // the socket does not go through the VPN
			},
			TLSClientConfig:   &tls.Config{MinVersion: tls.VersionTLS12},
			ForceAttemptHTTP2: true,
		},
	}
	defer client.CloseIdleConnections()

	var req *http.Request

	var err error

	if like {
		u, perr := url.Parse(server)
		if perr != nil {
			return nil, "", perr
		}

		q := append([]byte(nil), query...)
		if len(q) >= 2 {
			q[0], q[1] = 0, 0
		}

		u.RawQuery = "dns=" + base64.RawURLEncoding.EncodeToString(q)

		req, err = http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
		if err != nil {
			return nil, "", err
		}

		req.Header.Set("Accept", "application/dns-message")
		req.Header.Set("User-Agent", "")
	} else {
		req, err = http.NewRequestWithContext(ctx, http.MethodPost, server, bytes.NewReader(query))
		if err != nil {
			return nil, "", err
		}

		req.Header.Set("Content-Type", "application/dns-message")
		req.Header.Set("Accept", "application/dns-message")
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, "", err
	}
	defer resp.Body.Close()

	contentType := resp.Header.Get("Content-Type")

	if resp.StatusCode != http.StatusOK {
		return nil, contentType, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, maxDNSReply))
	if err != nil {
		return nil, contentType, err
	}

	return body, contentType, nil
}
