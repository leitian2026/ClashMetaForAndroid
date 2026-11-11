package config

// CUSTOM (CUSTOM.md #30, not upstream): reads yaml text into the plain ordered trees that effcfg compares. It keeps
// the order the keys were written in (a plain map would lose it) and resolves anchors, aliases and `<<` merges, so
// what is compared is what the core reads.

import (
	"cfa/native/effcfg"

	yamlv3 "go.yaml.in/yaml/v3"
)

// maxYamlDepth stops an alias that points back at itself.
const maxYamlDepth = 64

func nullTree() *effcfg.Node {
	return &effcfg.Node{Kind: effcfg.Scalar, Tag: "null"}
}

// parseTree reads yaml text. Empty text is a null scalar.
func parseTree(data []byte) (*effcfg.Node, error) {
	var doc yamlv3.Node

	if err := yamlv3.Unmarshal(data, &doc); err != nil {
		return nil, err
	}

	return convertTree(&doc, 0), nil
}

func resolveAlias(n *yamlv3.Node) *yamlv3.Node {
	for i := 0; n != nil && n.Kind == yamlv3.AliasNode && i < maxYamlDepth; i++ {
		n = n.Alias
	}

	return n
}

func convertTree(n *yamlv3.Node, depth int) *effcfg.Node {
	n = resolveAlias(n)

	if n == nil || depth > maxYamlDepth {
		return nullTree()
	}

	switch n.Kind {
	case yamlv3.DocumentNode:
		if len(n.Content) == 0 {
			return nullTree()
		}

		return convertTree(n.Content[0], depth+1)
	case yamlv3.SequenceNode:
		out := &effcfg.Node{Kind: effcfg.Seq}

		for _, c := range n.Content {
			out.Kids = append(out.Kids, convertTree(c, depth+1))
		}

		return out
	case yamlv3.MappingNode:
		return convertMap(n, depth)
	case yamlv3.ScalarNode:
		tag := "str"

		switch n.ShortTag() {
		case "!!int":
			tag = "int"
		case "!!float":
			tag = "float"
		case "!!bool":
			tag = "bool"
		case "!!null":
			tag = "null"
		}

		return &effcfg.Node{Kind: effcfg.Scalar, Value: n.Value, Tag: tag}
	}

	return nullTree()
}

// convertMap reads a mapping. A `<<` merge brings in the keys of another mapping (or a list of them) that the map does
// not write itself; the keys written in the map win, a later duplicate key replaces an earlier one.
func convertMap(n *yamlv3.Node, depth int) *effcfg.Node {
	out := &effcfg.Node{Kind: effcfg.Map}
	index := map[string]int{}

	put := func(key string, value *effcfg.Node, replace bool) {
		if i, ok := index[key]; ok {
			if replace {
				out.Kids[i] = value
			}

			return
		}

		index[key] = len(out.Keys)
		out.Keys = append(out.Keys, key)
		out.Kids = append(out.Kids, value)
	}

	merged := func(value *yamlv3.Node) {
		value = resolveAlias(value)

		if value == nil {
			return
		}

		sources := []*yamlv3.Node{value}

		if value.Kind == yamlv3.SequenceNode {
			sources = value.Content
		}

		for _, s := range sources {
			if m := convertTree(s, depth+1); m.Kind == effcfg.Map {
				for i, k := range m.Keys {
					put(k, m.Kids[i], false)
				}
			}
		}
	}

	for i := 0; i+1 < len(n.Content); i += 2 {
		key := resolveAlias(n.Content[i])

		if key == nil || key.Kind != yamlv3.ScalarNode {
			continue
		}

		if key.ShortTag() == "!!merge" {
			continue // handled after the keys the map writes itself
		}

		put(key.Value, convertTree(n.Content[i+1], depth+1), true)
	}

	for i := 0; i+1 < len(n.Content); i += 2 {
		if key := resolveAlias(n.Content[i]); key != nil && key.Kind == yamlv3.ScalarNode && key.ShortTag() == "!!merge" {
			merged(n.Content[i+1])
		}
	}

	return out
}
