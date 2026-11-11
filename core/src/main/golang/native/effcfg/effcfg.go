// Package effcfg lays what the core really runs over the config the user wrote (CUSTOM.md #30, not upstream).
//
// It is pure logic, standard library only, so it can be tested without the core: it takes the user's config and
// the same config after each step that changes it (the core's defaults, the override settings, the app's patches),
// all as plain ordered trees, and returns the user's config line by line with every difference marked in place:
// a value that was replaced, a field that was dropped, a field that was added, and who did it.
//
// Nothing here runs on its own; it is called once when the user asks for the page.
package effcfg

import (
	"sort"
	"strconv"
	"strings"
)

// Kind of a Node.
type Kind int

const (
	Scalar Kind = iota
	Map
	Seq
)

// Who changed a value (Line.Src).
const (
	SrcKernel   = "kernel"   // the core: defaults for what the user did not write, and fields it does not know (dropped)
	SrcOverride = "override" // the override settings of the app
	SrcApp      = "app"      // the patches of the app
)

// Status of a Line.
const (
	Same    = 0
	Changed = 1
	Removed = 2
	Added   = 3
)

// Node is one value of a config. Map keeps the order the keys were written in.
type Node struct {
	Kind  Kind
	Keys  []string // Map
	Kids  []*Node  // Map: the values, aligned with Keys; Seq: the items
	Value string   // Scalar
	Tag   string   // Scalar: "str", "int", "float", "bool" or "null"
}

// Stage is the whole config after one step. Label is who did the step ("" for the config the user wrote).
type Stage struct {
	Label string
	Tree  *Node
}

// Line is one line of the result: Pre then Val, indented by Ind spaces. For a replaced scalar (Changed) Old is
// the value it had, and Val the value it has now.
type Line struct {
	Ind int    `json:"i"`
	Pre string `json:"p"`
	Val string `json:"v,omitempty"`
	Old string `json:"o,omitempty"`
	St  int    `json:"s,omitempty"`
	Src string `json:"r,omitempty"`
}

// Report is the answer of Diff.
type Report struct {
	Lines       []Line `json:"lines"`
	Changed     int    `json:"changed"`     // values replaced
	Removed     int    `json:"removed"`     // fields / list items dropped
	Added       int    `json:"added"`       // fields / list items added
	AddedKernel int    `json:"addedKernel"` // of Added, the ones that are the core's defaults
}

// maxCells bounds the table of the list comparison; a bigger middle part is shown as "all out, all in".
const maxCells = 3000000

// Diff compares the config the user wrote (stages[0]) with the one that runs (the last stage). The stages in
// between only tell who made each difference. It needs at least two stages.
func Diff(stages []Stage) Report {
	d := &differ{stages: stages, srcCache: map[string]string{}}

	if len(stages) < 2 {
		return Report{Lines: []Line{}}
	}

	user := asMap(stages[0].Tree)
	final := asMap(stages[len(stages)-1].Tree)

	d.walkMap(nil, user, final, 0)

	d.report.Lines = d.lines

	if d.report.Lines == nil {
		d.report.Lines = []Line{}
	}

	return d.report
}

func asMap(n *Node) *Node {
	if n == nil || n.Kind != Map {
		return &Node{Kind: Map}
	}

	return n
}

type differ struct {
	stages   []Stage
	lines    []Line
	report   Report
	srcCache map[string]string
}

func (d *differ) add(l Line) {
	d.lines = append(d.lines, l)
}

// ---- walking two trees ---------------------------------------------------------------------------

// walkMap writes the entries of the user's map in the order they were written; each is compared with the same key
// of the running config. The keys that only the running config has come last.
func (d *differ) walkMap(path []string, u, f *Node, ind int) {
	fIndex := indexKeys(f)
	uIndex := indexKeys(u)

	for i, key := range u.Keys {
		uc := u.Kids[i]

		j, ok := fIndex[key]
		if !ok {
			d.removedRoot()
			d.emitEntry(key, uc, ind, "", ind+2, Removed, d.source(append(path, key)))

			continue
		}

		d.walkEntry(append(path, key), key, uc, f.Kids[j], ind)
	}

	for j, key := range f.Keys {
		if _, ok := uIndex[key]; ok {
			continue
		}

		fc := f.Kids[j]

		if isEmpty(fc) {
			continue // "nothing" the core filled in says nothing
		}

		src := d.source(append(path, key))

		d.addedRoot(src)
		d.emitEntry(key, fc, ind, "", ind+2, Added, src)
	}
}

func indexKeys(n *Node) map[string]int {
	m := make(map[string]int, len(n.Keys))

	for i, k := range n.Keys {
		m[k] = i
	}

	return m
}

// walkEntry compares one key that both configs have.
func (d *differ) walkEntry(path []string, key string, u, f *Node, ind int) {
	pre := key + ":"

	switch {
	case isEmpty(u) && isEmpty(f):
		d.emitEntry(key, u, ind, "", ind+2, Same, "")
	case u.Kind == Scalar && f.Kind == Scalar:
		if scalarEqual(u, f) {
			d.add(Line{Ind: ind, Pre: pre + " ", Val: scalarText(u)})

			return
		}

		d.report.Changed++
		d.add(Line{Ind: ind, Pre: pre + " ", Val: scalarText(f), Old: scalarText(u), St: Changed, Src: d.source(path)})
	case u.Kind == Map && f.Kind == Map:
		d.add(Line{Ind: ind, Pre: pre})
		d.walkMap(path, u, f, ind+2)
	case u.Kind == Seq && f.Kind == Seq:
		d.add(Line{Ind: ind, Pre: pre})
		d.walkSeq(path, u, f, ind+2)
	default:
		// not the same kind of value any more: the old one goes out, the new one comes in
		src := d.source(path)

		d.removedRoot()
		d.emitEntry(key, u, ind, "", ind+2, Removed, src)
		d.addedRoot(src)
		d.emitEntry(key, f, ind, "", ind+2, Added, src)
	}
}

// walkSeq compares two lists item by item (longest common run of equal items); an item that is not in both is
// shown as out or in.
func (d *differ) walkSeq(path []string, u, f *Node, ind int) {
	a := make([]string, len(u.Kids))
	b := make([]string, len(f.Kids))

	for i, n := range u.Kids {
		a[i] = canon(n)
	}

	for i, n := range f.Kids {
		b[i] = canon(n)
	}

	src := ""

	for _, op := range seqOps(a, b) {
		switch op.kind {
		case opKeep:
			d.emitItem(u.Kids[op.ai], ind, Same, "")
		case opDel:
			if src == "" {
				src = d.source(path)
			}

			d.removedRoot()
			d.emitItem(u.Kids[op.ai], ind, Removed, src)
		case opAdd:
			if src == "" {
				src = d.source(path)
			}

			d.addedRoot(src)
			d.emitItem(f.Kids[op.bi], ind, Added, src)
		}
	}
}

func (d *differ) removedRoot() { d.report.Removed++ }

func (d *differ) addedRoot(src string) {
	d.report.Added++

	if src == SrcKernel {
		d.report.AddedKernel++
	}
}

// ---- writing a value out -------------------------------------------------------------------------

// emitEntry writes "key: value" (a block for a map or a list). lead is "- " when the entry is the first one of a
// list item; childInd is the indent of what is nested under it.
func (d *differ) emitEntry(key string, n *Node, ind int, lead string, childInd int, st int, src string) {
	pre := lead + key + ":"

	switch {
	case n.Kind == Scalar:
		d.add(Line{Ind: ind, Pre: pre + " ", Val: scalarText(n), St: st, Src: src})
	case isEmpty(n):
		d.add(Line{Ind: ind, Pre: pre + " ", Val: emptyText(n), St: st, Src: src})
	case n.Kind == Map:
		d.add(Line{Ind: ind, Pre: pre, St: st, Src: src})

		for i, k := range n.Keys {
			d.emitEntry(k, n.Kids[i], childInd, "", childInd+2, st, src)
		}
	default:
		d.add(Line{Ind: ind, Pre: pre, St: st, Src: src})

		for _, it := range n.Kids {
			d.emitItem(it, childInd, st, src)
		}
	}
}

// emitItem writes one item of a list ("- value", or "- key: value" with the other keys under it).
func (d *differ) emitItem(n *Node, ind int, st int, src string) {
	switch {
	case n.Kind == Scalar:
		d.add(Line{Ind: ind, Pre: "- ", Val: scalarText(n), St: st, Src: src})
	case isEmpty(n):
		d.add(Line{Ind: ind, Pre: "- ", Val: emptyText(n), St: st, Src: src})
	case n.Kind == Map:
		for i, k := range n.Keys {
			if i == 0 {
				d.emitEntry(k, n.Kids[i], ind, "- ", ind+4, st, src)
			} else {
				d.emitEntry(k, n.Kids[i], ind+2, "", ind+4, st, src)
			}
		}
	default:
		d.add(Line{Ind: ind, Pre: "-", St: st, Src: src})

		for _, it := range n.Kids {
			d.emitItem(it, ind+2, st, src)
		}
	}
}

func emptyText(n *Node) string {
	switch {
	case n.Kind == Map:
		return "{}"
	case n.Kind == Seq:
		return "[]"
	default:
		return "null"
	}
}

func isEmpty(n *Node) bool {
	switch n.Kind {
	case Map, Seq:
		return len(n.Kids) == 0
	default:
		return n.Tag == "null"
	}
}

// scalarText is the scalar as it would be written in a config: strings that would read as something else (or
// break the line) are quoted.
func scalarText(n *Node) string {
	if n.Tag == "null" {
		return "null"
	}

	if n.Tag != "str" && n.Tag != "" {
		return n.Value
	}

	if needsQuote(n.Value) {
		return strconv.Quote(n.Value)
	}

	return n.Value
}

func needsQuote(s string) bool {
	if s == "" || s != strings.TrimSpace(s) {
		return true
	}

	if strings.ContainsAny(s, "\n\r\t") || strings.Contains(s, ": ") || strings.Contains(s, " #") {
		return true
	}

	switch s[0] {
	case '-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`':
		if s[0] == '-' && len(s) > 1 && s[1] != ' ' {
			break // "-foo" is fine, "- foo" is not
		}

		return true
	}

	if _, ok := asBool(s); ok {
		return true
	}

	if s == "null" || s == "~" || s == "Null" || s == "NULL" {
		return true
	}

	if _, err := strconv.ParseFloat(s, 64); err == nil {
		return true
	}

	return false
}

// ---- who did it ----------------------------------------------------------------------------------

// source is the label of the last step that changed the value at path.
func (d *differ) source(path []string) string {
	key := strings.Join(path, "\x00")

	if s, ok := d.srcCache[key]; ok {
		return s
	}

	src := SrcKernel

	for i := len(d.stages) - 1; i >= 1; i-- {
		prev := lookup(d.stages[i-1].Tree, path)
		cur := lookup(d.stages[i].Tree, path)

		if !sameNode(prev, cur) {
			if l := d.stages[i].Label; l != "" {
				src = l
			}

			break
		}
	}

	d.srcCache[key] = src

	return src
}

func lookup(n *Node, path []string) *Node {
	for _, k := range path {
		if n == nil || n.Kind != Map {
			return nil
		}

		found := false

		for i, key := range n.Keys {
			if key == k {
				n = n.Kids[i]
				found = true

				break
			}
		}

		if !found {
			return nil
		}
	}

	return n
}

func sameNode(a, b *Node) bool {
	if a == nil || b == nil {
		return a == b || (a == nil && b != nil && isEmpty(b)) || (b == nil && a != nil && isEmpty(a))
	}

	return canon(a) == canon(b)
}

// ---- comparing values ----------------------------------------------------------------------------

func asBool(s string) (bool, bool) {
	switch s {
	case "true", "True", "TRUE":
		return true, true
	case "false", "False", "FALSE":
		return false, true
	}

	return false, false
}

// normScalar writes "true", "1" and "1.0" the same way however they were spelled.
func normScalar(n *Node) string {
	if n.Tag == "null" {
		return ""
	}

	if b, ok := asBool(n.Value); ok && n.Tag != "str" {
		return strconv.FormatBool(b)
	}

	if n.Tag != "str" {
		if f, err := strconv.ParseFloat(n.Value, 64); err == nil {
			return strconv.FormatFloat(f, 'g', -1, 64)
		}
	}

	return n.Value
}

func scalarEqual(a, b *Node) bool {
	if a.Value == b.Value && a.Tag == b.Tag {
		return true
	}

	return normScalar(a) == normScalar(b) && (a.Tag == "null") == (b.Tag == "null")
}

// canon is a text that is the same for two values that mean the same (map keys in any order).
func canon(n *Node) string {
	var sb strings.Builder

	writeCanon(&sb, n)

	return sb.String()
}

func writeCanon(sb *strings.Builder, n *Node) {
	switch n.Kind {
	case Scalar:
		sb.WriteString("s")
		sb.WriteString(strconv.Quote(normScalar(n)))
	case Seq:
		if len(n.Kids) == 0 {
			sb.WriteString("e") // an empty list, an empty map and nothing are the same here

			return
		}

		sb.WriteString("[")

		for _, k := range n.Kids {
			writeCanon(sb, k)
			sb.WriteString(",")
		}

		sb.WriteString("]")
	case Map:
		if len(n.Kids) == 0 {
			sb.WriteString("e")

			return
		}

		order := make([]int, len(n.Keys))

		for i := range order {
			order[i] = i
		}

		sort.Slice(order, func(x, y int) bool { return n.Keys[order[x]] < n.Keys[order[y]] })

		sb.WriteString("{")

		for _, i := range order {
			sb.WriteString(strconv.Quote(n.Keys[i]))
			sb.WriteString(":")
			writeCanon(sb, n.Kids[i])
			sb.WriteString(",")
		}

		sb.WriteString("}")
	}
}

// ---- lists ---------------------------------------------------------------------------------------

type opKind int

const (
	opKeep opKind = iota
	opDel
	opAdd
)

type op struct {
	kind   opKind
	ai, bi int
}

// seqOps is the shortest way from list a to list b: items to keep, to take out and to put in.
func seqOps(a, b []string) []op {
	start := 0

	for start < len(a) && start < len(b) && a[start] == b[start] {
		start++
	}

	endA, endB := len(a), len(b)

	for endA > start && endB > start && a[endA-1] == b[endB-1] {
		endA--
		endB--
	}

	ops := make([]op, 0, len(a)+len(b))

	for i := 0; i < start; i++ {
		ops = append(ops, op{opKeep, i, i})
	}

	ma, mb := a[start:endA], b[start:endB]

	if len(ma)*len(mb) > maxCells {
		for i := range ma {
			ops = append(ops, op{opDel, start + i, 0})
		}

		for j := range mb {
			ops = append(ops, op{opAdd, 0, start + j})
		}
	} else {
		ops = append(ops, lcsOps(ma, mb, start)...)
	}

	for k := 0; endA+k < len(a); k++ {
		ops = append(ops, op{opKeep, endA + k, endB + k})
	}

	return ops
}

func lcsOps(a, b []string, offset int) []op {
	n, m := len(a), len(b)

	if n == 0 && m == 0 {
		return nil
	}

	// table[i][j] = length of the longest common run of a[i:] and b[j:]
	table := make([]int32, (n+1)*(m+1))
	w := m + 1

	for i := n - 1; i >= 0; i-- {
		for j := m - 1; j >= 0; j-- {
			if a[i] == b[j] {
				table[i*w+j] = table[(i+1)*w+j+1] + 1
			} else if table[(i+1)*w+j] >= table[i*w+j+1] {
				table[i*w+j] = table[(i+1)*w+j]
			} else {
				table[i*w+j] = table[i*w+j+1]
			}
		}
	}

	ops := make([]op, 0, n+m)
	i, j := 0, 0

	for i < n && j < m {
		switch {
		case a[i] == b[j]:
			ops = append(ops, op{opKeep, offset + i, offset + j})
			i++
			j++
		case table[(i+1)*w+j] >= table[i*w+j+1]:
			ops = append(ops, op{opDel, offset + i, 0})
			i++
		default:
			ops = append(ops, op{opAdd, 0, offset + j})
			j++
		}
	}

	for ; i < n; i++ {
		ops = append(ops, op{opDel, offset + i, 0})
	}

	for ; j < m; j++ {
		ops = append(ops, op{opAdd, 0, offset + j})
	}

	return ops
}
