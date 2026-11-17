// Package twostep holds the rules of the administrators' two-step sign-in (design
// §6.8): TOTP (RFC 6238), one-time e-mail codes, recovery codes and the policy.
package twostep

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// TOTP parameters: HMAC-SHA1, 30-second steps, 6 digits (RFC 6238 defaults, which
// every authenticator app understands).
const (
	TOTPPeriod    = 30
	TOTPDigits    = 6
	totpSecretLen = 20
	// Issuer names the platform in authenticator apps.
	Issuer = "SipLink"
)

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// NewTOTPSecret returns 20 random bytes.
func NewTOTPSecret() []byte {
	b := make([]byte, totpSecretLen)
	if _, err := rand.Read(b); err != nil {
		panic(err) // crypto/rand never fails on supported platforms
	}
	return b
}

// SecretText is the secret as people type it: base32 without padding.
func SecretText(key []byte) string { return b32.EncodeToString(key) }

// TOTPStep is the time step of t.
func TOTPStep(t time.Time) int64 { return t.Unix() / TOTPPeriod }

// TOTPCode is the HOTP value (RFC 4226) of key at counter step with the given number of
// digits, left-padded with zeros.
func TOTPCode(key []byte, step int64, digits int) string {
	var msg [8]byte
	binary.BigEndian.PutUint64(msg[:], uint64(step))
	m := hmac.New(sha1.New, key)
	m.Write(msg[:])
	sum := m.Sum(nil)
	off := sum[len(sum)-1] & 0x0f
	bin := binary.BigEndian.Uint32(sum[off:off+4]) & 0x7fffffff
	mod := uint32(1)
	for range digits {
		mod *= 10
	}
	s := strconv.FormatUint(uint64(bin%mod), 10)
	return strings.Repeat("0", digits-len(s)) + s
}

// MatchTOTP checks a 6-digit code against the steps now-1, now and now+1 and returns
// the matching step. A step not after last (the last step used) is refused, so a code
// is accepted once.
func MatchTOTP(key []byte, code string, now time.Time, last int64) (int64, bool) {
	code = NormalizeCode(code)
	if len(code) != TOTPDigits {
		return 0, false
	}
	cur := TOTPStep(now)
	for _, step := range []int64{cur - 1, cur, cur + 1} {
		if step <= last {
			continue
		}
		if subtle.ConstantTimeCompare([]byte(TOTPCode(key, step, TOTPDigits)), []byte(code)) == 1 {
			return step, true
		}
	}
	return 0, false
}

// TOTPURI is the otpauth:// URI an authenticator app reads from the QR code.
func TOTPURI(account string, key []byte) string {
	q := url.Values{}
	q.Set("secret", SecretText(key))
	q.Set("issuer", Issuer)
	q.Set("algorithm", "SHA1")
	q.Set("digits", strconv.Itoa(TOTPDigits))
	q.Set("period", strconv.Itoa(TOTPPeriod))
	label := url.PathEscape(Issuer + ":" + account)
	return "otpauth://totp/" + label + "?" + q.Encode()
}

// NormalizeCode drops spaces and dashes people type between groups of digits.
func NormalizeCode(code string) string {
	return strings.Map(func(r rune) rune {
		if r == ' ' || r == '-' || r == '\t' {
			return -1
		}
		return r
	}, strings.TrimSpace(code))
}
