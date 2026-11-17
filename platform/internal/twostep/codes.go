package twostep

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"math/big"
	"strconv"
	"strings"
	"time"
)

// One-time e-mail codes (design §6.8).
const (
	CodeDigits   = 6
	CodeTTL      = 10 * time.Minute
	CodeMaxTries = 5
)

// Purposes of e-mail codes.
const (
	PurposeLogin   = "login"
	PurposeConfirm = "confirm"
)

// Recovery codes: ten of ten characters from an alphabet without look-alikes.
const (
	RecoveryCount = 10
	recoveryLen   = 10
	recoveryChars = "abcdefghjkmnpqrstuvwxyz23456789"
)

// NewCode returns a uniformly random code of CodeDigits digits.
func NewCode() string {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		panic(err)
	}
	s := n.String()
	return strings.Repeat("0", CodeDigits-len(s)) + s
}

// CodeHash is the stored form of an e-mail code: HMAC-SHA256 under the platform's code
// key (secret.Keys.CodeKey), over the administrator, the purpose and the code, so that a
// database or backup alone allows no offline search (§6.8).
func CodeHash(key [32]byte, adminID int64, purpose, code string) string {
	return codeMAC(key, adminID, purpose, NormalizeCode(code))
}

// NewRecoveryCodes returns a fresh set, each shown as "xxxxx-xxxxx".
func NewRecoveryCodes() []string {
	out := make([]string, RecoveryCount)
	max := big.NewInt(int64(len(recoveryChars)))
	for i := range out {
		b := make([]byte, recoveryLen)
		for j := range b {
			n, err := rand.Int(rand.Reader, max)
			if err != nil {
				panic(err)
			}
			b[j] = recoveryChars[n.Int64()]
		}
		out[i] = string(b[:recoveryLen/2]) + "-" + string(b[recoveryLen/2:])
	}
	return out
}

// RecoveryHash is the stored form of a recovery code, as typed (case, dashes and
// spaces do not matter), keyed like CodeHash with the purpose "recovery".
func RecoveryHash(key [32]byte, adminID int64, code string) string {
	return codeMAC(key, adminID, purposeRecovery, strings.ToLower(NormalizeCode(code)))
}

const purposeRecovery = "recovery"

func codeMAC(key [32]byte, adminID int64, purpose, code string) string {
	m := hmac.New(sha256.New, key[:])
	m.Write([]byte(strconv.FormatInt(adminID, 10) + ":" + purpose + ":" + code))
	return hex.EncodeToString(m.Sum(nil))
}
