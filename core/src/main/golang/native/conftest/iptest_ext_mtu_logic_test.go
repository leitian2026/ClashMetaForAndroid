package conftest

import (
	"encoding/binary"
	"net"
	"net/netip"
	"testing"
	"time"
)

func TestBuildMTUProbe(t *testing.T) {
	dcid := []byte{1, 2, 3, 4, 5, 6, 7, 8}
	scid := []byte{8, 7, 6, 5, 4, 3, 2, 1}
	packet, err := buildMTUProbe(1200, dcid, scid)
	if err != nil {
		t.Fatal(err)
	}
	if len(packet) != 1200 {
		t.Fatalf("packet length = %d, want 1200", len(packet))
	}
	if packet[0] != 0xc0 {
		t.Fatalf("first byte = %#x, want 0xc0", packet[0])
	}
	if got := binary.BigEndian.Uint32(packet[1:5]); got != mtuProbeReservedVersion {
		t.Fatalf("version = %#x, want %#x", got, mtuProbeReservedVersion)
	}
	if packet[5] != byte(len(dcid)) || packet[14] != byte(len(scid)) {
		t.Fatal("incorrect connection ID lengths")
	}
	for _, b := range packet[23:] {
		if b != 0 {
			t.Fatal("padding is not zero-filled")
		}
	}
}

func TestIsVersionNegotiationResponse(t *testing.T) {
	dcid := []byte{1, 2, 3, 4}
	scid := []byte{5, 6, 7, 8}
	response := []byte{0x80, 0, 0, 0, 0, byte(len(scid))}
	response = append(response, scid...)
	response = append(response, byte(len(dcid)))
	response = append(response, dcid...)
	response = append(response, 0, 0, 0, 1, 0x0a, 0x0a, 0x0a, 0x0a)
	if !isVersionNegotiationResponse(response, dcid, scid) {
		t.Fatal("valid Version Negotiation response was rejected")
	}
	if isVersionNegotiationResponse(response, scid, dcid) {
		t.Fatal("response with reversed expected connection IDs was accepted")
	}
}

func TestMTUProbeBounds(t *testing.T) {
	v4Min, v4Max, v4Steps := mtuProbeBounds(netip.MustParseAddr("192.0.2.1"))
	if v4Min != 1200 || v4Max != 1472 || v4Steps != 9 {
		t.Fatalf("IPv4 bounds = %d..%d in %d steps", v4Min, v4Max, v4Steps)
	}
	v6Min, v6Max, v6Steps := mtuProbeBounds(netip.MustParseAddr("2001:db8::1"))
	if v6Min != 1200 || v6Max != 1452 || v6Steps != 8 {
		t.Fatalf("IPv6 bounds = %d..%d in %d steps", v6Min, v6Max, v6Steps)
	}
}

func TestSearchMaxPacketSize(t *testing.T) {
	got, steps, err := searchMaxPacketSize(1200, 1472, 9, func(size int) (bool, error) {
		return size <= 1337, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if got != 1337 || steps != 9 {
		t.Fatalf("IPv4 search = %d after %d steps, want 1337 after 9", got, steps)
	}

	got, steps, err = searchMaxPacketSize(1200, 1452, 8, func(size int) (bool, error) {
		return size <= 1337, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if got != 1337 || steps > 8 {
		t.Fatalf("IPv6 search = %d after %d steps, want 1337 within 8", got, steps)
	}
}

func TestSearchMaxPacketSizeNoResponse(t *testing.T) {
	got, _, err := searchMaxPacketSize(1200, 1472, 9, func(int) (bool, error) {
		return false, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if got != 0 {
		t.Fatalf("max response size = %d, want 0", got)
	}
}


// These wrappers mirror the unexported tcpConn / udpConn wrappers added by
// patches/mihomo/0001-conn-count.patch: embedding preserves the socket methods,
// but changes the concrete dynamic type returned by the dialer.
type mtuTestTCPWrapper struct {
	*net.TCPConn
}

type mtuTestUDPWrapper struct {
	*net.UDPConn
}

func assertMTUSyscallFD(t *testing.T, conn mtuSyscallConn) {
	t.Helper()
	rawConn, err := conn.SyscallConn()
	if err != nil {
		t.Fatalf("SyscallConn: %v", err)
	}
	var fd uintptr
	if err := rawConn.Control(func(value uintptr) {
		fd = value
	}); err != nil {
		t.Fatalf("RawConn.Control: %v", err)
	}
	if fd == 0 {
		t.Fatal("SyscallConn returned an invalid file descriptor")
	}
}

func TestMTUSocketInterfacesAcceptEmbeddedDialerWrappers(t *testing.T) {
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()

	tcpClient, err := net.DialTCP("tcp4", nil, listener.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	defer tcpClient.Close()
	tcpServer, err := listener.AcceptTCP()
	if err != nil {
		t.Fatal(err)
	}
	defer tcpServer.Close()

	wrappedTCP := &mtuTestTCPWrapper{TCPConn: tcpClient}
	var tcpSyscall mtuSyscallConn = wrappedTCP
	assertMTUSyscallFD(t, tcpSyscall)

	udpSocket, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer udpSocket.Close()

	wrappedUDP := &mtuTestUDPWrapper{UDPConn: udpSocket}
	var udpMTU mtuUDPConn = wrappedUDP
	assertMTUSyscallFD(t, udpMTU)

	if err := udpMTU.SetReadDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatalf("SetReadDeadline: %v", err)
	}
	if _, err := udpMTU.WriteToUDP([]byte("probe"), &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: udpSocket.LocalAddr().(*net.UDPAddr).Port}); err != nil {
		t.Fatalf("WriteToUDP: %v", err)
	}
	buffer := make([]byte, 16)
	if _, _, err := udpMTU.ReadFromUDP(buffer); err != nil {
		t.Fatalf("ReadFromUDP: %v", err)
	}
}
