package ipdelay

// CUSTOM (CUSTOM.md #26, not upstream): the `nameserver-policy` of the loaded profile with its entries in the order
// the profile wrote them. The generic JSON tree kept by CaptureDNS is a Go map and loses that order, but the core
// takes the first matching policy, so the order matters for "which policy answers this domain".

import (
	"bytes"
	"encoding/json"
	"strings"
	"sync"
)

// PolicyEntry is one `key: servers` line of nameserver-policy. Key is as written (it may hold several patterns
// separated by commas, or a `geosite:` / `rule-set:` prefix).
type PolicyEntry struct {
	Key     string   `json:"key"`
	Servers []string `json:"servers"`
}

var (
	policyLock    sync.RWMutex
	policyEntries []PolicyEntry
)

// PolicyEntries returns a copy of the captured nameserver-policy, in profile order.
func PolicyEntries() []PolicyEntry {
	policyLock.RLock()
	defer policyLock.RUnlock()

	return append([]PolicyEntry(nil), policyEntries...)
}

func setPolicy(entries []PolicyEntry) {
	policyLock.Lock()
	policyEntries = entries
	policyLock.Unlock()
}

func normPolicyKey(k string) string {
	return strings.ToLower(strings.NewReplacer("-", "", "_", "", " ", "").Replace(k))
}

// parsePolicy reads the nameserver-policy object out of the marshalled `dns:` section, keeping the key order.
func parsePolicy(data []byte) []PolicyEntry {
	dec := json.NewDecoder(bytes.NewReader(data))

	tok, err := dec.Token()
	if d, ok := tok.(json.Delim); err != nil || !ok || d != '{' {
		return nil
	}

	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return nil
		}

		key, _ := keyTok.(string)

		if normPolicyKey(key) != "nameserverpolicy" {
			var skip json.RawMessage

			if dec.Decode(&skip) != nil {
				return nil
			}

			continue
		}

		open, err := dec.Token()
		if d, ok := open.(json.Delim); err != nil || !ok || d != '{' {
			return nil // null or not a map
		}

		var out []PolicyEntry

		for dec.More() {
			kt, err := dec.Token()
			if err != nil {
				return out
			}

			k, _ := kt.(string)

			var raw json.RawMessage

			if dec.Decode(&raw) != nil {
				return out
			}

			var value any

			_ = json.Unmarshal(raw, &value)

			out = append(out, PolicyEntry{Key: k, Servers: flattenStrings(value)})
		}

		return out
	}

	return nil
}

func flattenStrings(v any) []string {
	switch x := v.(type) {
	case string:
		return []string{x}
	case []any:
		var out []string

		for _, item := range x {
			out = append(out, flattenStrings(item)...)
		}

		return out
	}

	return nil
}
