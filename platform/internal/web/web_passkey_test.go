package web

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"siplink/internal/config"
	"siplink/internal/store"

	"github.com/go-webauthn/webauthn/protocol/webauthncbor"
	"github.com/go-webauthn/webauthn/protocol/webauthncose"
)

// Passkeys (design §6.8) with a software authenticator made in the test: a P-256 key,
// "none" attestation, assertions signed over authenticatorData ‖ SHA-256(clientDataJSON).

var b64u = base64.RawURLEncoding

type softKey struct {
	t      *testing.T
	key    *ecdsa.PrivateKey
	id     []byte
	origin string
	rpID   string
	count  uint32
}

func newSoftKey(t *testing.T) *softKey {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	id := make([]byte, 16)
	rand.Read(id)
	return &softKey{t: t, key: key, id: id, origin: "https://platform.example.com", rpID: "platform.example.com"}
}

func (k *softKey) clientData(typ, challenge string) []byte {
	b, _ := json.Marshal(map[string]any{"type": typ, "challenge": challenge, "origin": k.origin, "crossOrigin": false})
	return b
}

func (k *softKey) authData(flags byte, attested []byte) []byte {
	rp := sha256.Sum256([]byte(k.rpID))
	out := append(rp[:], flags)
	out = binary.BigEndian.AppendUint32(out, k.count)
	return append(out, attested...)
}

// options posts to an options endpoint and returns the token and the publicKey object.
func passkeyOptions(t *testing.T, c *client, path string) (string, map[string]any) {
	t.Helper()
	r := c.postBody(path, "application/json", []byte("{}"))
	var out struct {
		Token   string `json:"token"`
		Options struct {
			PublicKey map[string]any `json:"publicKey"`
		} `json:"options"`
	}
	if r.code != 200 || json.Unmarshal([]byte(r.body), &out) != nil || out.Token == "" {
		t.Fatalf("%s: %d %s", path, r.code, r.body)
	}
	return out.Token, out.Options.PublicKey
}

func (k *softKey) register(c *client, name string) resp {
	k.t.Helper()
	token, pk := passkeyOptions(k.t, c, "/me/passkeys/options")
	if rp := pk["rp"].(map[string]any); rp["id"] != "platform.example.com" {
		k.t.Fatalf("rp %v", rp)
	}
	cose, err := webauthncbor.Marshal(webauthncose.EC2PublicKeyData{
		PublicKeyData: webauthncose.PublicKeyData{KeyType: int64(webauthncose.EllipticKey), Algorithm: int64(webauthncose.AlgES256)},
		Curve:         int64(webauthncose.P256), XCoord: k.key.X.FillBytes(make([]byte, 32)), YCoord: k.key.Y.FillBytes(make([]byte, 32)),
	})
	if err != nil {
		k.t.Fatal(err)
	}
	attested := make([]byte, 16) // AAGUID
	attested = binary.BigEndian.AppendUint16(attested, uint16(len(k.id)))
	attested = append(append(attested, k.id...), cose...)
	obj, err := webauthncbor.Marshal(map[string]any{"fmt": "none", "attStmt": map[string]any{},
		"authData": k.authData(0x45, attested)}) // UP | UV | AT
	if err != nil {
		k.t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"token": token, "name": name, "credential": map[string]any{
		"id": b64u.EncodeToString(k.id), "rawId": b64u.EncodeToString(k.id), "type": "public-key",
		"response": map[string]any{"clientDataJSON": b64u.EncodeToString(k.clientData("webauthn.create", pk["challenge"].(string))),
			"attestationObject": b64u.EncodeToString(obj)}}})
	return c.postBody("/me/passkeys", "application/json", body)
}

func (k *softKey) signIn(c *client, next string) resp {
	k.t.Helper()
	token, pk := passkeyOptions(k.t, c, "/login/step/passkey/options")
	allowed, _ := pk["allowCredentials"].([]any)
	if len(allowed) == 0 {
		k.t.Fatalf("no allowed credentials: %v", pk)
	}
	k.count++
	auth := k.authData(0x05, nil) // UP | UV
	cd := k.clientData("webauthn.get", pk["challenge"].(string))
	sum := sha256.Sum256(cd)
	digest := sha256.Sum256(append(append([]byte{}, auth...), sum[:]...))
	sig, err := ecdsa.SignASN1(rand.Reader, k.key, digest[:])
	if err != nil {
		k.t.Fatal(err)
	}
	body, _ := json.Marshal(map[string]any{"token": token, "next": next, "credential": map[string]any{
		"id": b64u.EncodeToString(k.id), "rawId": b64u.EncodeToString(k.id), "type": "public-key",
		"response": map[string]any{"clientDataJSON": b64u.EncodeToString(cd), "authenticatorData": b64u.EncodeToString(auth),
			"signature": b64u.EncodeToString(sig)}}})
	return c.postBody("/login/step/passkey", "application/json", body)
}

func redirectOf(t *testing.T, r resp) string {
	var out struct{ Redirect, Error string }
	if err := json.Unmarshal([]byte(r.body), &out); err != nil {
		t.Fatalf("%d %s", r.code, r.body)
	}
	return out.Redirect
}

func TestPasskeySignIn(t *testing.T) {
	h := newHarness(t)
	root, a := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/me"); !strings.Contains(r.body, "data-passkey-register") {
		t.Fatal("passkeys not offered")
	}
	k := newSoftKey(t)
	r := k.register(root, "办公室电脑")
	if r.code != 200 || !strings.HasPrefix(redirectOf(t, r), "/me/recovery?t=") {
		t.Fatalf("register: %d %s", r.code, r.body)
	}
	if r := k.register(root, "again"); r.code != http.StatusBadRequest {
		t.Errorf("same credential twice: %d", r.code) // excluded, and the id is unique
	}
	keys, _ := h.st.Passkeys(t.Context(), a.ID)
	if len(keys) != 1 || keys[0].Name != "办公室电脑" {
		t.Fatalf("stored %+v", keys)
	}
	if page := root.get("/me"); !strings.Contains(page.body, "办公室电脑") {
		t.Error("passkey not listed")
	}

	c := h.client()
	signInPending(t, c, "root")
	if page := c.get("/login/step"); !strings.Contains(page.body, "使用通行密钥") || !strings.Contains(page.body, "passkey.js") {
		t.Error("step page without the passkey button")
	}
	r = k.signIn(c, "/clients")
	if r.code != 200 || redirectOf(t, r) != "/clients" {
		t.Fatalf("passkey sign-in: %d %s", r.code, r.body)
	}
	if r := c.get("/clients"); r.code != 200 {
		t.Fatalf("signed in: %d", r.code)
	}
	keys, _ = h.st.Passkeys(t.Context(), a.ID)
	if keys[0].LastUsedAt == "" || !strings.Contains(keys[0].CredentialJSON, `"signCount":1`) {
		t.Errorf("use not recorded: %+v", keys[0])
	}

	// A counter that does not grow (a cloned key) is refused.
	d := h.client()
	signInPending(t, d, "root")
	k.count--
	if r := k.signIn(d, "/"); r.code != http.StatusUnauthorized {
		t.Errorf("counter went back: %d", r.code)
	}
	// Another origin is refused.
	k.origin = "https://evil.example.com"
	if r := k.signIn(d, "/"); r.code != http.StatusUnauthorized {
		t.Errorf("foreign origin: %d", r.code)
	}
	k.origin = "https://platform.example.com"
	// A token belongs to its ceremony: replaying a finished one fails.
	if r := d.postBody("/login/step/passkey", "application/json", []byte(`{"token":"x","credential":{}}`)); r.code != http.StatusBadRequest {
		t.Errorf("unknown token: %d", r.code)
	}
	if n := auditActions(t, h)["login.second_failure"]; n != 2 {
		t.Errorf("%d failures", n)
	}

	// The last passkey goes only with the password; the recovery codes go with it.
	del := "/me/passkeys/" + itoa(keys[0].ID) + "/delete"
	root.post(del, nil)
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.Passkeys != 1 {
		t.Fatal("last passkey deleted without the password")
	}
	root.post(del, url.Values{"password": {"password-root"}})
	if ts, _ := h.st.TwoStepState(t.Context(), a.ID); ts.Passkeys != 0 || ts.RecoveryLeft != 0 {
		t.Errorf("after delete: %+v", ts)
	}
	if acts := auditActions(t, h); acts["passkey.create"] != 1 || acts["passkey.delete"] != 1 {
		t.Errorf("audit %v", acts)
	}
}

// TestPasskeysNeedSecureContext: a plain-http public URL that is not localhost offers no
// passkeys, nor does an address; http://localhost does.
func TestPasskeysNeedSecureContext(t *testing.T) {
	h := newHarness(t, func(c *config.Config) {
		c.PublicURL, c.PublicHost = "http://platform.example.com", "platform.example.com"
	})
	root, _ := h.loggedIn("root", store.RoleSuper)
	if r := root.get("/me"); strings.Contains(r.body, "data-passkey-register") || strings.Contains(r.body, "通行密钥") {
		t.Error("passkeys offered without a secure context")
	}
	if r := root.postBody("/me/passkeys/options", "application/json", []byte("{}")); r.code != http.StatusNotFound {
		t.Errorf("options: %d", r.code)
	}
	ip := newHarness(t, func(c *config.Config) { c.PublicURL, c.PublicHost = "https://192.0.2.10", "192.0.2.10" })
	iroot, _ := ip.loggedIn("root", store.RoleSuper)
	if r := iroot.get("/me"); strings.Contains(r.body, "data-passkey-register") {
		t.Error("passkeys offered at an address")
	}
	local := newHarness(t, func(c *config.Config) { c.PublicURL, c.PublicHost = "http://localhost:18082", "localhost" })
	lroot, _ := local.loggedIn("root", store.RoleSuper)
	_, pk := passkeyOptions(t, lroot, "/me/passkeys/options")
	if rp := pk["rp"].(map[string]any); rp["id"] != "localhost" {
		t.Errorf("rp %v", rp)
	}
}
