package pki

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/rsa"
	"crypto/tls"
	"crypto/x509"
	"encoding/asn1"
	"encoding/pem"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

// MaxChain is the number of certificates an uploaded chain may have.
const MaxChain = 8

// Refusal is why a certificate of the operator's cannot be used, in words for the
// administrator.
type Refusal struct{ Reason string }

func (r *Refusal) Error() string { return r.Reason }

func refuse(format string, a ...any) error { return &Refusal{Reason: fmt.Sprintf(format, a...)} }

// Own is a certificate chain of the operator's with its private key: uploaded on the
// system page (or with "siplink pki import"), or read from the files named in the
// environment.
type Own struct {
	Chain []*x509.Certificate // the server's certificate first
	Key   crypto.Signer
	// Anchor is the self-signed certificate that ends the chain, or nil.
	Anchor *x509.Certificate
	// Public is true when the chain leads to a CA of the system root store.
	Public bool
	// EmptyAKI lists the positions (1-based) of chain certificates whose
	// AuthorityKeyIdentifier is an empty SEQUENCE, which mbedTLS 3.6.0 refuses.
	EmptyAKI []int

	cert *tls.Certificate
}

// Leaf is the server's certificate.
func (o *Own) Leaf() *x509.Certificate { return o.Chain[0] }

// Covers reports whether the server's certificate names host (a name or an address).
func (o *Own) Covers(host string) bool {
	return host != "" && o.Chain[0].VerifyHostname(host) == nil
}

// Private is the certificate phones pin and get in caCertificates: the self-signed
// anchor of a chain that no public CA vouches for; nil for a public chain.
func (o *Own) Private() *x509.Certificate {
	if o.Public {
		return nil
	}
	return o.Anchor
}

// Names lists the subject alternative names of the server's certificate.
func (o *Own) Names() []string {
	leaf := o.Chain[0]
	out := slices.Clone(leaf.DNSNames)
	for _, ip := range leaf.IPAddresses {
		out = append(out, ip.String())
	}
	return out
}

// ChainPEM writes the chain as PEM, the server's certificate first.
func (o *Own) ChainPEM() []byte {
	var out []byte
	for _, c := range o.Chain {
		out = append(out, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.Raw})...)
	}
	return out
}

// selfSigned reports whether c names itself as issuer and carries its own signature.
func selfSigned(c *x509.Certificate) bool {
	return bytes.Equal(c.RawSubject, c.RawIssuer) && c.CheckSignature(c.SignatureAlgorithm, c.RawTBSCertificate, c.Signature) == nil
}

var oidAuthorityKeyID = asn1.ObjectIdentifier{2, 5, 29, 35}

// emptyAKI reports whether c has an AuthorityKeyIdentifier that is an empty SEQUENCE.
func emptyAKI(c *x509.Certificate) bool {
	for _, e := range c.Extensions {
		if e.Id.Equal(oidAuthorityKeyID) {
			return bytes.Equal(e.Value, []byte{0x30, 0x00})
		}
	}
	return false
}

// ParseOwn reads a chain and its private key the way the upload checks them: PEM that
// parses, an unencrypted RSA or ECDSA key that belongs to the first certificate, and
// every certificate signed by the next one. Time, names and trust are checked by
// Certs.Assess.
func ParseOwn(chainPEM, keyPEM []byte) (*Own, error) {
	var chain []*x509.Certificate
	rest := chainPEM
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			break
		}
		if block.Type != "CERTIFICATE" {
			if strings.Contains(block.Type, "PRIVATE KEY") {
				return nil, refuse("证书链文件中包含私钥，请将私钥作为单独文件上传")
			}
			continue
		}
		c, err := x509.ParseCertificate(block.Bytes)
		if err != nil {
			return nil, refuse("证书链中第 %d 个证书无法解析：%v", len(chain)+1, err)
		}
		chain = append(chain, c)
	}
	switch {
	case len(chain) == 0:
		return nil, refuse("证书链文件中未找到 PEM 证书（应以 -----BEGIN CERTIFICATE----- 开头）")
	case len(chain) > MaxChain:
		return nil, refuse("证书链最多 %d 个证书", MaxChain)
	}
	key, err := parseKey(keyPEM)
	if err != nil {
		return nil, err
	}
	if pub, ok := chain[0].PublicKey.(interface{ Equal(crypto.PublicKey) bool }); !ok || !pub.Equal(key.Public()) {
		return nil, refuse("私钥与证书链中的第一个证书（服务器证书）不匹配：链须以服务器证书开头，私钥须是它的私钥")
	}
	for i := 0; i+1 < len(chain); i++ {
		if selfSigned(chain[i]) {
			return nil, refuse("第 %d 个证书为自签名证书，仅可置于链末尾", i+1)
		}
		if err := chain[i].CheckSignatureFrom(chain[i+1]); err != nil {
			return nil, refuse("第 %d 个证书不是由第 %d 个证书签发的：链须按 服务器证书、中间证书、根证书 的顺序排列（%v）", i+1, i+2, err)
		}
	}
	return newOwn(chain, key), nil
}

// parseKey reads the first private key of a PEM text.
func parseKey(keyPEM []byte) (crypto.Signer, error) {
	rest := keyPEM
	for {
		var block *pem.Block
		block, rest = pem.Decode(rest)
		if block == nil {
			return nil, refuse("私钥文件中未找到 PEM 私钥（应以 -----BEGIN PRIVATE KEY----- 或类似内容开头）")
		}
		if block.Type == "ENCRYPTED PRIVATE KEY" || strings.Contains(block.Headers["Proc-Type"], "ENCRYPTED") {
			return nil, refuse("私钥已加密。请上传未加密的私钥（例如先运行 openssl pkey -in 私钥.pem -out 私钥-未加密.pem）")
		}
		var (
			k   any
			err error
		)
		switch block.Type {
		case "PRIVATE KEY":
			k, err = x509.ParsePKCS8PrivateKey(block.Bytes)
		case "RSA PRIVATE KEY":
			k, err = x509.ParsePKCS1PrivateKey(block.Bytes)
		case "EC PRIVATE KEY":
			k, err = x509.ParseECPrivateKey(block.Bytes)
		default:
			continue // parameters, certificates and the like
		}
		if err != nil {
			return nil, refuse("私钥无法解析：%v", err)
		}
		switch k := k.(type) {
		case *rsa.PrivateKey:
			return k, nil
		case *ecdsa.PrivateKey:
			return k, nil
		}
		return nil, refuse("仅支持 RSA 和 ECDSA 私钥")
	}
}

// ownFromPair turns a key pair loaded from files into an Own. Files are not held to the
// upload's checks (they worked before those existed); only the key match is checked.
func ownFromPair(certFile, keyFile string) (*Own, error) {
	pair, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, fmt.Errorf("load certificate files: %w", err)
	}
	signer, ok := pair.PrivateKey.(crypto.Signer)
	if !ok {
		return nil, errors.New("load certificate files: the key cannot sign")
	}
	var chain []*x509.Certificate
	for _, der := range pair.Certificate {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return nil, fmt.Errorf("load certificate files: %w", err)
		}
		chain = append(chain, c)
	}
	return newOwn(chain, signer), nil
}

func newOwn(chain []*x509.Certificate, key crypto.Signer) *Own {
	o := &Own{Chain: chain, Key: key}
	if last := chain[len(chain)-1]; selfSigned(last) {
		o.Anchor = last
	}
	for i, c := range chain {
		if emptyAKI(c) {
			o.EmptyAKI = append(o.EmptyAKI, i+1)
		}
	}
	raw := make([][]byte, len(chain))
	for i, c := range chain {
		raw[i] = c.Raw
	}
	o.cert = &tls.Certificate{Certificate: raw, PrivateKey: key, Leaf: chain[0]}
	return o
}

// classify records whether the chain leads to a CA of roots at now.
func (o *Own) classify(roots *x509.CertPool, now time.Time) {
	o.Public = roots != nil && verifies(o.Chain, roots, now)
}

// verifies reports whether the server's certificate of chain leads to roots.
func verifies(chain []*x509.Certificate, roots *x509.CertPool, now time.Time) bool {
	inter := x509.NewCertPool()
	for _, c := range chain[1:] {
		inter.AddCert(c)
	}
	_, err := chain[0].Verify(x509.VerifyOptions{Roots: roots, Intermediates: inter, CurrentTime: now,
		KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageAny}})
	return err == nil
}

// serverAuth reports whether a leaf may serve TLS the way the app checks it
// (CertificatePin.check): no extended key usage at all, or one that lists serverAuth.
func serverAuth(leaf *x509.Certificate) bool {
	if len(leaf.ExtKeyUsage) == 0 && len(leaf.UnknownExtKeyUsage) == 0 {
		return true
	}
	return slices.Contains(leaf.ExtKeyUsage, x509.ExtKeyUsageServerAuth)
}
