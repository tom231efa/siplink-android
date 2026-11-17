// Package secret holds the key file, the AES-256-GCM secret box and the HMAC
// derivation of LDAP passwords (design §6.1, §4.3).
package secret

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
)

// KeyFileSize is 32 bytes of data-encryption key followed by 32 bytes of LDAP key.
const KeyFileSize = 64

// Keys are the two keys of the key file.
type Keys struct {
	Data [32]byte // AES-256-GCM key for *_enc columns
	LDAP [32]byte // HMAC key for derived LDAP passwords
}

// LoadOrCreate reads the key file, or creates it with mode 0600 when it does not exist.
// created is true when a new file was written.
func LoadOrCreate(path string) (keys *Keys, created bool, err error) {
	raw, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		raw = make([]byte, KeyFileSize)
		if _, err := rand.Read(raw); err != nil {
			return nil, false, err
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			return nil, false, err
		}
		f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
		if err != nil {
			return nil, false, fmt.Errorf("create key file: %w", err)
		}
		if _, err := f.Write(raw); err != nil {
			f.Close()
			return nil, false, err
		}
		if err := f.Close(); err != nil {
			return nil, false, err
		}
		created = true
	} else if err != nil {
		return nil, false, fmt.Errorf("read key file: %w", err)
	}
	if len(raw) != KeyFileSize {
		return nil, false, fmt.Errorf("key file %s has %d bytes, want %d", path, len(raw), KeyFileSize)
	}
	keys = &Keys{}
	copy(keys.Data[:], raw[:32])
	copy(keys.LDAP[:], raw[32:])
	return keys, created, nil
}

// Box encrypts and decrypts column values with AES-256-GCM.
// Ciphertext layout (base64url, no padding): keyID(4) | nonce(12) | sealed.
type Box struct {
	aead  cipher.AEAD
	keyID [4]byte
}

// ErrDecrypt is returned for a ciphertext that does not open with this key and AAD.
var ErrDecrypt = errors.New("secret: cannot decrypt value")

// NewBox makes a box for the data key.
func NewBox(key [32]byte) (*Box, error) {
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	b := &Box{aead: aead}
	sum := sha256.Sum256(key[:])
	copy(b.keyID[:], sum[:4])
	return b, nil
}

// AAD binds a ciphertext to its place: table, row and column.
func AAD(table string, row any, column string) []byte {
	var id string
	switch v := row.(type) {
	case int64:
		id = strconv.FormatInt(v, 10)
	default:
		id = fmt.Sprint(v)
	}
	return []byte(table + "\x00" + id + "\x00" + column)
}

// Seal encrypts plaintext for the given AAD.
func (b *Box) Seal(plaintext, aad []byte) (string, error) {
	nonce := make([]byte, b.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	out := make([]byte, 0, 4+len(nonce)+len(plaintext)+b.aead.Overhead())
	out = append(out, b.keyID[:]...)
	out = append(out, nonce...)
	out = b.aead.Seal(out, nonce, plaintext, aad)
	return base64.RawURLEncoding.EncodeToString(out), nil
}

// Open decrypts a value made by Seal with the same AAD.
func (b *Box) Open(ciphertext string, aad []byte) ([]byte, error) {
	raw, err := base64.RawURLEncoding.DecodeString(ciphertext)
	ns := b.aead.NonceSize()
	if err != nil || len(raw) < 4+ns+b.aead.Overhead() {
		return nil, ErrDecrypt
	}
	if !hmac.Equal(raw[:4], b.keyID[:]) {
		return nil, ErrDecrypt
	}
	plain, err := b.aead.Open(nil, raw[4:4+ns], raw[4+ns:], aad)
	if err != nil {
		return nil, ErrDecrypt
	}
	return plain, nil
}

// LDAPPassword derives the LDAP password of a provisioned client (design §4.3):
// base64url(HMAC-SHA256(ldapKey, publicID ‖ generation))[:32].
func LDAPPassword(ldapKey [32]byte, publicID string, generation int64) string {
	m := hmac.New(sha256.New, ldapKey[:])
	m.Write([]byte(publicID))
	m.Write([]byte{0})
	m.Write([]byte(strconv.FormatInt(generation, 10)))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))[:32]
}

// Token returns n random bytes as base64url without padding.
func Token(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// HashHex returns the lowercase hex SHA-256 of s.
func HashHex(s string) string {
	sum := sha256.Sum256([]byte(s))
	const digits = "0123456789abcdef"
	out := make([]byte, 64)
	for i, c := range sum {
		out[i*2] = digits[c>>4]
		out[i*2+1] = digits[c&15]
	}
	return string(out)
}

// RandomText returns n characters from an alphabet without look-alike characters.
func RandomText(n int) string {
	const alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	out := make([]byte, n)
	buf := make([]byte, 1)
	for i := 0; i < n; {
		if _, err := rand.Read(buf); err != nil {
			panic(err)
		}
		// Rejection sampling keeps the distribution uniform.
		if int(buf[0]) >= 256-256%len(alphabet) {
			continue
		}
		out[i] = alphabet[int(buf[0])%len(alphabet)]
		i++
	}
	return string(out)
}
