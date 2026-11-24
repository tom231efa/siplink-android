package ldapd

import (
	"bufio"
	"bytes"
	"errors"
	"strings"
	"testing"

	ber "github.com/go-asn1-ber/asn1-ber"
	"github.com/go-ldap/ldap/v3"
)

// with appends children to a constructed packet.
func with(p *ber.Packet, children ...*ber.Packet) *ber.Packet {
	for _, c := range children {
		p.AppendChild(c)
	}
	return p
}

func app(tag int, children ...*ber.Packet) *ber.Packet {
	return with(ber.Encode(ber.ClassApplication, ber.TypeConstructed, ber.Tag(tag), nil, ""), children...)
}

func seq(children ...*ber.Packet) *ber.Packet { return with(ber.NewSequence(""), children...) }

func setOf(children ...*ber.Packet) *ber.Packet {
	return with(ber.Encode(ber.ClassUniversal, ber.TypeConstructed, ber.TagSet, nil, ""), children...)
}

func integer(tag ber.Tag, v int64) *ber.Packet {
	return ber.NewInteger(ber.ClassUniversal, ber.TypePrimitive, tag, v, "")
}

func boolean(v bool) *ber.Packet {
	return ber.NewBoolean(ber.ClassUniversal, ber.TypePrimitive, ber.TagBoolean, v, "")
}

// wire encodes a request as a client sends it, with optional controls.
func wire(op *ber.Packet, controls ...*ber.Packet) []byte {
	p := envelope(1, op)
	if len(controls) > 0 {
		p.AppendChild(with(ber.Encode(ber.ClassContext, ber.TypeConstructed, 0, nil, ""), controls...))
	}
	return p.Bytes()
}

func searchOp(t testing.TB, filter string, attrs ...string) *ber.Packet {
	t.Helper()
	f, err := ldap.CompileFilter(filter)
	if err != nil {
		t.Fatal(err)
	}
	list := seq()
	for _, a := range attrs {
		list.AppendChild(octets(a))
	}
	return app(opSearchRequest, octets(bookBase("staff")), integer(ber.TagEnumerated, scopeSub),
		integer(ber.TagEnumerated, 0), integer(ber.TagInteger, 51), integer(ber.TagInteger, 5), boolean(false), f, list)
}

// nested returns a filter of depth+1 levels of not around an equality.
func nested(depth int) string {
	return strings.Repeat("(!", depth) + "(cn=x)" + strings.Repeat(")", depth)
}

// wideOr returns an or of n equality terms.
func wideOr(n int) string {
	return "(|" + strings.Repeat("(cn=*x*)", n) + ")"
}

// wellFormed: the requests the server handles, as clients encode them.
func wellFormed(t testing.TB) map[string][]byte {
	critical := seq(octets("1.2.3.4"), boolean(true), octets("v"))
	return map[string][]byte{
		"simple bind": wire(app(opBindRequest, integer(ber.TagInteger, 3), octets(accountDN("phone1")),
			ber.NewString(ber.ClassContext, ber.TypePrimitive, 0, strings.Repeat("p", 32), ""))),
		"anonymous bind": wire(app(opBindRequest, integer(ber.TagInteger, 3), octets(""),
			ber.NewString(ber.ClassContext, ber.TypePrimitive, 0, "", ""))),
		"sasl bind": wire(app(opBindRequest, integer(ber.TagInteger, 3), octets(""),
			ber.Encode(ber.ClassContext, ber.TypeConstructed, 3, nil, ""))),
		"unbind":      wire(ber.Encode(ber.ClassApplication, ber.TypePrimitive, opUnbindRequest, nil, "")),
		"abandon":     wire(ber.NewInteger(ber.ClassApplication, ber.TypePrimitive, opAbandonRequest, 7, "")),
		"sdk search":  wire(searchOp(t, sdk(sdkName, "张"), "cn", "telephoneNumber", "mobile")),
		"all filters": wire(searchOp(t, `(&(objectClass=*)(!(cn~=x))(|(cn>=a)(cn<=z))(cn=a*b*c)(cn:caseExactMatch:=x))`)),
		"root dse":    wire(searchOp(t, "(objectClass=*)", "+", "*", "1.1")),
		"deep filter": wire(searchOp(t, nested(maxFilterDepth))),
		"wide filter": wire(searchOp(t, wideOr(maxFilterTerms-1))),
		"controls":    wire(searchOp(t, "(cn=x)"), critical, seq(octets("1.2.840.113556.1.4.319"), octets("\x30\x05\x02\x01\x05\x04\x00"))),
		"starttls":    wire(app(opExtendedRequest, ber.NewString(ber.ClassContext, ber.TypePrimitive, 0, OIDStartTLS, ""))),
		"add":         wire(app(opAddRequest, octets("cn=x,dc=siplink"), seq(seq(octets("cn"), setOf(octets("x")))))),
		"modify":      wire(app(opModifyRequest, octets("cn=x,dc=siplink"), seq(seq(integer(ber.TagEnumerated, 2), seq(octets("cn"), setOf(octets("y"))))))),
		"delete":      wire(ber.NewString(ber.ClassApplication, ber.TypePrimitive, opDelRequest, "cn=x,dc=siplink", "")),
		"modify dn":   wire(app(opModDNRequest, octets("cn=x,dc=siplink"), octets("cn=y"), boolean(true))),
		"compare":     wire(app(opCompareRequest, octets("cn=x,dc=siplink"), seq(octets("cn"), octets("x")))),
		"long length": wire(searchOp(t, "(cn="+strings.Repeat("x", 70000)+")")),
		"many attrs":  wire(searchOp(t, "(cn=x)", strings.Split(strings.Repeat("cn,", 1000), ",")...)),
	}
}

func TestShapeWellFormed(t *testing.T) {
	for name, msg := range wellFormed(t) {
		if err := checkShape(msg); err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		p, err := readMessage(bufio.NewReader(bytes.NewReader(msg)))
		if err != nil {
			t.Errorf("%s: read: %v", name, err)
			continue
		}
		if _, err := parseMessage(p); err != nil {
			t.Errorf("%s: parse: %v", name, err)
		}
	}
}

// hdr is a tag and a definite length in the shortest form.
func hdr(tag byte, n int) []byte {
	switch {
	case n < 0x80:
		return []byte{tag, byte(n)}
	case n < 0x100:
		return []byte{tag, 0x81, byte(n)}
	case n < 0x10000:
		return []byte{tag, 0x82, byte(n >> 8), byte(n)}
	}
	return []byte{tag, 0x83, byte(n >> 16), byte(n >> 8), byte(n)}
}

// outer wraps content in the LDAPMessage sequence after a message id.
func outer(content ...[]byte) []byte {
	body := []byte{0x02, 0x01, 0x01}
	for _, c := range content {
		body = append(body, c...)
	}
	return append(hdr(0x30, len(body)), body...)
}

// nulls is a search-like operation of n empty elements.
func nulls(n int) []byte {
	return append(hdr(0x63, 2*n), bytes.Repeat([]byte{0x05, 0x00}, n)...)
}

// depthOf returns n levels of sequences, the innermost holding a null.
func depthOf(n int) []byte {
	b := []byte{0x05, 0x00}
	for i := 0; i < n-1; i++ {
		b = append(hdr(0x30, len(b)), b...)
	}
	return b
}

func TestShapeLimits(t *testing.T) {
	// Outer sequence, message id, operation: 3 elements before the nulls; the outer
	// sequence is the first level before the nested ones.
	for _, tc := range []struct {
		name string
		msg  []byte
		ok   bool
	}{
		{"elements at the limit", outer(nulls(maxElements - 3)), true},
		{"one element over", outer(nulls(maxElements - 2)), false},
		{"130,000 nulls in 256 KiB", outer(nulls((MaxMessageSize - 16) / 2)), false},
		{"depth at the limit", outer(depthOf(maxDepth - 1)), true},
		{"one level deeper", outer(depthOf(maxDepth)), false},
		{"indefinite inner length", outer([]byte{0x63, 0x80, 0x05, 0x00, 0x00, 0x00}), false},
		{"inner length past its parent", outer([]byte{0x63, 0x03, 0x04, 0x05, 0x41}), false},
		{"inner past the message", outer([]byte{0x04, 0x7f, 0x41}), false},
		{"inner 4-octet length overflowing", outer([]byte{0x04, 0x84, 0xff, 0xff, 0xff, 0xff, 0x41}), false},
		{"5 length octets", outer([]byte{0x04, 0x85, 0, 0, 0, 0, 1, 0x41}), false},
		{"length octets missing", outer([]byte{0x04, 0x82, 0x01}), false},
		{"high tag cut off", outer([]byte{0x7f, 0x81}), false},
		{"high tag", outer([]byte{0x5f, 0x81, 0x01, 0x00}), true},
	} {
		if err := checkShape(tc.msg); (err == nil) != tc.ok {
			t.Errorf("%s: %v, want ok=%v", tc.name, err, tc.ok)
		}
		_, err := readMessage(bufio.NewReader(bytes.NewReader(tc.msg)))
		if !tc.ok && err == nil {
			t.Errorf("%s: read accepted it", tc.name)
		}
	}
	// The 256 KiB message of 130,000 elements costs its buffer, not a packet per element.
	hostile := outer(nulls((MaxMessageSize - 16) / 2))
	if n := testing.AllocsPerRun(3, func() { readMessage(bufio.NewReader(bytes.NewReader(hostile))) }); n > 10 {
		t.Errorf("reading the hostile message allocates %v times", n)
	}
	// A second element after the first, as when the outer length is short.
	if err := checkShape(append(outer(), 0x05, 0x00)); err == nil {
		t.Error("trailing element accepted")
	}
	// The outer length is checked before anything is read: too large, indefinite, 5 octets.
	for _, head := range [][]byte{{0x30, 0x84, 0xff, 0xff, 0xff, 0xff}, {0x30, 0x83, 0x04, 0x00, 0x01}, {0x30, 0x80}, {0x30, 0x85, 0, 0, 0, 0, 1}} {
		_, err := readMessage(bufio.NewReader(bytes.NewReader(append(head, make([]byte, 16)...))))
		if err == nil || errors.Is(err, errShape) {
			t.Errorf("outer % x: %v", head, err)
		}
	}
}

func TestFilterTermLimit(t *testing.T) {
	compileString(t, wideOr(maxFilterTerms-1)) // the or and its terms: exactly the limit
	f, err := ldap.CompileFilter(wideOr(maxFilterTerms))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := compileFilter(f); err == nil {
		t.Errorf("a filter of %d terms compiled", maxFilterTerms+1)
	}
	// Answered as other bad filters, on a connection that goes on.
	c := newEnv(t).login("phone1")
	if _, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, wideOr(maxFilterTerms)); code(err) != ldap.LDAPResultProtocolError {
		t.Errorf("search with %d terms: %v", maxFilterTerms+1, err)
	}
	if r, err := search(c, bookBase("staff"), ldap.ScopeWholeSubtree, 0, wideOr(maxFilterTerms-1)); err != nil || len(r.Entries) != 0 {
		t.Errorf("search at the limit: %v", err)
	}
}

func FuzzReadMessage(f *testing.F) {
	for _, msg := range wellFormed(f) {
		f.Add(msg)
	}
	f.Add(outer(nulls(100)))
	f.Add(outer(depthOf(maxDepth)))
	f.Add(outer([]byte{0x63, 0x80, 0x05, 0x00, 0x00, 0x00}))
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > MaxMessageSize+8 {
			return
		}
		p, err := readMessage(bufio.NewReader(bytes.NewReader(data)))
		if err != nil {
			return
		}
		m, err := parseMessage(p)
		if err != nil {
			return
		}
		if m.op.Tag == opSearchRequest {
			parseSearch(m.op)
		}
	})
}
