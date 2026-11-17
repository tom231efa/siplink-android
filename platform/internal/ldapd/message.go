package ldapd

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"strings"

	ber "github.com/go-asn1-ber/asn1-ber"
)

// LDAP application tags (RFC 4511).
const (
	opBindRequest     = 0
	opBindResponse    = 1
	opUnbindRequest   = 2
	opSearchRequest   = 3
	opSearchEntry     = 4
	opSearchDone      = 5
	opModifyRequest   = 6
	opModifyResponse  = 7
	opAddRequest      = 8
	opAddResponse     = 9
	opDelRequest      = 10
	opDelResponse     = 11
	opModDNRequest    = 12
	opModDNResponse   = 13
	opCompareRequest  = 14
	opCompareResponse = 15
	opAbandonRequest  = 16
	opExtendedRequest = 23
	opExtendedResp    = 24
)

// Result codes used by the server.
const (
	rcSuccess                 = 0
	rcOperationsError         = 1
	rcProtocolError           = 2
	rcTimeLimitExceeded       = 3
	rcSizeLimitExceeded       = 4
	rcAuthMethodNotSupported  = 7
	rcUnavailableCriticalExt  = 12
	rcConfidentialityRequired = 13
	rcNoSuchObject            = 32
	rcInvalidCredentials      = 49
	rcBusy                    = 51
	rcUnavailable             = 52
	rcUnwillingToPerform      = 53
	rcOther                   = 80
)

// OIDStartTLS is the StartTLS extended operation.
const OIDStartTLS = "1.3.6.1.4.1.1466.20037"

// MaxMessageSize is the largest LDAP message accepted (design §4.5).
const MaxMessageSize = 256 << 10

var errTooLarge = errors.New("ldap message too large")

// readMessage reads one BER-encoded LDAPMessage, refusing messages over the limit
// and indefinite lengths before reading the body.
func readMessage(r *bufio.Reader) (*ber.Packet, error) {
	tag, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	if tag != 0x30 {
		return nil, fmt.Errorf("unexpected tag 0x%02x", tag)
	}
	first, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	header := []byte{tag, first}
	length := int(first)
	if first&0x80 != 0 {
		n := int(first & 0x7f)
		if n == 0 || n > 4 {
			return nil, errors.New("unsupported length form")
		}
		length = 0
		for i := 0; i < n; i++ {
			b, err := r.ReadByte()
			if err != nil {
				return nil, err
			}
			header = append(header, b)
			length = length<<8 | int(b)
		}
	}
	if length > MaxMessageSize {
		return nil, errTooLarge
	}
	buf := make([]byte, len(header)+length)
	copy(buf, header)
	if _, err := io.ReadFull(r, buf[len(header):]); err != nil {
		return nil, err
	}
	return ber.DecodePacketErr(buf)
}

// message is a decoded request.
type message struct {
	id       int64
	op       *ber.Packet
	critical bool // a critical control is present
}

func parseMessage(p *ber.Packet) (*message, error) {
	if len(p.Children) < 2 {
		return nil, errors.New("short LDAPMessage")
	}
	id, ok := p.Children[0].Value.(int64)
	if !ok || id < 0 {
		return nil, errors.New("bad message id")
	}
	m := &message{id: id, op: p.Children[1]}
	if m.op.ClassType != ber.ClassApplication {
		return nil, errors.New("protocolOp is not an application tag")
	}
	if len(p.Children) > 2 {
		ctrls := p.Children[2]
		if ctrls.ClassType != ber.ClassContext || ctrls.Tag != 0 {
			return nil, errors.New("bad controls")
		}
		for _, c := range ctrls.Children {
			if len(c.Children) > 1 {
				if crit, ok := c.Children[1].Value.(bool); ok && crit {
					m.critical = true
				}
			}
		}
	}
	return m, nil
}

func envelope(id int64, op *ber.Packet) *ber.Packet {
	p := ber.NewSequence("LDAPMessage")
	p.AppendChild(ber.NewInteger(ber.ClassUniversal, ber.TypePrimitive, ber.TagInteger, id, "id"))
	p.AppendChild(op)
	return p
}

func octets(s string) *ber.Packet {
	return ber.NewString(ber.ClassUniversal, ber.TypePrimitive, ber.TagOctetString, s, "")
}

// result builds an LDAPResult-shaped response for application tag op.
func result(op int, code int, matched, msg string) *ber.Packet {
	p := ber.Encode(ber.ClassApplication, ber.TypeConstructed, ber.Tag(op), nil, "")
	p.AppendChild(ber.NewInteger(ber.ClassUniversal, ber.TypePrimitive, ber.TagEnumerated, int64(code), "code"))
	p.AppendChild(octets(matched))
	p.AppendChild(octets(msg))
	return p
}

// responseTag returns the response tag of a request that gets a result, or -1.
func responseTag(op int) int {
	switch op {
	case opBindRequest:
		return opBindResponse
	case opSearchRequest:
		return opSearchDone
	case opModifyRequest:
		return opModifyResponse
	case opAddRequest:
		return opAddResponse
	case opDelRequest:
		return opDelResponse
	case opModDNRequest:
		return opModDNResponse
	case opCompareRequest:
		return opCompareResponse
	case opExtendedRequest:
		return opExtendedResp
	}
	return -1
}

// entryPacket builds a SearchResultEntry with the selected attributes.
func entryPacket(n *node, sel selection, typesOnly bool) *ber.Packet {
	p := ber.Encode(ber.ClassApplication, ber.TypeConstructed, opSearchEntry, nil, "")
	p.AppendChild(octets(n.dn))
	attrs := ber.NewSequence("attributes")
	for a := 0; a < nAttrs; a++ {
		if len(n.vals[a]) == 0 || !sel.wants(a) {
			continue
		}
		pa := ber.NewSequence("")
		pa.AppendChild(octets(attrNames[a]))
		set := ber.Encode(ber.ClassUniversal, ber.TypeConstructed, ber.TagSet, nil, "")
		if !typesOnly {
			for _, v := range n.vals[a] {
				set.AppendChild(octets(v))
			}
		}
		pa.AppendChild(set)
		attrs.AppendChild(pa)
	}
	p.AppendChild(attrs)
	return p
}

// selection is the attribute list of a search request.
type selection struct {
	all   bool
	plus  bool // "+": operational attributes (only the root DSE has some)
	names [nAttrs]bool
	raw   map[string]bool // every requested name, lower-cased
}

func (s selection) wants(a int) bool { return s.all || s.names[a] }

// parseSelection: empty list or "*" = all user attributes; "1.1" = none; named
// attributes case-insensitively; "+" adds nothing (no operational attributes).
func parseSelection(list *ber.Packet) selection {
	s := selection{raw: map[string]bool{}}
	if len(list.Children) == 0 {
		s.all = true
		return s
	}
	for _, c := range list.Children {
		name := string(raw(c))
		s.raw[strings.ToLower(name)] = true
		switch name {
		case "*":
			s.all = true
		case "+":
			s.plus = true
		case "1.1":
		default:
			if a, ok := lookupAttr(name); ok {
				s.names[a] = true
			}
		}
	}
	return s
}
