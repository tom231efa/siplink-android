package web

import (
	"bytes"
	"compress/gzip"
	"io/fs"
	"net/http"
	"path"
	"strconv"
	"strings"
	"sync"
)

// Compression (gzip) of pages and of the text files under /static. A response that shows
// a secret is never compressed: with compression, the length of a page that holds a
// secret and text an attacker chose tells whether the two share bytes (BREACH). Such pages
// set Page.secret; the device API (JSON) and downloads are not compressed at all.

// minGzip is the smallest body worth compressing.
const minGzip = 1024

var gzipWriters = sync.Pool{New: func() any {
	w, _ := gzip.NewWriterLevel(nil, gzip.DefaultCompression)
	return w
}}

// acceptsGzip reports whether the request's Accept-Encoding allows gzip: named with a
// q-value above 0, or "*" when gzip is not named.
func acceptsGzip(r *http.Request) bool {
	star := false
	for _, v := range r.Header.Values("Accept-Encoding") {
		for part := range strings.SplitSeq(v, ",") {
			name, params, _ := strings.Cut(strings.TrimSpace(part), ";")
			ok := true
			for p := range strings.SplitSeq(params, ";") {
				if k, q, found := strings.Cut(strings.TrimSpace(p), "="); found && strings.EqualFold(strings.TrimSpace(k), "q") {
					f, err := strconv.ParseFloat(strings.TrimSpace(q), 64)
					ok = err == nil && f > 0
				}
			}
			switch name = strings.ToLower(strings.TrimSpace(name)); name {
			case "gzip", "x-gzip":
				return ok
			case "*":
				star = ok
			}
		}
	}
	return star
}

// writeBody writes a text body with its status: gzip-compressed when compress is true,
// the body is long enough and the request accepts gzip. Vary names Accept-Encoding for
// every body whose encoding depends on it.
func writeBody(w http.ResponseWriter, r *http.Request, status int, body []byte, compress bool) {
	h := w.Header()
	if !compress || len(body) < minGzip || h.Get("Content-Encoding") != "" {
		w.WriteHeader(status)
		w.Write(body)
		return
	}
	h.Add("Vary", "Accept-Encoding")
	if !acceptsGzip(r) {
		w.WriteHeader(status)
		w.Write(body)
		return
	}
	h.Set("Content-Encoding", "gzip")
	h.Del("Content-Length")
	w.WriteHeader(status)
	zw := gzipWriters.Get().(*gzip.Writer)
	zw.Reset(w)
	zw.Write(body)
	zw.Close()
	gzipWriters.Put(zw)
}

// gzipTypes are the static files that are text, by extension (woff2 and images are
// compressed already).
var gzipTypes = map[string]bool{".css": true, ".js": true, ".svg": true}

// gzippedAssets are the compressible static files compressed once, by name ("static/app.css"),
// only where that saves something.
var gzippedAssets = sync.OnceValue(func() map[string][]byte {
	out := map[string][]byte{}
	names, _ := fs.Glob(staticFiles, "static/*")
	for _, name := range names {
		data, err := fs.ReadFile(staticFiles, name)
		if err != nil || !gzipTypes[path.Ext(name)] || len(data) < minGzip {
			continue
		}
		var b bytes.Buffer
		zw, _ := gzip.NewWriterLevel(&b, gzip.BestCompression)
		zw.Write(data)
		zw.Close()
		if b.Len() < len(data) {
			out[name] = b.Bytes()
		}
	}
	return out
})
