package ipdelay

// CUSTOM (CUSTOM.md #69, not upstream): the raw `hosts:` of the loaded profile, for "read the IPs of the config" in
// the test-IP dialog. Read only; nothing runs on its own.

// RawHosts returns a copy of the raw `hosts:` mapping of the loaded profile (key -> a string or a list of strings).
func RawHosts() map[string]any {
	lock.RLock()
	defer lock.RUnlock()

	out := make(map[string]any, len(hosts))
	for k, v := range hosts {
		out[k] = v
	}

	return out
}

// ToStrings reads a `hosts:` value, which may be one string or a list of strings.
func ToStrings(v any) []string {
	return toStrings(v)
}
