// Package auth holds administrator passwords, sessions, rate limits and the
// permission matrix.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"
)

// Argon2Params are the argon2id cost parameters.
type Argon2Params struct {
	Memory  uint32 // KiB
	Time    uint32
	Threads uint8
}

// HashParams are used for new hashes (design §6.1: m=64 MiB, t=3, p=2). Tests may
// lower them; verification always reads the parameters from the stored hash.
var HashParams = Argon2Params{Memory: 64 * 1024, Time: 3, Threads: 2}

const (
	saltLen = 16
	keyLen  = 32
)

// MinPasswordLength is the shortest administrator password accepted.
const MinPasswordLength = 10

// ErrBadHash is returned for a stored hash that is not an argon2id PHC string.
var ErrBadHash = errors.New("auth: malformed password hash")

// HashPassword returns an argon2id PHC string.
func HashPassword(password string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	p := HashParams
	key := argon2.IDKey([]byte(password), salt, p.Time, p.Memory, p.Threads, keyLen)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, p.Memory, p.Time, p.Threads,
		b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

// VerifyPassword checks password against a PHC string in constant time.
func VerifyPassword(hash, password string) (bool, error) {
	parts := strings.Split(hash, "$")
	if len(parts) != 6 || parts[1] != "argon2id" {
		return false, ErrBadHash
	}
	var version int
	if _, err := fmt.Sscanf(parts[2], "v=%d", &version); err != nil || version != argon2.Version {
		return false, ErrBadHash
	}
	var p Argon2Params
	if _, err := fmt.Sscanf(parts[3], "m=%d,t=%d,p=%d", &p.Memory, &p.Time, &p.Threads); err != nil {
		return false, ErrBadHash
	}
	if p.Memory == 0 || p.Time == 0 || p.Threads == 0 || p.Memory > 1<<20 || p.Time > 64 {
		return false, ErrBadHash
	}
	b64 := base64.RawStdEncoding
	salt, err1 := b64.DecodeString(parts[4])
	want, err2 := b64.DecodeString(parts[5])
	if err1 != nil || err2 != nil || len(want) == 0 {
		return false, ErrBadHash
	}
	got := argon2.IDKey([]byte(password), salt, p.Time, p.Memory, p.Threads, uint32(len(want)))
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// dummyHash is verified for unknown user names so that their timing matches.
var (
	dummyOnce sync.Once
	dummyHash string
)

// VerifyDummy spends the same time as a real verification.
func VerifyDummy(password string) {
	dummyOnce.Do(func() { dummyHash, _ = HashPassword("siplink-dummy-password") })
	_, _ = VerifyPassword(dummyHash, password)
}

// PasswordProblem returns a message in Chinese when a new administrator password is
// not acceptable, or "". With strict (the switch "密码强度检测" on the system page) it
// must have MinPasswordLength characters and differ from the user name; without, it
// only has to be non-empty. It is never longer than 256 characters.
func PasswordProblem(password, username string, strict bool) string {
	n := utf8.RuneCountInString(password)
	switch {
	case n > 256:
		return "密码不得超过 256 个字符"
	case !strict && n == 0:
		return "请输入新密码"
	case strict && n < MinPasswordLength:
		return fmt.Sprintf("密码至少为 %d 个字符", MinPasswordLength)
	case strict && strings.EqualFold(password, username):
		return "密码不得与用户名相同"
	}
	return ""
}

// PassphraseProblem returns a message in Chinese when the passphrase of an encrypted
// backup download is not acceptable, or "": the same lengths as PasswordProblem.
func PassphraseProblem(passphrase string, strict bool) string {
	n := utf8.RuneCountInString(passphrase)
	switch {
	case n > 256:
		return "加密口令不得超过 256 个字符"
	case n == 0:
		return "请输入加密口令"
	case strict && n < MinPasswordLength:
		return fmt.Sprintf("加密口令至少为 %d 个字符", MinPasswordLength)
	}
	return ""
}

// ValidUsername accepts 2-32 characters of ASCII letters, digits, '.', '_' and '-'.
func ValidUsername(name string) bool {
	if len(name) < 2 || len(name) > 32 {
		return false
	}
	for _, r := range name {
		if !(r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '.' || r == '_' || r == '-') {
			return false
		}
	}
	return true
}
