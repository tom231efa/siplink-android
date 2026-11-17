package secret

import (
	"os"
	"path/filepath"
	"testing"
)

func TestKeyFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "key")
	k1, created, err := LoadOrCreate(path)
	if err != nil || !created {
		t.Fatalf("create: %v %v", created, err)
	}
	st, _ := os.Stat(path)
	if st.Mode().Perm() != 0o600 || st.Size() != KeyFileSize {
		t.Errorf("mode %v size %d", st.Mode(), st.Size())
	}
	k2, created, err := LoadOrCreate(path)
	if err != nil || created || *k1 != *k2 {
		t.Fatalf("reload: %v %v", created, err)
	}
	if k1.Data == k1.LDAP {
		t.Error("data and LDAP keys equal")
	}
	if ck := k1.CodeKey(); ck == k1.Data || ck == k1.LDAP || ck != k2.CodeKey() {
		t.Error("code key not derived apart from the other keys, or not stable")
	}
	os.WriteFile(path, []byte("short"), 0o600)
	if _, _, err := LoadOrCreate(path); err == nil {
		t.Error("short key file accepted")
	}
}

func TestBox(t *testing.T) {
	var key, other [32]byte
	key[0], other[0] = 1, 2
	b, _ := NewBox(key)
	aad := AAD("client_credentials", int64(7), "password_enc")
	ct, err := b.Seal([]byte("geheim"), aad)
	if err != nil {
		t.Fatal(err)
	}
	ct2, _ := b.Seal([]byte("geheim"), aad)
	if ct == ct2 {
		t.Error("same nonce twice")
	}
	if p, err := b.Open(ct, aad); err != nil || string(p) != "geheim" {
		t.Fatalf("round trip: %q %v", p, err)
	}
	if _, err := b.Open(ct, AAD("client_credentials", int64(8), "password_enc")); err != ErrDecrypt {
		t.Error("AAD mismatch accepted")
	}
	b2, _ := NewBox(other)
	if _, err := b2.Open(ct, aad); err != ErrDecrypt {
		t.Error("other key accepted")
	}
	for _, bad := range []string{"", "!!", "AAAA"} {
		if _, err := b.Open(bad, aad); err != ErrDecrypt {
			t.Errorf("%q accepted", bad)
		}
	}
}

func TestLDAPPassword(t *testing.T) {
	var k [32]byte
	p1 := LDAPPassword(k, "abcdefghij", 1)
	if len(p1) != 32 || p1 != LDAPPassword(k, "abcdefghij", 1) {
		t.Fatalf("unstable or wrong length: %q", p1)
	}
	if p1 == LDAPPassword(k, "abcdefghij", 2) || p1 == LDAPPassword(k, "abcdefghik", 1) {
		t.Error("generation or id does not change the password")
	}
	k[0] = 1
	if p1 == LDAPPassword(k, "abcdefghij", 1) {
		t.Error("key does not change the password")
	}
}

func TestRandom(t *testing.T) {
	if len(Token(32)) != 43 {
		t.Error("token length")
	}
	if s := RandomText(16); len(s) != 16 || s == RandomText(16) {
		t.Error("random text")
	}
	if HashHex("abc") != "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" {
		t.Error("sha256 hex")
	}
}
