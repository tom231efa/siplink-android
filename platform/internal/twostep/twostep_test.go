package twostep

import (
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"
)

// TestTOTPVectors: the SHA-1 test vectors of RFC 6238, appendix B (8 digits).
func TestTOTPVectors(t *testing.T) {
	key := []byte("12345678901234567890")
	for _, v := range []struct {
		unix int64
		code string
	}{
		{59, "94287082"}, {1111111109, "07081804"}, {1111111111, "14050471"},
		{1234567890, "89005924"}, {2000000000, "69279037"}, {20000000000, "65353130"},
	} {
		if got := TOTPCode(key, TOTPStep(time.Unix(v.unix, 0)), 8); got != v.code {
			t.Errorf("T=%d: %s, want %s", v.unix, got, v.code)
		}
	}
	// Six digits are the last six of the same value.
	if got := TOTPCode(key, TOTPStep(time.Unix(59, 0)), 6); got != "287082" {
		t.Errorf("6 digits: %s", got)
	}
}

func TestTOTPWindowAndReplay(t *testing.T) {
	key := NewTOTPSecret()
	now := time.Unix(1_800_000_000, 0)
	cur := TOTPStep(now)
	for _, d := range []int64{-1, 0, 1} {
		code := TOTPCode(key, cur+d, TOTPDigits)
		step, ok := MatchTOTP(key, code[:3]+" "+code[3:], now, 0)
		if !ok || step != cur+d {
			t.Errorf("step %+d refused", d)
		}
	}
	for _, d := range []int64{-2, 2} {
		if _, ok := MatchTOTP(key, TOTPCode(key, cur+d, TOTPDigits), now, 0); ok {
			t.Errorf("step %+d accepted", d)
		}
	}
	code := TOTPCode(key, cur, TOTPDigits)
	if _, ok := MatchTOTP(key, code, now, cur); ok {
		t.Error("a used step accepted again")
	}
	if _, ok := MatchTOTP(key, TOTPCode(key, cur+1, TOTPDigits), now, cur); !ok {
		t.Error("the next step refused after use of the current one")
	}
	for _, bad := range []string{"", "12345", "1234567", "abcdef"} {
		if _, ok := MatchTOTP(key, bad, now, 0); ok {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestTOTPURI(t *testing.T) {
	key := []byte("12345678901234567890")
	u, err := url.Parse(TOTPURI("zhang.san", key))
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if u.Scheme != "otpauth" || u.Host != "totp" || u.Path != "/SipLink:zhang.san" || q.Get("secret") != "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" ||
		q.Get("issuer") != "SipLink" || q.Get("digits") != "6" || q.Get("period") != "30" || q.Get("algorithm") != "SHA1" {
		t.Errorf("uri %s", u)
	}
	if len(NewTOTPSecret()) != 20 || string(NewTOTPSecret()) == string(NewTOTPSecret()) {
		t.Error("secret length or randomness")
	}
}

func TestCodes(t *testing.T) {
	seen := map[string]bool{}
	for range 200 {
		c := NewCode()
		if !regexp.MustCompile(`^\d{6}$`).MatchString(c) {
			t.Fatalf("code %q", c)
		}
		seen[c] = true
	}
	if len(seen) < 190 {
		t.Errorf("only %d distinct codes of 200", len(seen))
	}
	var key, other [32]byte
	copy(key[:], "code-key-code-key-code-key-code!")
	copy(other[:], "another-key-another-key-another!")
	h := CodeHash(key, 7, PurposeLogin, "123456")
	if h != CodeHash(key, 7, PurposeLogin, "123 456") || h == CodeHash(key, 8, PurposeLogin, "123456") ||
		h == CodeHash(key, 7, PurposeConfirm, "123456") || h == CodeHash(other, 7, PurposeLogin, "123456") ||
		strings.Contains(h, "123456") || len(h) != 64 {
		t.Error("code hash not bound to key, administrator and purpose")
	}
}

func TestRecoveryCodes(t *testing.T) {
	codes := NewRecoveryCodes()
	if len(codes) != RecoveryCount {
		t.Fatalf("%d codes", len(codes))
	}
	format := regexp.MustCompile(`^[a-hj-km-np-z2-9]{5}-[a-hj-km-np-z2-9]{5}$`)
	seen := map[string]bool{}
	for _, c := range codes {
		if !format.MatchString(c) || seen[c] {
			t.Errorf("code %q", c)
		}
		seen[c] = true
	}
	var key [32]byte
	copy(key[:], "code-key-code-key-code-key-code!")
	c := codes[0]
	h := RecoveryHash(key, 7, c)
	if RecoveryHash(key, 7, strings.ToUpper(strings.ReplaceAll(c, "-", " "))) != h || RecoveryHash(key, 7, codes[1]) == h {
		t.Error("recovery hash normalisation")
	}
	// The same code of another administrator, or under another key, hashes differently:
	// a stolen table cannot be searched for all administrators at once.
	if RecoveryHash(key, 8, c) == h || RecoveryHash([32]byte{}, 7, c) == h {
		t.Error("recovery hash not bound to key and administrator")
	}
}

func TestPolicy(t *testing.T) {
	for _, c := range []struct {
		region, required bool
		methods          int
		setUp            bool
	}{
		{true, true, 0, true}, {true, true, 1, false}, {true, false, 0, false},
		{false, true, 0, false}, {false, false, 0, false},
	} {
		if got := MustSetUp(c.region, c.required, c.methods); got != c.setUp {
			t.Errorf("MustSetUp(%v,%v,%d) = %v", c.region, c.required, c.methods, got)
		}
	}
	if MayRemove(true, true, 0) || !MayRemove(true, true, 1) || !MayRemove(false, true, 0) || !MayRemove(true, false, 0) {
		t.Error("MayRemove")
	}
}

func TestPasskeysUsable(t *testing.T) {
	for u, want := range map[string]bool{
		"https://platform.example.com": true, "http://localhost:18082": true, "http://app.localhost": true,
		"http://127.0.0.1:8080": false, "http://[::1]:8080": false, "http://platform.example.com": false,
		"http://192.0.2.10": false, "https://192.0.2.10": false,
	} {
		if PasskeysUsable(u) != want {
			t.Errorf("%s: %v", u, !want)
		}
	}
	for in, want := range map[string]string{
		"https://p.example.com:443": "https://p.example.com", "https://p.example.com:8443": "https://p.example.com:8443",
		"http://localhost:18082": "http://localhost:18082", "http://localhost:80": "http://localhost",
	} {
		if got := Origin(in); got != want {
			t.Errorf("Origin(%s) = %s", in, got)
		}
	}
}
