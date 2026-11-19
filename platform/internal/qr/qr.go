// Package qr renders QR codes for enrollment URLs (design §5.4): SVG for screen and
// print from the bitmap of go-qrcode, PNG through the library's encoder. Level M.
package qr

import (
	"fmt"
	"html"
	"strings"

	qrcode "github.com/skip2/go-qrcode"
)

// SVG returns an SVG image of the QR code of text, scaled by the viewer (no fixed size);
// label names it for screen readers.
func SVG(text, label string) (string, error) {
	q, err := qrcode.New(text, qrcode.Medium)
	if err != nil {
		return "", err
	}
	bits := q.Bitmap() // includes the quiet zone
	n := len(bits)
	var b strings.Builder
	fmt.Fprintf(&b, `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" shape-rendering="crispEdges" role="img" aria-label="%s">`, n, n, html.EscapeString(label))
	fmt.Fprintf(&b, `<rect width="%d" height="%d" fill="#fff"/><path fill="#000" d="`, n, n)
	for y, row := range bits {
		for x := 0; x < len(row); {
			if !row[x] {
				x++
				continue
			}
			start := x
			for x < len(row) && row[x] {
				x++
			}
			fmt.Fprintf(&b, "M%d %dh%dv1h-%dz", start, y, x-start, x-start)
		}
	}
	b.WriteString(`"/></svg>`)
	return b.String(), nil
}

// PNG returns a PNG image of size×size pixels.
func PNG(text string, size int) ([]byte, error) {
	return qrcode.Encode(text, qrcode.Medium, size)
}
