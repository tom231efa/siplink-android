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
	svg, err := SVG(url)
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
