// Package auth holds administrator passwords, sessions, rate limits and the
// permission matrix.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"sync"
	"unicode/utf8"

	"golang.org/x/crypto/argon2"

	"siplink/internal/i18n"
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

// MaxHashing is how many argon2id computations run at once in the process; each holds
// the memory its parameters name (64 MiB for new hashes).
const MaxHashing = 4

// ErrBusy is returned when no hashing slot became free before the context ended.
var ErrBusy = errors.New("auth: password hashing busy")

var (
	hashSlots = make(chan struct{}, MaxHashing)
	idKey     = argon2.IDKey // tests count the computations through it
)

// derive runs argon2id in one of the MaxHashing slots, waiting for one until ctx ends.
func derive(ctx context.Context, password string, salt []byte, p Argon2Params, n uint32) ([]byte, error) {
	select {
	case hashSlots <- struct{}{}:
	case <-ctx.Done():
		return nil, ErrBusy
	}
	defer func() { <-hashSlots }()
	return idKey([]byte(password), salt, p.Time, p.Memory, p.Threads, n), nil
}

// HashPassword returns an argon2id PHC string. It waits for a hashing slot.
func HashPassword(password string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	p := HashParams
	key, _ := derive(context.Background(), password, salt, p, keyLen)
	b64 := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s", argon2.Version, p.Memory, p.Time, p.Threads,
		b64.EncodeToString(salt), b64.EncodeToString(key)), nil
}

// VerifyPassword checks password against a PHC string in constant time. It waits for a
// hashing slot until ctx ends, then gives up with ErrBusy.
func VerifyPassword(ctx context.Context, hash, password string) (bool, error) {
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
	got, err := derive(ctx, password, salt, p, uint32(len(want)))
	if err != nil {
		return false, err
	}
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}

// dummyHash is verified for unknown user names so that their timing matches.
var (
	dummyOnce sync.Once
	dummyHash string
)

// VerifyDummy spends the same time as a real verification and waits for a slot like
// it; it returns ErrBusy when VerifyPassword would.
func VerifyDummy(ctx context.Context, password string) error {
	dummyOnce.Do(func() { dummyHash, _ = HashPassword("siplink-dummy-password") })
	_, err := VerifyPassword(ctx, dummyHash, password)
	return err
}

// PasswordProblem says why a new administrator password is not acceptable, nil when
// it is. With strict (the switch "密码强度检测" on the system page) it
// must have MinPasswordLength characters and differ from the user name; without, it
// only has to be non-empty. It is never longer than 256 characters.
func PasswordProblem(password, username string, strict bool) error {
	n := utf8.RuneCountInString(password)
	switch {
	case n > 256:
		return i18n.E("auth.password.too_long", "max", 256)
	case !strict && n == 0:
		return i18n.E("auth.password.empty")
	case strict && n < MinPasswordLength:
		return i18n.E("auth.password.too_short", "min", MinPasswordLength)
	case strict && strings.EqualFold(password, username):
		return i18n.E("auth.password.is_username")
	}
	return nil
}

// PassphraseProblem says why the passphrase of an encrypted backup download is not
// acceptable, nil when it is: the same lengths as PasswordProblem.
func PassphraseProblem(passphrase string, strict bool) error {
	n := utf8.RuneCountInString(passphrase)
	switch {
	case n > 256:
		return i18n.E("auth.passphrase.too_long", "max", 256)
	case n == 0:
		return i18n.E("auth.passphrase.empty")
	case strict && n < MinPasswordLength:
		return i18n.E("auth.passphrase.too_short", "min", MinPasswordLength)
	}
	return nil
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
