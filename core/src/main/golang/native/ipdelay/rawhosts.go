package ipdelay

// CUSTOM (CUSTOM.md #69, not upstream): helper for reading a `hosts:` value in the test-IP dialog.
// Read only; nothing runs on its own.

// ToStrings reads a `hosts:` value, which may be one string or a list of strings.
func ToStrings(v any) []string {
	return toStrings(v)
}
