package i18n

import (
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"testing"
)

// callSite is a call with a literal key and the argument names passed to it.
type callSite struct {
	pos, key string
	names    []string
	dynamic  bool // an argument name that is not a literal: not checked
}

// TestCallSiteArguments compares the argument names passed at every call with a literal
// key (Go: T, i18n.M, i18n.E, i18n.Wrap, refuse; templates: t) with the placeholders of
// the text, so that a missing or misspelt argument fails here and not only on a page a
// test happens to render (§8.1).
func TestCallSiteArguments(t *testing.T) {
	root := filepath.Join("..", "..")
	sites := goCallSites(t, root)
	sites = append(sites, templateCallSites(t, filepath.Join(root, "internal", "web", "templates"))...)
	if len(sites) < 1000 {
		t.Fatalf("only %d call sites found", len(sites))
	}
	en := catalogs[English.Code]
	for _, s := range sites {
		text, ok := en[s.key]
		if !ok {
			t.Errorf("%s: no key %s", s.pos, s.key)
			continue
		}
		if s.dynamic {
			continue
		}
		want, got := Placeholders(text), slices.Compact(slices.Sorted(slices.Values(s.names)))
		for _, w := range want {
			if !slices.Contains(got, w) {
				t.Errorf("%s: %s: argument {%s} not passed", s.pos, s.key, w)
			}
		}
		for _, g := range got {
			if !slices.Contains(want, g) {
				t.Errorf("%s: %s: argument %s not in %q", s.pos, s.key, g, text)
			}
		}
	}
}

func literal(e ast.Expr) (string, bool) {
	if b, ok := e.(*ast.BasicLit); ok && b.Kind == token.STRING {
		s, err := strconv.Unquote(b.Value)
		return s, err == nil
	}
	return "", false
}

// goCallSites finds the calls with a literal key in the Go files (tests aside).
func goCallSites(t *testing.T, root string) []callSite {
	var out []callSite
	fset := token.NewFileSet()
	err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return err
		}
		f, err := parser.ParseFile(fset, path, nil, 0)
		if err != nil {
			return err
		}
		inI18n := f.Name.Name == "i18n"
		ast.Inspect(f, func(n ast.Node) bool {
			call, ok := n.(*ast.CallExpr)
			if !ok {
				return true
			}
			keyAt, extra := -1, []string(nil)
			switch fun := call.Fun.(type) {
			case *ast.SelectorExpr:
				x, _ := fun.X.(*ast.Ident)
				switch {
				case fun.Sel.Name == "T":
					keyAt = 0
				case x != nil && x.Name == "i18n" && (fun.Sel.Name == "M" || fun.Sel.Name == "E"):
					keyAt = 0
				case x != nil && x.Name == "i18n" && fun.Sel.Name == "Wrap":
					keyAt, extra = 1, []string{"err"}
				}
			case *ast.Ident:
				if fun.Name == "refuse" || inI18n && (fun.Name == "M" || fun.Name == "E") {
					keyAt = 0
				}
			}
			if keyAt < 0 || len(call.Args) <= keyAt {
				return true
			}
			key, ok := literal(call.Args[keyAt])
			if !ok {
				return true
			}
			s := callSite{pos: fset.Position(call.Pos()).String(), key: key, names: extra, dynamic: call.Ellipsis.IsValid()}
			args := call.Args[keyAt+1:]
			s.dynamic = s.dynamic || len(args)%2 != 0
			for i := 0; i < len(args); i += 2 {
				if name, ok := literal(args[i]); ok {
					s.names = append(s.names, name)
				} else {
					s.dynamic = true
				}
			}
			out = append(out, s)
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return out
}

var templateCall = regexp.MustCompile(`(?:\{\{-?\s*|\(\s*)t\s+"([^"]+)"`)

// templateCallSites finds {{t "key" "name" value …}} and (t "key" …) in the templates.
func templateCallSites(t *testing.T, dir string) []callSite {
	files, err := filepath.Glob(filepath.Join(dir, "*.html"))
	if err != nil || len(files) == 0 {
		t.Fatalf("templates: %v", err)
	}
	var out []callSite
	for _, file := range files {
		data, err := os.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		src := string(data)
		for _, loc := range templateCall.FindAllStringSubmatchIndex(src, -1) {
			end := callEnd(src, loc[1])
			s := callSite{pos: filepath.Base(file) + ":" + strconv.Itoa(strings.Count(src[:loc[0]], "\n")+1), key: src[loc[2]:loc[3]]}
			toks := templateTokens(strings.TrimSpace(strings.TrimSuffix(strings.TrimSpace(src[loc[1]:end]), "-")))
			s.dynamic = len(toks)%2 != 0
			for i := 0; i < len(toks); i += 2 {
				if name, err := strconv.Unquote(toks[i]); err == nil {
					s.names = append(s.names, name)
				} else {
					s.dynamic = true
				}
			}
			out = append(out, s)
		}
	}
	return out
}

// callEnd is where the template call that starts before i ends: ")" or "}}" or "|" at
// its own depth, outside strings.
func callEnd(src string, i int) int {
	depth, inString := 0, false
	for ; i < len(src); i++ {
		c := src[i]
		switch {
		case inString:
			inString = c != '"'
		case c == '"':
			inString = true
		case c == '(':
			depth++
		case c == ')':
			if depth == 0 {
				return i
			}
			depth--
		case depth == 0 && (c == '|' || c == '}' && i+1 < len(src) && src[i+1] == '}'):
			return i
		}
	}
	return i
}

// templateTokens splits a template call's arguments at spaces outside strings and
// parentheses.
func templateTokens(s string) []string {
	var out []string
	depth, inString, cur := 0, false, ""
	for _, r := range s {
		switch {
		case inString:
			inString = r != '"'
		case r == '"':
			inString = true
		case r == '(':
			depth++
		case r == ')':
			depth--
		case r == ' ' && depth == 0:
			if cur != "" {
				out = append(out, cur)
				cur = ""
			}
			continue
		}
		cur += string(r)
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}
