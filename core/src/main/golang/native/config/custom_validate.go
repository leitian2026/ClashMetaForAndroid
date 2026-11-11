package config

// CUSTOM (CUSTOM.md #27, not upstream): check a profile folder without loading it. It does what the validation step
// of FetchAndValid does (read config.yaml, apply the app's patches, let the core parse it, throw the parsed
// providers away), but never downloads anything and never applies the result to the running core. Used to give a
// clear error (with the line) before the user commits an edited profile, and after a rollback.
//
// Power: runs once per call, nothing in the background.

import (
	"fmt"
	"regexp"
	"strconv"
)

// SortValidate is the `sort` value of queryCustomConnections that asks for this check; the filter is the profile
// folder (the one that holds config.yaml).
const SortValidate = 7

// ValidateResult is the answer. Line is the line of config.yaml the core named in its error, 0 when it named none.
type ValidateResult struct {
	OK    bool   `json:"ok"`
	Error string `json:"error"`
	Line  int    `json:"line"`
}

var reErrorLine = regexp.MustCompile(`(?i)\bline (\d+)`)

func failure(msg string) ValidateResult {
	res := ValidateResult{Error: msg}

	if m := reErrorLine.FindStringSubmatch(msg); m != nil {
		if n, err := strconv.Atoi(m[1]); err == nil {
			res.Line = n
		}
	}

	return res
}

// Validate parses the profile in profileDir. It never panics.
func Validate(profileDir string) (res ValidateResult) {
	defer func() {
		if r := recover(); r != nil {
			res = failure(fmt.Sprint(r))
		}
	}()

	rawCfg, err := UnmarshalAndPatch(profileDir)
	if err != nil {
		return failure(err.Error())
	}

	cfg, err := Parse(rawCfg)
	if err != nil {
		return failure(err.Error())
	}

	destroyProviders(cfg)

	return ValidateResult{OK: true}
}
