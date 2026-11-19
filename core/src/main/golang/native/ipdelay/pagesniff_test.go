package ipdelay

import (
	"strings"
	"testing"
)

func TestSniffPage(t *testing.T) {
	big := strings.Repeat("<div>ok</div>", 600) // over jsRedirectMaxPage

	cases := []struct {
		name, body, kind, note string
	}{
		{"nothing", "<html><body>hello</body></html>", "", ""},
		{"meta refresh", `<html><head><meta http-equiv="refresh" content="0; url=https://example.com/unsupported"></head></html>`, SniffRefresh, "https://example.com/unsupported"},
		{"meta refresh, content first", `<meta content="3;URL='/region'" http-equiv="Refresh">`, SniffRefresh, "/region"},
		{"meta refresh without address", `<meta http-equiv="refresh" content="30">`, "", ""},
		{"script on a small page", `<script>window.location.href = "https://example.com/blocked";</script>`, SniffScript, "https://example.com/blocked"},
		{"script replace", `<script>location.replace('/na')</script>`, SniffScript, "/na"},
		{"script on a big page is ignored", big + `<script>location = "/x"</script>`, "", ""},
		{"region english", "<p>This service is Not Available In Your Country.</p>", SniffRegion, "not available in your country"},
		{"region openai code", `{"error":{"code":"unsupported_country_region_territory"}}`, SniffRegion, "unsupported_country_region_territory"},
		{"ordinary chinese text is not a refusal", "<p>在您所在的国家，我们提供多种服务</p>", "", ""},
		{"region chinese", "<p>该服务在您所在的国家/地区不可用</p>", SniffRegion, "您所在的国家/地区不可用"},
		{"refresh wins over region", `<meta http-equiv=refresh content="0;url=/n"><p>not available in your country</p>`, SniffRefresh, "/n"},
	}

	for _, c := range cases {
		kind, note := SniffPage([]byte(c.body))
		if kind != c.kind || note != c.note {
			t.Errorf("%s: got (%q, %q), want (%q, %q)", c.name, kind, note, c.kind, c.note)
		}
	}
}


func TestSniffPageHint(t *testing.T) {
	got := SniffPageHint([]byte(`<!doctype html><html><head><title>  Blocked &amp; denied </title><style>.x{display:none}</style><script>secret()</script></head><body>
		<h1>  Access   denied </h1><script>hidden text</script><p>Try again later</p></body></html>`))
	if got != "Blocked & denied\nAccess denied Try again later" {
		t.Fatalf("got %q", got)
	}

	long := SniffPageHint([]byte("<html><body>" + strings.Repeat("a", 100) + "</body></html>"))
	if got := len([]rune(long[strings.IndexByte(long, '\n')+1:])); got != 80 {
		t.Fatalf("body length = %d, want 80", got)
	}

	if got := SniffPageHint([]byte("<html><body>plain text without title</body></html>")); got != "\nplain text without title" {
		t.Fatalf("body without title = %q", got)
	}
	if got := SniffPageHint([]byte{0, 1, 2}); got != "" {
		t.Fatalf("binary body = %q", got)
	}
}
