package issues

import (
	"testing"
)

func reset() {
	mu.Lock()
	items, ignored, loaded, dir = map[string]*Issue{}, map[string]bool{}, false, ""
	mu.Unlock()
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
