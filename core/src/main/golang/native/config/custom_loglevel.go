package config

// CUSTOM (CUSTOM.md #31, not upstream): sets the log level of the running core at once, for the level chosen on the
// logs page. The override setting (override.json) only counts when a profile is loaded; this makes it count now.
//
// Power: runs once per choice, nothing in the background.

import (
	"strings"

	"github.com/metacubex/mihomo/log"
)

// SortLogLevel is the `sort` value of queryCustomConnections that asks for this; the filter is the level
// ("debug", "info", "warning", "error" or "silent"). An empty or unknown filter changes nothing.
const SortLogLevel = 9

// LogLevelResult is the level the core has after the call.
type LogLevelResult struct {
	Level string `json:"level"`
}

// SetLogLevel sets the level and returns the level the core has now.
func SetLogLevel(name string) LogLevelResult {
	if l, ok := log.LogLevelMapping[strings.ToLower(strings.TrimSpace(name))]; ok {
		log.SetLevel(l)
	}

	return LogLevelResult{Level: log.Level().String()}
}
