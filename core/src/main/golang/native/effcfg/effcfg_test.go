package effcfg

import (
	"strings"
	"testing"
)

func str(v string) *Node   { return &Node{Kind: Scalar, Value: v, Tag: "str"} }
func num(v string) *Node   { return &Node{Kind: Scalar, Value: v, Tag: "int"} }
func boo(v string) *Node   { return &Node{Kind: Scalar, Value: v, Tag: "bool"} }
func seq(k ...*Node) *Node { return &Node{Kind: Seq, Kids: k} }

// m builds a map from alternating key, value arguments.
func m(kv ...any) *Node {
	n := &Node{Kind: Map}

	for i := 0; i < len(kv); i += 2 {
		n.Keys = append(n.Keys, kv[i].(string))
		n.Kids = append(n.Kids, kv[i+1].(*Node))
	}

	return n
}

// render writes a report the way the page shows it, in plain text.
func render(r Report) string {
	var sb strings.Builder

	for _, l := range r.Lines {
		mark := " "

		switch l.St {
		case Changed:
			mark = "~"
		case Removed:
			mark = "-"
		case Added:
			mark = "+"
		}

		sb.WriteString(mark)
		sb.WriteString(strings.Repeat(" ", l.Ind))
		sb.WriteString(l.Pre)

		if l.Old != "" {
			sb.WriteString("[" + l.Old + "] ")
		}

		sb.WriteString(l.Val)

		if l.Src != "" {
			sb.WriteString("  <" + l.Src + ">")
		}

		sb.WriteString("\n")
	}

	return sb.String()
}

func TestDiffMarksEveryKind(t *testing.T) {
	user := m(
		"mixed-port", num("7890"),
		"external-controller", str("127.0.0.1:9090"),
		"foo", str("unknown to the core"),
		"tun", m("enable", boo("true"), "stack", str("system")),
		"dns", m("enable", boo("true"), "nameserver", seq(str("1.1.1.1"), str("8.8.8.8"))),
		"listeners", seq(m("type", str("mixed"), "port", num("1")), m("type", str("tun"), "name", str("t"))),
	)

	kernel := m(
		"mixed-port", num("7890"),
		"external-controller", str("127.0.0.1:9090"),
		"tun", m("enable", boo("true"), "stack", str("system"), "mtu", num("9000")),
		"dns", m("enable", boo("true"), "nameserver", seq(str("1.1.1.1"), str("8.8.8.8"))),
		"listeners", seq(m("type", str("mixed"), "port", num("1")), m("type", str("tun"), "name", str("t"))),
		"profile", m("store-fake-ip", boo("false")),
		"ntp", m("enable", boo("false")),
		"hosts", m(), // nothing, must not be shown
	)

	app := m(
		"mixed-port", num("7890"),
		"external-controller", str(""),
		"tun", m("enable", boo("false"), "stack", str("system"), "mtu", num("9000")),
		"dns", m("enable", boo("true"), "nameserver", seq(str("1.1.1.1"), str("8.8.8.8"), str("system://"))),
		"listeners", seq(m("type", str("mixed"), "port", num("1"))),
		"profile", m("store-fake-ip", boo("true")),
		"ntp", m("enable", boo("false")),
		"hosts", m(),
	)

	r := Diff([]Stage{{"", user}, {SrcKernel, kernel}, {SrcApp, app}})

	want := `` +
		` mixed-port: 7890
~external-controller: [127.0.0.1:9090] ""  <app>
-foo: unknown to the core  <kernel>
 tun:
~  enable: [true] false  <app>
   stack: system
+  mtu: 9000  <kernel>
 dns:
   enable: true
   nameserver:
     - 1.1.1.1
     - 8.8.8.8
+    - system://  <app>
 listeners:
   - type: mixed
     port: 1
-  - type: tun  <app>
-    name: t  <app>
+profile:  <kernel>
~  store-fake-ip: [false] true  <app>
+ntp:  <kernel>
+  enable: false  <kernel>
`

	// the lines of one kind carry only what that kind has, so compare after normalizing the indentation marks
	got := render(r)

	if normalize(got) != normalize(want) {
		t.Fatalf("got:\n%s\nwant:\n%s", got, want)
	}

	if r.Changed != 3 || r.Removed != 2 || r.Added != 4 || r.AddedKernel != 3 {
		// changed: external-controller, tun.enable, profile.store-fake-ip; removed: foo, listener;
		// added: mtu (core), "system://" (app), profile (core), ntp (core)
		t.Fatalf("counts %+v", r)
	}
}

// normalize lets the test text above be written with the marks hand-aligned.
func normalize(s string) string {
	var out []string

	for _, line := range strings.Split(strings.TrimRight(s, "\n"), "\n") {
		if len(line) == 0 {
			continue
		}

		out = append(out, string(line[0])+strings.TrimRight(line[1:], " "))
	}

	return strings.Join(out, "\n")
}

func TestSpellingIsNotADifference(t *testing.T) {
	user := m("a", num("1"), "b", &Node{Kind: Scalar, Value: "1.0", Tag: "float"}, "c", boo("True"), "d", str("7890"), "e", seq())
	final := m("a", num("1"), "b", num("1"), "c", boo("true"), "d", num("7890"), "e", &Node{Kind: Scalar, Tag: "null"})

	r := Diff([]Stage{{"", user}, {SrcKernel, final}})

	if r.Changed+r.Removed+r.Added != 0 {
		t.Fatalf("%+v\n%s", r, render(r))
	}
}

func TestKindChange(t *testing.T) {
	user := m("x", str("a"))
	final := m("x", seq(str("a")))

	r := Diff([]Stage{{"", user}, {SrcApp, final}})

	if r.Removed != 1 || r.Added != 1 {
		t.Fatalf("%+v\n%s", r, render(r))
	}
}

func TestSourceIsTheLastStepThatChangedIt(t *testing.T) {
	user := m("p", num("1"))
	s1 := m("p", num("2")) // the override
	s2 := m("p", num("2"))
	s3 := m("p", num("3")) // the app, later

	r := Diff([]Stage{{"", user}, {SrcOverride, s1}, {SrcApp, s2}, {SrcApp, s3}})

	if len(r.Lines) != 1 || r.Lines[0].Src != SrcApp || r.Lines[0].Old != "1" || r.Lines[0].Val != "3" {
		t.Fatalf("%s", render(r))
	}

	r = Diff([]Stage{{"", user}, {SrcOverride, s1}, {SrcApp, s2}})

	if r.Lines[0].Src != SrcOverride {
		t.Fatalf("%s", render(r))
	}
}

func TestQuoting(t *testing.T) {
	cases := map[string]string{
		"plain": "plain", "": `""`, "true": `"true"`, "7890": `"7890"`, "null": `"null"`,
		"a: b": `"a: b"`, "- x": `"- x"`, "-x": "-x", " lead": `" lead"`, "x #c": `"x #c"`, "1.1.1.1": "1.1.1.1",
		"+.stun.*.*": "+.stun.*.*", "*.n.n.srv.nintendo.net": `"*.n.n.srv.nintendo.net"`, "line\nbreak": `"line\nbreak"`,
	}

	for in, want := range cases {
		if got := scalarText(str(in)); got != want {
			t.Errorf("%q: got %s want %s", in, got, want)
		}
	}
}

func TestLongListsAndOrder(t *testing.T) {
	var a, b []*Node

	for i := 0; i < 3000; i++ {
		n := str(strings.Repeat("x", 3) + string(rune('a'+i%26)) + strings.Repeat("y", i%7))
		a = append(a, n)
		b = append(b, n)
	}

	// one item inserted in the middle, one dropped near the end
	b = append(b[:1500], append([]*Node{str("NEW")}, b[1500:]...)...)
	b = append(b[:2900], b[2901:]...)

	r := Diff([]Stage{{"", m("rules", seq(a...))}, {SrcApp, m("rules", seq(b...))}})

	if r.Added != 1 || r.Removed != 1 {
		t.Fatalf("%+v", r)
	}

	if len(r.Lines) != 1+3000+1 { // the key, every item (one dropped, one added: 3000 - 1 + 1), and the dropped one's line
		t.Fatalf("lines %d", len(r.Lines))
	}
}

func TestEmptyConfigs(t *testing.T) {
	r := Diff([]Stage{{"", &Node{Kind: Scalar, Tag: "null"}}, {SrcKernel, m("a", num("1"), "b", seq())}})

	if r.Added != 1 || len(r.Lines) != 1 || r.Lines[0].Pre != "a: " || r.Lines[0].Src != SrcKernel {
		t.Fatalf("%+v", r)
	}

	if len(Diff(nil).Lines) != 0 {
		t.Fatal("nil stages")
	}
}

func TestKernelDefaultBlockTheAppTweaked(t *testing.T) {
	user := m("port", num("1"))
	kernel := m("port", num("1"), "tun", m("enable", boo("false"), "auto-route", boo("true"), "mtu", num("0"), "dns", m("a", num("1"))))
	app := m("port", num("1"), "tun", m("enable", boo("false"), "auto-route", boo("false"), "mtu", num("0"), "extra", num("5"), "dns", m("a", num("1"))))

	r := Diff([]Stage{{"", user}, {SrcKernel, kernel}, {SrcApp, app}})

	want := ` port: 1
+tun:  <kernel>
+  enable: false  <kernel>
~  auto-route: [true] false  <app>
+  mtu: 0  <kernel>
+  extra: 5  <app>
+  dns:  <kernel>
+    a: 1  <kernel>
`

	if normalize(render(r)) != normalize(want) {
		t.Fatalf("got:\n%s", render(r))
	}

	// the block is the core's: one block added by the core; the app's two touches are a replaced value and an added one
	if r.Changed != 1 || r.Added != 2 || r.AddedKernel != 1 || r.Removed != 0 {
		t.Fatalf("%+v", r)
	}
}

func TestAnchorDefinitionIsNotADifference(t *testing.T) {
	defs := m("type", str("vless"), "port", num("443"))
	defs.Anchored = true

	user := m("proxy-defaults", defs, "port", num("1"))
	final := m("port", num("1"))

	r := Diff([]Stage{{"", user}, {SrcKernel, final}})

	if r.Removed != 0 || r.Added != 0 || r.Changed != 0 || len(r.Lines) != 2 || !r.Lines[0].Anchor || r.Lines[0].Pre != "proxy-defaults:" || r.Lines[0].St != Same {
		t.Fatalf("%+v\n%s", r, render(r))
	}
}

func TestOverrideOfACoreDefault(t *testing.T) {
	// the override changes a value the core had filled in: shown as replaced, by the override settings
	user := m("port", num("1"))
	kernel := m("port", num("1"), "log-level", str("info"))
	override := m("port", num("1"), "log-level", str("debug"))

	r := Diff([]Stage{{"", user}, {SrcKernel, kernel}, {SrcOverride, override}})

	if len(r.Lines) != 2 || r.Lines[1].St != Changed || r.Lines[1].Old != "info" || r.Lines[1].Val != "debug" || r.Lines[1].Src != SrcOverride {
		t.Fatalf("%s", render(r))
	}

	if r.Added != 0 || r.Changed != 1 {
		t.Fatalf("%+v", r)
	}
}
