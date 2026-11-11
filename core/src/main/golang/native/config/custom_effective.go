package config

// CUSTOM (CUSTOM.md #30, not upstream): the config the core really runs, laid over the config the user wrote. It
// reads config.yaml, lets the core fill in its defaults (UnmarshalRawConfig), then runs the app's own steps one by
// one (the override settings and the patches in process.go), and after each step writes the whole config out as yaml
// again. effcfg compares the user's text with the last of them and uses the steps in between to tell who changed
// what. Nothing is loaded and nothing is downloaded.
//
// Power: runs once per call, nothing in the background.

import (
	"fmt"
	"os"
	P "path"
	"reflect"

	"cfa/native/effcfg"

	cyaml "github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/config"
)

// SortEffective is the `sort` value of queryCustomConnections that asks for this; the filter is the profile folder
// (the one that holds config.yaml).
const SortEffective = 8

// EffectiveResult is the answer. When OK is false only Error is set.
type EffectiveResult struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	effcfg.Report
}

// stageLabel is who does a step: the override settings are told apart from the app's patches.
func stageLabel(p processor) string {
	if reflect.ValueOf(p).Pointer() == reflect.ValueOf(patchOverride).Pointer() {
		return effcfg.SrcOverride
	}

	return effcfg.SrcApp
}

func snapshotTree(raw *config.RawConfig) (*effcfg.Node, error) {
	data, err := cyaml.Marshal(raw)
	if err != nil {
		return nil, err
	}

	return parseTree(data)
}

// Effective compares the profile in profileDir with what the core would run. It never panics.
func Effective(profileDir string) (res EffectiveResult) {
	defer func() {
		if r := recover(); r != nil {
			res = EffectiveResult{Error: fmt.Sprint(r)}
		}
	}()

	data, err := os.ReadFile(P.Join(profileDir, "config.yaml"))
	if err != nil {
		return EffectiveResult{Error: err.Error()}
	}

	user, err := parseTree(data)
	if err != nil {
		return EffectiveResult{Error: err.Error()}
	}

	raw, err := config.UnmarshalRawConfig(data)
	if err != nil {
		return EffectiveResult{Error: err.Error()}
	}

	stages := []effcfg.Stage{{Tree: user}}

	add := func(label string) error {
		tree, err := snapshotTree(raw)
		if err != nil {
			return err
		}

		stages = append(stages, effcfg.Stage{Label: label, Tree: tree})

		return nil
	}

	// what the core made of the text: its defaults for what is not written, and fields it does not know are gone
	if err := add(effcfg.SrcKernel); err != nil {
		return EffectiveResult{Error: err.Error()}
	}

	for _, p := range processors {
		if err := p(raw, profileDir); err != nil {
			return EffectiveResult{Error: err.Error()}
		}

		if err := add(stageLabel(p)); err != nil {
			return EffectiveResult{Error: err.Error()}
		}
	}

	return EffectiveResult{OK: true, Report: effcfg.Diff(stages)}
}
