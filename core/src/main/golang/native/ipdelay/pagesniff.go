package ipdelay

// CUSTOM (CUSTOM.md #67, not part of upstream): what the page of a target address says about being moved or refused.
// A status code alone cannot tell: a site that does not serve a country often answers 200 with a page that says so, or
// moves the browser with a `<meta refresh>` or a line of script. SniffPage looks at the first part of the page (the
// caller reads at most sniffMax bytes) and names the first thing it finds. It uses no other package, so it can be tested
// alone. It is a guess from the text: a page that is drawn by script after it loads (most single-page apps) shows
// nothing here, and a word list cannot know every site's wording.

import (
	"bytes"
	"html"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// sniffMax is how much of the page the caller reads.
const sniffMax = 64 << 10

// jsRedirectMaxPage: a script redirect is only believed on a small page; a big page has `location = "..."` in its normal
// code all the time.
const jsRedirectMaxPage = 4096

// kinds returned by SniffPage
const (
	SniffRefresh = "refresh" // `<meta http-equiv="refresh" content="0;url=...">`, the note is the address
	SniffScript  = "script"  // a small page whose script sets `location`, the note is the address
	SniffRegion  = "region"  // the page says the service is not available in the country / region, the note is the words found
)

var (
	metaTag     = regexp.MustCompile(`(?is)<meta\b[^>]*>`)
	refreshAttr = regexp.MustCompile(`(?is)http-equiv\s*=\s*["']?\s*refresh`)
	refreshURL  = regexp.MustCompile(`(?is)content\s*=\s*["'][^"']*?url\s*=\s*['"]?([^"'>\s]+)`)
	scriptMove  = regexp.MustCompile(`(?is)(?:\blocation(?:\.href)?\s*=\s*|\blocation\.(?:replace|assign)\(\s*)["']([^"']+)["']`)
)

// regionWords are phrases (lower case) that a page uses to refuse a country or region. A short list on purpose: each one
// is a whole phrase, so that ordinary text does not match by accident.
var regionWords = []string{
	"not available in your country",
	"not available in your region",
	"isn't available in your country",
	"isn't available in your region",
	"isn't supported in your country",
	"is not supported in your country",
	"not supported in your country",
	"not supported in your region",
	"unavailable in your country",
	"unavailable in your region",
	"country, region, or territory not supported",
	"unsupported_country_region_territory",
	"not available in your location",
	"您所在的国家/地区不可用",
	"您所在的地区不可用",
	"你所在的国家/地区不可用",
	"你所在的地区不可用",
	"不支持您所在的",
	"不支持你所在的",
	"所在国家/地区不可用",
	"所在地区不可用",
}

// SniffPage returns what the page shows ("" when nothing), and the address or the words that gave it away.
func SniffPage(body []byte) (kind, note string) {
	text := string(body)

	for _, tag := range metaTag.FindAllString(text, -1) {
		if !refreshAttr.MatchString(tag) {
			continue
		}

		if m := refreshURL.FindStringSubmatch(tag); m != nil {
			return SniffRefresh, strings.TrimSpace(m[1])
		}
	}

	if len(body) <= jsRedirectMaxPage {
		if m := scriptMove.FindStringSubmatch(text); m != nil {
			return SniffScript, strings.TrimSpace(m[1])
		}
	}

	lower := strings.ToLower(text)

	for _, w := range regionWords {
		if strings.Contains(lower, w) {
			return SniffRegion, w
		}
	}

	return "", ""
}


// page text extraction is deliberately limited to the response body already read by requestSnapshotVia.
var (
	pageTitleTag   = regexp.MustCompile(`(?is)<title\b[^>]*>(.*?)</title\s*>`)
	pageBodyTag    = regexp.MustCompile(`(?is)<body\b[^>]*>(.*?)</body\s*>`)
	pageScriptTag  = regexp.MustCompile(`(?is)<script\b[^>]*>.*?</script\s*>`)
	pageStyleTag   = regexp.MustCompile(`(?is)<style\b[^>]*>.*?</style\s*>`)
	pageCommentTag = regexp.MustCompile(`(?s)<!--.*?-->`)
	pageHTMLTag    = regexp.MustCompile(`(?s)<[^>]*>`)
)

// SniffPageHint returns the title and the first 80 characters of readable page text, separated by one newline.
// The first line may be empty when the page has no title. Binary or invalid UTF-8 bodies are ignored.
func SniffPageHint(body []byte) string {
	if len(body) == 0 || bytes.IndexByte(body, 0) >= 0 || !utf8.Valid(body) {
		return ""
	}

	source := string(body)
	title := ""
	if match := pageTitleTag.FindStringSubmatch(source); len(match) > 1 {
		title = cleanPageText(match[1])
	}

	content := pageScriptTag.ReplaceAllString(source, " ")
	content = pageStyleTag.ReplaceAllString(content, " ")
	content = pageCommentTag.ReplaceAllString(content, " ")
	content = pageTitleTag.ReplaceAllString(content, " ")
	if match := pageBodyTag.FindStringSubmatch(content); len(match) > 1 {
		content = match[1]
	}
	content = cleanPageText(content)
	if title == "" && content == "" {
		return ""
	}

	runes := []rune(content)
	if len(runes) > 80 {
		content = string(runes[:80])
	}
	return title + "\n" + content
}

func cleanPageText(source string) string {
	source = pageHTMLTag.ReplaceAllString(source, " ")
	source = html.UnescapeString(source)
	for _, r := range source {
		if unicode.IsControl(r) && !unicode.IsSpace(r) {
			return ""
		}
	}
	return strings.Join(strings.Fields(source), " ")
}
