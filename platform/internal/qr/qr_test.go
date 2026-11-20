package qr

import (
	"bytes"
	"fmt"
	"image/png"
	"strings"
	"testing"

	qrcode "github.com/skip2/go-qrcode"
)

func TestSVGMatchesBitmap(t *testing.T) {
	const url = "https://platform.example.com/p/abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG?pin=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
	svg, err := SVG(url, "QR")
	if err != nil {
		t.Fatal(err)
	}
	q, _ := qrcode.New(url, qrcode.Medium)
	dark := 0
	for _, row := range q.Bitmap() {
		for _, v := range row {
			if v {
				dark++
			}
		}
	}
	// Every dark module is covered by exactly one run: sum the run lengths.
	runs := 0
	for _, part := range strings.Split(svg, "M")[1:] {
		var x, y, w int
		if _, err := fmtSscanf(part, &x, &y, &w); err != nil {
			t.Fatal(err)
		}
		runs += w
	}
	if runs != dark || !strings.HasPrefix(svg, "<svg") || strings.Contains(svg, url) {
		t.Errorf("runs %d, dark %d", runs, dark)
	}
	p, err := PNG(url, 256)
	if err != nil {
		t.Fatal(err)
	}
	img, err := png.Decode(bytes.NewReader(p))
	if err != nil || img.Bounds().Dx() != 256 {
		t.Errorf("png: %v", err)
	}
}

func fmtSscanf(s string, x, y, w *int) (int, error) {
	return fmt.Sscanf(s, "%d %dh%d", x, y, w)
}

// TestSVGsInOrder: the codes made in parallel are those of SVG, in the order of the texts.
func TestSVGsInOrder(t *testing.T) {
	var texts []string
	for i := range 40 {
		texts = append(texts, fmt.Sprintf("https://platform.example.com/p/%043d", i))
	}
	got, err := SVGs(texts, "QR")
	if err != nil || len(got) != len(texts) {
		t.Fatalf("%d codes: %v", len(got), err)
	}
	for i, text := range texts {
		if want, _ := SVG(text, "QR"); got[i] != want {
			t.Errorf("code %d is not the one of its text", i)
		}
	}
	if got, err := SVGs(nil, "QR"); err != nil || len(got) != 0 {
		t.Errorf("no texts: %v %v", got, err)
	}
	if _, err := SVGs([]string{"ok", strings.Repeat("x", 4000)}, "QR"); err == nil {
		t.Error("a text too long for a QR code: no error")
	}
}
