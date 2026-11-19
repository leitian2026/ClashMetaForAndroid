// CUSTOM (CUSTOM.md #78, not part of upstream): one direct WebSocket HTTP/1.1 upgrade check per selected IP.
package conftest

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/sha1"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"cfa/native/errlog"

	"github.com/metacubex/mihomo/component/dialer"
)

const websocketAcceptGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

// ipExtEnabled reports whether one optional direct extension was requested.
func ipExtEnabled(raw, name string) bool {
	for _, item := range strings.Split(raw, ",") {
		if strings.EqualFold(strings.TrimSpace(item), name) {
			return true
		}
	}
	return false
}

func websocketAccept(key string) string {
	sum := sha1.Sum([]byte(key + websocketAcceptGUID))
	return base64.StdEncoding.EncodeToString(sum[:])
}

func probeWebSocket(parent context.Context, ip, domain, path string) IPExtResult {
	result := IPExtResult{Tested: true}
	path = strings.TrimSpace(path)
	if path == "" {
		path = "/"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	requestURL, err := url.ParseRequestURI(path)
	if err != nil || requestURL.IsAbs() || requestURL.Host != "" {
		result.Reason = "invalid WebSocket path"
		result.Code = errlog.Classify(result.Reason)
		return result
	}

	ctx, cancel := context.WithTimeout(parent, probeTimeout)
	defer cancel()
	started := time.Now()
	elapsed := func() int { return int(time.Since(started).Milliseconds()) }

	raw, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(ip, ipPort))
	if err != nil {
		return websocketFailure(result, elapsed(), err)
	}
	conn := tls.Client(raw, &tls.Config{
		ServerName:         domain,
		InsecureSkipVerify: true,
		MinVersion:         tls.VersionTLS12,
		NextProtos:         []string{"http/1.1"},
	})
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if err := conn.HandshakeContext(ctx); err != nil {
		return websocketFailure(result, elapsed(), err)
	}

	keyBytes := make([]byte, 16)
	if _, err := rand.Read(keyBytes); err != nil {
		return websocketFailure(result, elapsed(), err)
	}
	key := base64.StdEncoding.EncodeToString(keyBytes)
	request := &http.Request{
		Method: http.MethodGet,
		URL:    requestURL,
		Host:   domain,
		Header: make(http.Header),
	}
	request.Header.Set("Upgrade", "websocket")
	request.Header.Set("Connection", "Upgrade")
	request.Header.Set("Sec-WebSocket-Key", key)
	request.Header.Set("Sec-WebSocket-Version", "13")
	if err := request.Write(conn); err != nil {
		return websocketFailure(result, elapsed(), err)
	}

	response, err := http.ReadResponse(bufio.NewReader(conn), request)
	if err != nil {
		return websocketFailure(result, elapsed(), err)
	}
	result.Ms = elapsed()
	result.Status = response.StatusCode
	if response.StatusCode == http.StatusSwitchingProtocols {
		if response.Header.Get("Sec-WebSocket-Accept") == websocketAccept(key) {
			result.OK = true
			return result
		}
		result.Reason = "invalid Sec-WebSocket-Accept: " + response.Header.Get("Sec-WebSocket-Accept")
		return result
	}

	result.Reason = fmt.Sprintf("HTTP %d", response.StatusCode)
	var details []string
	if server := response.Header.Get("Server"); server != "" {
		details = append(details, "Server: "+server)
	}
	if mitigated := response.Header.Get("Cf-Mitigated"); mitigated != "" {
		details = append(details, "Cf-Mitigated: "+mitigated)
	}
	if len(details) > 0 {
		result.Reason += " · " + strings.Join(details, " · ")
	}
	result.Detail = strings.Join(details, " · ")
	return result
}

func websocketFailure(result IPExtResult, ms int, err error) IPExtResult {
	result.Ms = ms
	result.Reason = err.Error()
	result.Code = errlog.Classify(result.Reason)
	return result
}
