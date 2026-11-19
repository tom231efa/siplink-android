package i18n

import (
	"go/scanner"
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

// chinese finds Chinese and Japanese characters and the CJK and full-width punctuation.
var chinese = regexp.MustCompile(`[\p{Han}\p{Hiragana}\p{Katakana}\x{3000}-\x{303f}\x{ff00}-\x{ffef}]`)

// allowed are the literals that keep Chinese text on purpose, by file (relative to the
// module root): data, not the console's words (§5.5, §8.1).
var allowed = map[string][]string{
	// the languages, each named in itself
	"internal/i18n/i18n.go": {"中文（简体）", "日本語", "中文（繁體）"},
	// the app's names of key types and icons and its reserved categories: data of the
	// key CSV format and of a key's default label (§3.5.7)
	"internal/rules/shortcuts.go": {"速拨", "盲转", "咨询转", "发送按键", "呼叫前转", "免打扰",
		"电话", "个人", "团队", "收藏", "家", "办公楼", "门店", "前台", "客服", "广播", "会议", "门", "锁", "钥匙",
		"车库", "车辆", "电梯", "保安", "监控", "门铃", "报警", "医务", "消防", "餐厅", "客房", "仓库", "车间", "教室",
		"维修", "机房", "物流", "照明", "全部", "未分类", "新建分类"},
	// the colon a Chinese keyboard types in front of a port
	"internal/rules/accounts.go": {"："},
	// the Chinese column names of the address book CSV, and the separator people type
	"internal/books/csv.go":   {"姓名", "办公电话", "手机", "单位", "部门", "职务", "邮箱", "姓", "名"},
	"internal/books/entry.go": {"；"},
	// the separators of several tags in one cell of the client CSV (§2.6)
	"internal/catalog/groups.go": {";；"},
	// the suffix migration 0008 gives a name that is taken (data, §2.5)
	"internal/store/migrate_0008.go": {"（%d）"},
	// platform.name of the provisioning document (§3.5.2)
	"internal/web/h_device.go": {"SipLink 管理平台"},
	// the Chinese column names of the client CSV (§5.2)
	"internal/web/h_client_import.go": {"名称", "区域", "客户端模板", "备注", "分组", "标签", "用户名", "密码", "认证用户", "显示名", "LDAP密码"},
}

// TestNoChineseOutsideCatalogs: templates, scripts and Go code (tests aside) hold no
// Chinese text but what the allow-list names; the console's words live in the catalogs.
func TestNoChineseOutsideCatalogs(t *testing.T) {
	root := filepath.Join("..", "..")
	var found []string
	for _, dir := range []string{"internal", "cmd"} {
		err := filepath.WalkDir(filepath.Join(root, dir), func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			rel, _ := filepath.Rel(root, path)
			rel = filepath.ToSlash(rel)
			var hits []hit
			switch {
			case strings.HasSuffix(rel, "_test.go"):
				return nil
			case strings.HasSuffix(rel, ".go"):
				hits, err = goLiterals(path)
			case strings.HasSuffix(rel, ".html"):
				hits, err = templateText(path)
			case strings.HasSuffix(rel, ".js"):
				hits, err = scriptText(path)
			default:
				return nil
			}
			if err != nil {
				return err
			}
			for _, h := range hits {
				if !slices.Contains(allowed[rel], h.text) {
					found = append(found, rel+":"+strconv.Itoa(h.line)+": "+h.text)
				}
			}
			return nil
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, f := range found {
		t.Error(f)
	}
}

type hit struct {
	line int
	text string
}

// goLiterals lists the string and rune literals (decoded) that hold Chinese text.
func goLiterals(path string) ([]hit, error) {
	src, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	fset := token.NewFileSet()
	file := fset.AddFile(path, -1, len(src))
	var s scanner.Scanner
	s.Init(file, src, nil, 0) // comments are skipped
	var out []hit
	for {
		pos, tok, lit := s.Scan()
		if tok == token.EOF {
			return out, nil
		}
		if tok != token.STRING && tok != token.CHAR {
			continue
		}
		text, err := strconv.Unquote(lit)
		if err != nil {
			text = lit
		}
		if chinese.MatchString(text) {
			out = append(out, hit{fset.Position(pos).Line, text})
		}
	}
}

var (
	templateComment = regexp.MustCompile(`(?s)\{\{/\*.*?\*/\}\}|<!--.*?-->`)
	scriptComment   = regexp.MustCompile(`(?m)^\s*//.*$|/\*(?s:.*?)\*/|\s//\s.*$`)
	chineseRun      = regexp.MustCompile(`[^\s"'<>{}=]*[\p{Han}\p{Hiragana}\p{Katakana}\x{3000}-\x{303f}\x{ff00}-\x{ffef}][^\s"'<>{}=]*`)
)

// templateText lists the runs of Chinese text in a template outside its comments.
func templateText(path string) ([]hit, error) { return runs(path, templateComment) }

// scriptText lists the runs of Chinese text in a script outside its comments.
func scriptText(path string) ([]hit, error) { return runs(path, scriptComment) }

func runs(path string, comments *regexp.Regexp) ([]hit, error) {
	src, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	// Blank the comments but keep their line breaks, so that line numbers stay right.
	text := comments.ReplaceAllStringFunc(string(src), func(c string) string {
		return strings.Repeat("\n", strings.Count(c, "\n"))
	})
	var out []hit
	for i, line := range strings.Split(text, "\n") {
		for _, m := range chineseRun.FindAllString(line, -1) {
			out = append(out, hit{i + 1, m})
		}
	}
	return out, nil
}
