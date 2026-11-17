package provision

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

// Version is the lowercase hex SHA-256 of the canonical JSON of the document without
// version, generatedAt and deviceKey: keys sorted, no whitespace (§3.5.1).
func Version(d *Document) (string, error) {
	c := *d
	c.Version, c.GeneratedAt, c.DeviceKey = "", "", ""
	b, err := Canonical(&c)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:]), nil
}

// Canonical returns JSON with object keys sorted and no insignificant whitespace; the
// three excluded fields are dropped when empty.
func Canonical(d *Document) ([]byte, error) {
	raw, err := marshal(d)
	if err != nil {
		return nil, err
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var v map[string]any
	if err := dec.Decode(&v); err != nil {
		return nil, err
	}
	for _, k := range []string{"version", "generatedAt", "deviceKey"} {
		if s, _ := v[k].(string); s == "" {
			delete(v, k)
		}
	}
	return marshal(v) // maps are written with sorted keys
}

// Marshal is the JSON the phone receives (UTF-8, no HTML escaping).
func Marshal(d *Document) ([]byte, error) { return marshal(d) }

func marshal(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimRight(buf.Bytes(), "\n"), nil
}
