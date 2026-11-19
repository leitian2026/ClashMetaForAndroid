package ipdelay

// CUSTOM (CUSTOM.md #69, not upstream): helper for reading a `hosts:` value in the test-IP dialog.
// Read only; nothing runs on its own.

// RawHosts returns a read-only snapshot of the profile hosts keys and values.
func RawHosts() map[string]any {
	lock.RLock()
	defer lock.RUnlock()

	out := make(map[string]any, len(hosts))
	for key, value := range hosts {
		out[key] = value
	}

	return out
}

// ToStrings reads a hosts value, which may be one string or a list of strings.
func ToStrings(v any) []string {
	return toStrings(v)
}
