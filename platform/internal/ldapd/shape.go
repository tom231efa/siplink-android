package ldapd

import (
	"errors"
	"sync"

	ber "github.com/go-asn1-ber/asn1-ber"
)

// Limits of the shape of one received message (design §4.5), checked before the
// library builds a packet for every element.
const (
	maxElements     = 4096 // elements in one message, the outermost included
	maxDepth        = 64   // levels of elements, the outermost being level 1
	maxLengthOctets = 4    // octets of a long-form length (MaxMessageSize needs 3)
)

var (
	errShape     = errors.New("ldap message refused: malformed or too complex")
	errLengthLen = errors.New("unsupported length form")
)

var berLimits sync.Once

// setBERLimits makes the library refuse what checkShape refuses, in case a message
// reached it some other way.
func setBERLimits() {
	berLimits.Do(func() {
		ber.MaxPacketLengthBytes = MaxMessageSize
		ber.MaxNestingDepth = maxDepth
	})
}

// tlvLength decodes the length octets at the start of b: the length and the number of
// octets it takes. The indefinite form and more than maxLengthOctets octets are refused.
// The result is unsigned and 64-bit, so it cannot overflow on a 32-bit build.
func tlvLength(b []byte) (uint64, int, error) {
	if len(b) == 0 {
		return 0, 0, errShape
	}
	first := b[0]
	if first&0x80 == 0 {
		return uint64(first), 1, nil
	}
	n := int(first & 0x7f)
	if n == 0 || n > maxLengthOctets {
		return 0, 0, errLengthLen // 0x80 is the indefinite form
	}
	if len(b) < 1+n {
		return 0, 0, errShape
	}
	var length uint64
	for _, o := range b[1 : 1+n] {
		length = length<<8 | uint64(o)
	}
	return length, 1 + n, nil
}

// checkShape walks the TLV headers of one whole message without allocating. It refuses
// more than maxElements elements, more than maxDepth levels, an indefinite length and an
// element that runs past its parent (or past the message).
func checkShape(msg []byte) error {
	var ends [maxDepth]int // end offset of each open constructed element
	depth, elements := 0, 0
	for pos := 0; pos < len(msg); {
		for depth > 0 && pos == ends[depth-1] {
			depth--
		}
		limit := len(msg)
		if depth > 0 {
			limit = ends[depth-1]
		} else if pos > 0 {
			return errShape // a second element after the message
		}
		if elements++; elements > maxElements || depth >= maxDepth {
			return errShape
		}
		id := msg[pos]
		pos++
		if id&0x1f == 0x1f { // high tag number: continuation octets
			for {
				if pos >= limit {
					return errShape
				}
				pos++
				if msg[pos-1]&0x80 == 0 {
					break
				}
			}
		}
		length, n, err := tlvLength(msg[pos:limit])
		if err != nil {
			return err
		}
		pos += n
		if length > uint64(limit-pos) {
			return errShape
		}
		end := pos + int(length)
		if id&0x20 != 0 { // constructed: its children follow
			ends[depth] = end
			depth++
		} else {
			pos = end
		}
	}
	return nil
}
