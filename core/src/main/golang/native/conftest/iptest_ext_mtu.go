// CUSTOM (CUSTOM.md #80, not part of upstream): direct TCP MSS and UDP QUIC-packet MTU diagnostics.
package conftest

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"time"

	"cfa/native/errlog"

	"github.com/metacubex/mihomo/component/dialer"
	"golang.org/x/sys/unix"
)

func readTCPMSS(conn *tls.Conn, ip string) (int, int) {
	if conn == nil {
		return 0, 0
	}
	tcp, ok := conn.NetConn().(*net.TCPConn)
	if !ok {
		return 0, 0
	}
	rawConn, err := tcp.SyscallConn()
	if err != nil {
		return 0, 0
	}

	var mss int
	var socketErr error
	if err := rawConn.Control(func(fd uintptr) {
		mss, socketErr = unix.GetsockoptInt(int(fd), unix.IPPROTO_TCP, unix.TCP_MAXSEG)
	}); err != nil || socketErr != nil || mss <= 0 {
		return 0, 0
	}

	address, err := netip.ParseAddr(ip)
	if err != nil {
		return mss, 0
	}
	address = address.Unmap()
	headerBytes := 40 // IPv4 header + TCP header.
	if address.Is6() {
		headerBytes = 60 // IPv6 header + TCP header.
	}
	return mss, mss + headerBytes
}

func appendMTUDetail(result *IPExtResult, line string) {
	if line == "" {
		return
	}
	if result.Detail != "" {
		result.Detail += "\n"
	}
	result.Detail += line
}

func setMTUFailure(result *IPExtResult, err error) {
	if err == nil {
		return
	}
	result.Reason = err.Error()
	result.Code = errlog.Classify(result.Reason)
}

func setMTUDontFragment(conn *net.UDPConn, ipv6 bool) error {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var socketErr error
	if err := rawConn.Control(func(fd uintptr) {
		level, option, expected := unix.IPPROTO_IP, unix.IP_MTU_DISCOVER, unix.IP_PMTUDISC_DO
		if ipv6 {
			level, option, expected = unix.IPPROTO_IPV6, unix.IPV6_MTU_DISCOVER, unix.IPV6_PMTUDISC_DO
		}
		if socketErr = unix.SetsockoptInt(int(fd), level, option, expected); socketErr != nil {
			return
		}
		var actual int
		actual, socketErr = unix.GetsockoptInt(int(fd), level, option)
		if socketErr == nil && actual != expected {
			socketErr = fmt.Errorf("DF socket option readback is %d, want %d", actual, expected)
		}
	}); err != nil {
		return err
	}
	return socketErr
}

func probeMTUUDP(ctx context.Context, ip string, result *IPExtResult) {
	result.Tested = true
	if ctx.Err() != nil {
		appendMTUDetail(result, "UDP probe cancelled")
		return
	}

	remoteAddrPort, remoteAddr, err := http3UDPAddress(ip, ipPort)
	if err != nil {
		setMTUFailure(result, err)
		appendMTUDetail(result, "UDP target address invalid: "+err.Error())
		return
	}
	minSize, maxSize, maxSteps := mtuProbeBounds(remoteAddrPort.Addr())
	if maxSteps == 0 {
		setMTUFailure(result, fmt.Errorf("unsupported address family for UDP MTU probe"))
		return
	}

	network := "udp4"
	if remoteAddrPort.Addr().Is6() {
		network = "udp6"
	}
	packetConn, err := dialer.ListenPacket(ctx, network, "", remoteAddrPort)
	if err != nil {
		setMTUFailure(result, err)
		appendMTUDetail(result, "UDP socket failed: "+err.Error())
		return
	}
	defer packetConn.Close()
	udpConn, ok := packetConn.(*net.UDPConn)
	if !ok {
		err := fmt.Errorf("UDP socket is not a *net.UDPConn")
		setMTUFailure(result, err)
		appendMTUDetail(result, err.Error())
		return
	}
	if err := setMTUDontFragment(udpConn, remoteAddrPort.Addr().Is6()); err != nil {
		setMTUFailure(result, fmt.Errorf("set UDP DF: %w", err))
		appendMTUDetail(result, "UDP DF setup failed: "+err.Error())
		return
	}
	appendMTUDetail(result, "UDP DF socket option verified by readback")

	// This scoped watcher only wakes a blocked socket read on cancellation; it exits with this probe.
	watcherDone := make(chan struct{})
	watcherExited := make(chan struct{})
	go func() {
		defer close(watcherExited)
		select {
		case <-ctx.Done():
			_ = udpConn.SetReadDeadline(time.Now())
		case <-watcherDone:
		}
	}()
	defer func() {
		close(watcherDone)
		<-watcherExited
	}()

	sentAny := false
	localTooLarge := false
	maxOK, steps, err := searchMaxPacketSize(minSize, maxSize, maxSteps, func(size int) (bool, error) {
		ok, sent, localLimit, probeErr := probeUDPPacketSize(ctx, udpConn, remoteAddr, size)
		sentAny = sentAny || sent > 0
		localTooLarge = localTooLarge || localLimit
		switch {
		case probeErr != nil && (errors.Is(probeErr, context.Canceled) || errors.Is(probeErr, context.DeadlineExceeded)):
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: cancelled", size))
		case probeErr != nil:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: error: %v", size, probeErr))
		case localLimit && sent == 0:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: DF / local EMSGSIZE", size))
		case ok && localLimit:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: sent %d/2, Version Negotiation reply (one local EMSGSIZE)", size, sent))
		case ok:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: sent %d, Version Negotiation reply", size, sent))
		case localLimit:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: sent %d/2, no reply within 2 s (one local EMSGSIZE)", size, sent))
		default:
			appendMTUDetail(result, fmt.Sprintf("UDP %d bytes: sent %d, no reply within 2 s", size, sent))
		}
		return ok, probeErr
	})
	result.UDPMTU = maxOK
	if result.TCPMSS > 0 || result.UDPMTU > 0 {
		result.OK = true
	}
	if err != nil {
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			appendMTUDetail(result, "UDP probe cancelled")
			return
		}
		setMTUFailure(result, err)
		return
	}
	if maxOK == 0 {
		if sentAny {
			result.UDPNoResponse = true
			appendMTUDetail(result, fmt.Sprintf("UDP search completed: no Version Negotiation response in %d size checks", steps))
		} else if localTooLarge {
			setMTUFailure(result, fmt.Errorf("all UDP probe sizes were blocked locally by DF / MTU"))
		} else {
			setMTUFailure(result, fmt.Errorf("no UDP probe packet was sent"))
		}
	} else {
		appendMTUDetail(result, fmt.Sprintf("UDP search completed: maximum responding datagram payload %d bytes after %d size checks", maxOK, steps))
		if maxOK == maxSize {
			appendMTUDetail(result, "UDP upper bound reached")
		}
	}
}

func probeUDPPacketSize(ctx context.Context, conn *net.UDPConn, remote *net.UDPAddr, size int) (ok bool, sent int, localTooLarge bool, err error) {
	dcid := make([]byte, 8)
	scid := make([]byte, 8)
	if _, err := rand.Read(dcid); err != nil {
		return false, 0, false, fmt.Errorf("generate QUIC DCID: %w", err)
	}
	if _, err := rand.Read(scid); err != nil {
		return false, 0, false, fmt.Errorf("generate QUIC SCID: %w", err)
	}
	packet, err := buildMTUProbe(size, dcid, scid)
	if err != nil {
		return false, 0, false, err
	}

	for attempt := 0; attempt < 2; attempt++ {
		if err := ctx.Err(); err != nil {
			return false, sent, localTooLarge, err
		}
		n, writeErr := conn.WriteToUDP(packet, remote)
		if writeErr != nil {
			if errors.Is(writeErr, unix.EMSGSIZE) {
				localTooLarge = true
				continue
			}
			return false, sent, localTooLarge, fmt.Errorf("send UDP probe: %w", writeErr)
		}
		if n != len(packet) {
			return false, sent, localTooLarge, fmt.Errorf("short UDP write: %d of %d bytes", n, len(packet))
		}
		sent++
	}
	if sent == 0 {
		return false, 0, localTooLarge, nil
	}

	deadline := time.Now().Add(2 * time.Second)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := conn.SetReadDeadline(deadline); err != nil {
		return false, sent, localTooLarge, fmt.Errorf("set UDP read deadline: %w", err)
	}
	buffer := make([]byte, 2048)
	for {
		if err := ctx.Err(); err != nil {
			return false, sent, localTooLarge, err
		}
		n, from, readErr := conn.ReadFromUDP(buffer)
		if readErr != nil {
			if err := ctx.Err(); err != nil {
				return false, sent, localTooLarge, err
			}
			if netErr, ok := readErr.(net.Error); ok && netErr.Timeout() {
				return false, sent, localTooLarge, nil
			}
			return false, sent, localTooLarge, fmt.Errorf("read UDP probe response: %w", readErr)
		}
		if !sameUDPAddr(from, remote) {
			continue
		}
		if isVersionNegotiationResponse(buffer[:n], dcid, scid) {
			return true, sent, localTooLarge, nil
		}
	}
}

func sameUDPAddr(a, b *net.UDPAddr) bool {
	return a != nil && b != nil && a.Port == b.Port && a.IP.Equal(b.IP) && a.Zone == b.Zone
}
