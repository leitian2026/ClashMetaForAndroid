// CUSTOM (CUSTOM.md #80, not part of upstream): pure QUIC packet and MTU search helpers.
package conftest

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"net/netip"
)

const mtuProbeReservedVersion uint32 = 0x0a0a0a0a

// buildMTUProbe creates a padded QUIC long-header packet with a reserved version.
// For an unsupported version, QUIC parses only the invariant header (type, version and CIDs).
func buildMTUProbe(size int, dcid, scid []byte) ([]byte, error) {
	if size < 1200 || size > 1472 {
		return nil, fmt.Errorf("invalid QUIC probe size %d", size)
	}
	if len(dcid) > 20 || len(scid) > 20 {
		return nil, fmt.Errorf("QUIC connection ID exceeds 20 bytes")
	}

	packet := make([]byte, size)
	packet[0] = 0xc0 // Long header + QUIC fixed bit.
	binary.BigEndian.PutUint32(packet[1:5], mtuProbeReservedVersion)
	offset := 5
	packet[offset] = byte(len(dcid))
	offset++
	copy(packet[offset:], dcid)
	offset += len(dcid)
	packet[offset] = byte(len(scid))
	offset++
	copy(packet[offset:], scid)
	return packet, nil
}

// isVersionNegotiationResponse validates the QUIC Version Negotiation header and
// the connection IDs swapped by the server. The version list must be non-empty.
func isVersionNegotiationResponse(packet, dcid, scid []byte) bool {
	if len(packet) < 7 || packet[0]&0x80 == 0 || binary.BigEndian.Uint32(packet[1:5]) != 0 {
		return false
	}
	offset := 5
	dcidLen := int(packet[offset])
	offset++
	if dcidLen > 20 || offset+dcidLen+1 > len(packet) {
		return false
	}
	responseDCID := packet[offset : offset+dcidLen]
	offset += dcidLen
	scidLen := int(packet[offset])
	offset++
	if scidLen > 20 || offset+scidLen > len(packet) {
		return false
	}
	responseSCID := packet[offset : offset+scidLen]
	offset += scidLen
	versions := packet[offset:]
	return bytes.Equal(responseDCID, scid) && bytes.Equal(responseSCID, dcid) && len(versions) > 0 && len(versions)%4 == 0
}

// searchMaxPacketSize returns the greatest size accepted by probe within maxSteps checks.
func searchMaxPacketSize(minSize, maxSize, maxSteps int, probe func(size int) (bool, error)) (maxOK, steps int, err error) {
	low, high := minSize, maxSize
	for low <= high && steps < maxSteps {
		size := low + (high-low+1)/2
		ok, probeErr := probe(size)
		steps++
		if probeErr != nil {
			return maxOK, steps, probeErr
		}
		if ok {
			maxOK = size
			low = size + 1
		} else {
			high = size - 1
		}
	}
	return maxOK, steps, nil
}

func mtuProbeBounds(address netip.Addr) (minSize, maxSize, maxSteps int) {
	if address.Is4() {
		return 1200, 1472, 9 // IPv4 MTU 1228..1500.
	}
	if address.Is6() {
		return 1200, 1452, 8 // IPv6 MTU 1248..1500.
	}
	return 0, 0, 0
}
