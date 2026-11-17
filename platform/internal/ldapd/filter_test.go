package ldapd

import (
	"testing"

	"github.com/go-ldap/ldap/v3"

	"siplink/internal/store"
)

func compileString(t *testing.T, s string) *filter {
	t.Helper()
	p, err := ldap.CompileFilter(s)
	if err != nil {
		t.Fatalf("%s: %v", s, err)
	}
	f, err := compileFilter(p)
	if err != nil {
		t.Fatalf("%s: %v", s, err)
	}
	return f
}

func TestFilterEvaluation(t *testing.T) {
	d := newDIT("dc=siplink")
	n := d.entryNode(&store.Entry{ID: 7, Name: "张伟 Wei", Surname: "张", Phones: []string{"021-5555 0001"},
		Mobiles: []string{"138 0000 1001"}, Email: "Zhang@Example.com"}, "staff")
	cases := []struct {
		f    string
		want tri
	}{
		{"(cn=*张*)", tTrue},
		{"(cn=*张伟*)", tTrue},
		{"(cn=张伟 wei)", tTrue},
		{"(cn=张伟    WEI)", tTrue},
		{"(cn=张*)", tTrue},
		{"(cn=*wei)", tTrue},
		{"(cn=*王*)", tFalse},
		{"(|(cn=*王*)(sn=*张*))", tTrue},
		{"(&(cn=*张*)(sn=李))", tFalse},
		{"(telephoneNumber=*55550001*)", tTrue},
		{"(telephoneNumber=02155550001)", tTrue},
		{"(mobile=*1380000*)", tTrue},
		{"(mobile=*138 0000*)", tTrue},
		{"(mail=zhang@example.com)", tTrue},
		{"(objectClass=INETORGPERSON)", tTrue},
		{"(objectClass=*)", tTrue},
		{"(givenName=*)", tFalse},
		{"(uid=7)", tTrue},
		{"(cn>=张)", tTrue},
		{"(cn<=a)", tFalse},
		{"(cn~=张伟 wei)", tTrue},
		{"(unknownAttr=x)", tUndefined},
		{"(!(unknownAttr=x))", tUndefined},
		{"(|(unknownAttr=x)(cn=*张*))", tTrue},
		{"(&(unknownAttr=x)(cn=*张*))", tUndefined},
		{"(unknownAttr=*)", tFalse},
		{"(cn:caseExactMatch:=x)", tUndefined},
		{"(commonName=*伟*)", tTrue},
	}
	for _, c := range cases {
		if got := compileString(t, c.f).eval(n); got != c.want {
			t.Errorf("%s = %d, want %d", c.f, got, c.want)
		}
	}
}

func TestFoldAndDN(t *testing.T) {
	if foldRune('K') != foldRune('k') || foldRune('K') != foldRune('k') || foldRune('Σ') != foldRune('ς') {
		t.Error("simple case folding")
	}
	if foldCase("  A   b ", true) != "a b" || foldCase(" A ", false) != " a " {
		t.Error("space handling")
	}
	if foldPhone("+86 138-0000 1001") != "+8613800001001" {
		t.Error("phone folding")
	}
	d := newDIT("dc=siplink")
	for dn, want := range map[string]int{
		"":                                    tRoot,
		"DC=SipLink":                          tSuffix,
		"ou=Books, dc=siplink":                tBooks,
		"ou=staff,ou=books,dc=siplink":        tBook,
		"uid=12,ou=staff,ou=books,dc=siplink": tEntry,
		"uid=x,ou=staff,ou=books,dc=siplink":  tNone,
		"ou=clients,dc=siplink":               tNone,
		"ou=staff,dc=siplink":                 tNone,
		"dc=other":                            tNone,
		"ou=st\\61ff,ou=books,dc=siplink":     tBook,
	} {
		if got := d.resolve(dn); got.kind != want {
			t.Errorf("resolve(%q) = %d, want %d", dn, got.kind, want)
		}
	}
	if got := d.resolve("ou=st\\61ff,ou=books,dc=siplink"); got.slug != "staff" {
		t.Errorf("escaped slug %q", got.slug)
	}
	if d.accountName("cn=Phone1, ou=accounts, dc=siplink") != "phone1" || d.accountName("cn=x,ou=books,dc=siplink") != "" {
		t.Error("account DN")
	}
}
