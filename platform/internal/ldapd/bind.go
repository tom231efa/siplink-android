package ldapd

import (
	"context"
	"crypto/subtle"
	"errors"
	"time"

	ber "github.com/go-asn1-ber/asn1-ber"

	"siplink/internal/secret"
	"siplink/internal/store"
)

// identity is who a connection is bound as, with what its bind was checked against:
// a reset credential changes it, and so does a new account that reuses a deleted one's id.
type identity struct {
	kind    int // idAnonymous, idAccount, idClient
	account int64
	hash    string // the account's password hash at the bind
	client  string // public id
	gen     int64  // the client's ldap_generation at the bind
}

const (
	idAnonymous = iota
	idAccount
	idClient
)

// permittedBooks returns the books the identity may read (design §4.3). It is looked up
// again per search: when the account or client is gone, disabled or no longer active, or
// its credential was reset since the bind, the identity becomes anonymous for good.
func (s *Server) permittedBooks(ctx context.Context, id *identity) ([]*store.Book, error) {
	switch id.kind {
	case idAccount:
		books, ok, err := s.st.BooksOfAccount(ctx, id.account, id.hash)
		if err != nil || ok {
			return books, err
		}
	case idClient:
		gen, books, ok, err := s.st.ClientBooks(ctx, id.client)
		if err != nil || ok && gen == id.gen {
			return books, err
		}
	}
	*id = identity{}
	return s.st.AnonymousBooks(ctx)
}

// bind handles a BindRequest: simple bind of a provisioned client, a service account
// or anonymous.
func (c *conn) bind(m *message) bool {
	c.ident = identity{} // a new bind starts anonymous whatever the outcome
	op := m.op
	if len(op.Children) < 3 {
		return c.send(m.id, result(opBindResponse, rcProtocolError, "", "malformed bind"))
	}
	if v, _ := op.Children[0].Value.(int64); v != 3 {
		return c.send(m.id, result(opBindResponse, rcProtocolError, "", "only LDAPv3 is supported"))
	}
	name := string(raw(op.Children[1]))
	cred := op.Children[2]
	if cred.ClassType != ber.ClassContext || cred.Tag != 0 {
		return c.send(m.id, result(opBindResponse, rcAuthMethodNotSupported, "", "only simple bind is supported"))
	}
	password := raw(cred)
	if name == "" && len(password) == 0 {
		return c.send(m.id, result(opBindResponse, rcSuccess, "", "")) // anonymous
	}
	if c.s.cfg.RequireTLS && !c.tls {
		// The password is not even looked at before TLS.
		return c.send(m.id, result(opBindResponse, rcConfidentialityRequired, "", "use StartTLS before binding"))
	}
	if len(password) == 0 {
		return c.send(m.id, result(opBindResponse, rcUnwillingToPerform, "", "unauthenticated bind not allowed"))
	}
	ipKey := c.ip.String()
	if c.s.fails.Exceeded(ipKey) {
		time.Sleep(c.s.cfg.SlowDelay)
		return c.send(m.id, result(opBindResponse, rcInvalidCredentials, "", ""))
	}
	ctx, cancel := context.WithTimeout(context.Background(), c.s.cfg.OpTimeout)
	defer cancel()
	id, ok, err := c.s.checkBind(ctx, name, string(password))
	if err != nil {
		c.s.log.Error("ldap bind lookup failed", "err", err)
		return c.send(m.id, result(opBindResponse, rcBusy, "", ""))
	}
	if !ok {
		c.s.fails.Add(ipKey)
		time.Sleep(c.s.cfg.FailDelay)
		return c.send(m.id, result(opBindResponse, rcInvalidCredentials, "", ""))
	}
	c.ident = id
	return c.send(m.id, result(opBindResponse, rcSuccess, "", ""))
}

// checkBind verifies a DN and password.
func (s *Server) checkBind(ctx context.Context, dn, password string) (identity, bool, error) {
	if pub := s.dit.clientID(dn); pub != "" {
		gen, _, ok, err := s.st.ClientBooks(ctx, pub)
		if err != nil || !ok {
			return identity{}, false, err
		}
		want := secret.LDAPPassword(s.cfg.LDAPKey, pub, gen)
		if subtle.ConstantTimeCompare([]byte(password), []byte(want)) != 1 {
			return identity{}, false, nil
		}
		return identity{kind: idClient, client: pub, gen: gen}, true, nil
	}
	name := s.dit.accountName(dn)
	if name == "" {
		return identity{}, false, nil
	}
	a, err := s.st.LDAPAccountByName(ctx, name)
	if errors.Is(err, store.ErrNotFound) {
		return identity{}, false, nil
	}
	if err != nil {
		return identity{}, false, err
	}
	// High-entropy secret: SHA-256 is right and keeps binds cheap (design §4.3).
	if a.Disabled || subtle.ConstantTimeCompare([]byte(secret.HashHex(password)), []byte(a.PasswordHash)) != 1 {
		return identity{}, false, nil
	}
	return identity{kind: idAccount, account: a.ID, hash: a.PasswordHash}, true, nil
}
