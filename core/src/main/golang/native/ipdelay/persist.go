package ipdelay

// CUSTOM (CUSTOM.md #10, #11): small files under <filesDir>/custom/ that survive restarts of the
// service:
//   - country_cache.json : exit country per "node|ip" (so the flag shows without probing again)
//   - ip_history.json    : the last node IP switches (read by the app's connections page)
// Files are written only when something new was learned (an IP switch, a new country), never on a
// timer, and always through a temp file + rename so a reader never sees a half-written file.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	countryTTL = 7 * 24 * time.Hour // how long a remembered exit country is trusted
	maxStored  = 300                // remembered "node|ip" entries
	maxHistory = 50                 // remembered IP switches
)

type storedCountry struct {
	Country string `json:"c"`
	At      int64  `json:"t"` // unix seconds
}

// HistoryEntry is one switch of the node IP in use.
type HistoryEntry struct {
	At      int64  `json:"t"` // unix milliseconds
	Node    string `json:"node"`
	IP      string `json:"ip"`
	Country string `json:"country,omitempty"`
}

var (
	persistLock   sync.Mutex // never held together with `lock` (take `lock` first if both are needed)
	dataDir       string
	stored        map[string]storedCountry
	storedLoaded  bool
	history       []HistoryEntry
	historyLoaded bool
)

// setDataDir derives <filesDir>/custom from the core's home directory (<filesDir>/clash).
func setDataDir(home string) {
	persistLock.Lock()
	defer persistLock.Unlock()

	dir := ""

	if home != "" {
		dir = filepath.Join(filepath.Dir(filepath.Clean(home)), "custom")
	}

	if dir != dataDir {
		dataDir = dir
		storedLoaded = false
		historyLoaded = false
	}
}

func readJSON(name string, v any) bool {
	if dataDir == "" {
		return false
	}

	data, err := os.ReadFile(filepath.Join(dataDir, name))
	if err != nil {
		return false
	}

	return json.Unmarshal(data, v) == nil
}

func writeJSON(name string, v any) {
	if dataDir == "" {
		return
	}

	data, err := json.Marshal(v)
	if err != nil {
		return
	}

	if os.MkdirAll(dataDir, 0o755) != nil {
		return
	}

	path := filepath.Join(dataDir, name)
	tmp := path + ".tmp"

	if os.WriteFile(tmp, data, 0o644) != nil {
		return
	}

	if os.Rename(tmp, path) != nil {
		_ = os.Remove(tmp)
	}
}

func freshCountry(e storedCountry) bool {
	return validCountry(e.Country) && time.Since(time.Unix(e.At, 0)) < countryTTL
}

func loadStoredLocked() {
	if storedLoaded {
		return
	}

	storedLoaded = true
	stored = map[string]storedCountry{}

	var m map[string]storedCountry

	if !readJSON("country_cache.json", &m) {
		return
	}

	for key, e := range m {
		if freshCountry(e) {
			stored[key] = e
		}
	}
}

// savedCountries returns the remembered exit countries of the nodes in `names`.
func savedCountries(names map[string]bool) map[string]string {
	persistLock.Lock()
	defer persistLock.Unlock()

	loadStoredLocked()

	out := map[string]string{}

	for key, e := range stored {
		i := strings.LastIndex(key, "|")
		if i <= 0 {
			continue
		}

		if names[key[:i]] && freshCountry(e) {
			out[key] = e.Country
		}
	}

	return out
}

// rememberCountries stores newly learned exit countries; the file is rewritten once per call and
// only when something actually changed.
func rememberCountries(found map[string]string) {
	if len(found) == 0 {
		return
	}

	persistLock.Lock()
	defer persistLock.Unlock()

	if dataDir == "" {
		return
	}

	loadStoredLocked()

	now := time.Now().Unix()
	changed := false

	for key, country := range found {
		if old, ok := stored[key]; ok && old.Country == country && now-old.At < 3600 {
			continue // same answer, learned within the last hour: nothing to write
		}

		stored[key] = storedCountry{Country: country, At: now}
		changed = true
	}

	if !changed {
		return
	}

	for key, e := range stored {
		if !freshCountry(e) {
			delete(stored, key)
		}
	}

	if len(stored) > maxStored {
		keys := make([]string, 0, len(stored))

		for key := range stored {
			keys = append(keys, key)
		}

		sort.Slice(keys, func(i, j int) bool { return stored[keys[i]].At > stored[keys[j]].At })

		for _, key := range keys[maxStored:] {
			delete(stored, key)
		}
	}

	writeJSON("country_cache.json", stored)
}

func loadHistoryLocked() {
	if historyLoaded {
		return
	}

	historyLoaded = true
	history = nil

	var list []HistoryEntry

	if readJSON("ip_history.json", &list) {
		if len(list) > maxHistory {
			list = list[len(list)-maxHistory:]
		}

		history = list
	}
}

// recordHistory remembers that `node`/`ip` is now in use. The same node IP is not recorded twice in
// a row; when only its exit country became known afterwards, the last entry is completed.
func recordHistory(node, ip, country string) {
	if node == "" || ip == "" {
		return
	}

	persistLock.Lock()
	defer persistLock.Unlock()

	if dataDir == "" {
		return
	}

	loadHistoryLocked()

	if n := len(history); n > 0 && history[n-1].Node == node && history[n-1].IP == ip {
		if country == "" || history[n-1].Country == country {
			return
		}

		history[n-1].Country = country
	} else {
		history = append(history, HistoryEntry{
			At:      time.Now().UnixMilli(),
			Node:    node,
			IP:      ip,
			Country: country,
		})

		if len(history) > maxHistory {
			history = append([]HistoryEntry(nil), history[len(history)-maxHistory:]...)
		}
	}

	writeJSON("ip_history.json", history)
}
