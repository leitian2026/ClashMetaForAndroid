package issues

import (
	"testing"
	"time"
)

func reset() {
	mu.Lock()
	items, ignored, loaded, dir = map[string]*Issue{}, map[string]bool{}, false, ""
	nodeFailures = map[string][]time.Time{}
	mu.Unlock()
	OnNetworkOffline = nil
}

func TestFromLog(t *testing.T) {
	reset()
	FromLog("[Provider] my sub pull error: Get x: i/o timeout")
	FromLog("[GEO] update GEO database error: boom")
	FromLog("GeoSite.dat invalid, remove and download")
	FromLog("[DNS] skip invalid nameserver policy: x")
	if FromLog("something unrelated") || FromLog("[Provider] x started") {
		t.Fatal("unrelated line taken")
	}

	s := Query()
	if s.Active != 4 {
		t.Fatalf("active %d: %+v", s.Active, s.Items)
	}

	for _, it := range s.Items {
		if it.Kind == KindProvider && it.Subject != "my sub" {
			t.Fatalf("provider subject %q", it.Subject)
		}
	}
}

func TestIgnorePersistsAndResolve(t *testing.T) {
	reset()
	d := t.TempDir()
	SetDir(d)
	Report(KindIP, "1.2.3.4:443", "timeout", "i/o timeout", "n1")
	Report(KindIP, "1.2.3.4:443", "timeout", "i/o timeout", "n1")

	if s := Query(); s.Items[0].Count != 2 {
		t.Fatalf("count %d", s.Items[0].Count)
	}

	SetIgnored(ID(KindIP, "1.2.3.4:443"), true)

	if s := Query(); s.Active != 0 || !s.Items[0].Ignored {
		t.Fatalf("%+v", s)
	}

	// a new run of the core: the ignore choice comes back from the file
	mu.Lock()
	items, ignored, loaded = map[string]*Issue{}, map[string]bool{}, false
	mu.Unlock()
	Report(KindIP, "1.2.3.4:443", "timeout", "x", "n1")

	if s := Query(); s.Active != 0 {
		t.Fatalf("ignore lost: %+v", s)
	}

	Resolve(KindIP, "1.2.3.4:443")
	Clear()

	if s := Query(); len(s.Items) != 0 {
		t.Fatalf("clear kept %+v", s.Items)
	}
}

func TestCommand(t *testing.T) {
	reset()
	Command("sub|A|HTTP 403")

	if s := Query(); s.Active != 1 {
		t.Fatal("sub not reported")
	}

	Command("subok|A")

	if s := Query(); s.Active != 0 {
		t.Fatal("sub not resolved")
	}
}

func TestDialLines(t *testing.T) {
	reset()

	var asked int

	OnDNSFailed = func() { asked++ }

	line := func(node, dest, reason string) string {
		return "[TCP] dial " + node + " (match Match/) 127.0.0.1:1 --> " + dest + " error: " + reason
	}

	// the address of a node that did not answer
	if FromLog(line("my node", "example.com:443", "dial tcp 1.2.3.4:8443: i/o timeout")) {
		t.Fatal("a dial line must not be taken")
	}

	s := Query()
	if s.Active != 1 || s.Items[0].Kind != KindIP || s.Items[0].Subject != "1.2.3.4:8443" || s.Items[0].Extra != "my node" {
		t.Fatalf("got %+v", s)
	}

	// not a node's address: DIRECT, the destination itself, cancelled, no network
	FromLog(line("DIRECT", "example.com:443", "dial tcp 5.5.5.5:443: i/o timeout"))
	FromLog(line("n2", "6.6.6.6:443", "dial tcp 6.6.6.6:443: i/o timeout"))
	FromLog(line("n3", "example.com:443", "dial tcp 7.7.7.7:443: operation was canceled: context canceled"))
	FromLog(line("n4", "example.com:443", "dial tcp [2001:db8::1]:443: connect: network is unreachable"))

	if s := Query(); s.Active != 1 {
		t.Fatalf("extra issues: %+v", s.Items)
	}

	// IPv6 node address
	FromLog(line("n5", "example.com:443", "dial tcp [2001:db8::2]:443: i/o timeout"))

	if s := Query(); s.Active != 2 {
		t.Fatalf("ipv6 address not found: %+v", s.Items)
	}

	// no DNS server answered: the servers are asked, the line is still the errors page's
	if FromLog(line("n6", "example.com:443", "dns resolve failed: all DNS requests failed, first error: x")) || asked != 1 {
		t.Fatalf("asked %d", asked)
	}

	// the same sentence on its own is an issue and also asks
	if !FromLog("[DNS] all DNS requests failed, first error: x") || asked != 2 {
		t.Fatalf("standalone: asked %d", asked)
	}
}


func TestDialIssueNeedsTwoFailures(t *testing.T) {
	reset()
	OnNetworkOffline = func() bool { return false }
	line := "[TCP] dial node-a (match Match/) 127.0.0.1:1 --> example.com:443 error: dial tcp 1.2.3.4:8443: i/o timeout"

	FromLog(line)
	if s := Query(); s.Active != 0 {
		t.Fatalf("one failure created an issue: %+v", s.Items)
	}

	FromLog(line)
	if s := Query(); s.Active != 1 || s.Items[0].Subject != "1.2.3.4:8443" {
		t.Fatalf("two failures did not create issue: %+v", s.Items)
	}
}

func TestDialIssueSkippedWhenNetworkOffline(t *testing.T) {
	reset()
	calls := 0
	OnNetworkOffline = func() bool {
		calls++
		return true
	}
	line := "[TCP] dial node-a (match Match/) 127.0.0.1:1 --> example.com:443 error: dial tcp 1.2.3.4:8443: i/o timeout"

	FromLog(line)
	FromLog(line)
	if s := Query(); s.Active != 0 {
		t.Fatalf("created issue while offline: %+v", s.Items)
	}
	if calls != 1 {
		t.Fatalf("offline check called %d times, want 1", calls)
	}

	FromLog(line)
	if s := Query(); s.Active != 0 {
		t.Fatalf("one post-offline failure created an issue: %+v", s.Items)
	}
	FromLog(line)
	if s := Query(); s.Active != 0 {
		t.Fatalf("offline issue unexpectedly created: %+v", s.Items)
	}
	if calls != 3 {
		t.Fatalf("offline check called %d times, want 3", calls)
	}
}

func TestExistingDialIssueStillAccumulates(t *testing.T) {
	reset()
	OnNetworkOffline = func() bool { return true }
	Report(KindIP, "1.2.3.4:8443", "timeout", "first", "node-a")
	line := "[TCP] dial node-a (match Match/) 127.0.0.1:1 --> example.com:443 error: dial tcp 1.2.3.4:8443: i/o timeout"
	FromLog(line)
	if s := Query(); s.Active != 1 || s.Items[0].Count != 2 {
		t.Fatalf("existing issue did not accumulate: %+v", s.Items)
	}
}


func TestResolveByExtra(t *testing.T) {
	reset()
	Report(KindIP, "1.2.3.4:443", "timeout", "timeout", "node-a")
	Report(KindIP, "2.3.4.5:443", "timeout", "timeout", "node-a")
	Report(KindIP, "3.4.5.6:443", "timeout", "timeout", "node-b")

	ResolveByExtra(KindIP, "node-a")

	s := Query()
	resolved := 0
	for _, it := range s.Items {
		if it.Extra == "node-a" && it.Resolved {
			resolved++
		}
		if it.Extra == "node-b" && it.Resolved {
			t.Fatalf("another node was resolved: %+v", it)
		}
	}
	if resolved != 2 || s.Active != 1 {
		t.Fatalf("unexpected snapshot: %+v", s)
	}
}
