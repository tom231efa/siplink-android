# Third-party material

The management platform's web pages embed the following files. Nothing is loaded from
a CDN at run time; the files are part of the binary (`go:embed`).

| What | Where in this repository | Source | Version | Licence |
|---|---|---|---|---|
| Inter variable font (`InterVariable.woff2`, upright, all weights) | `internal/web/static/InterVariable.woff2` | Official release archive `Inter-4.1.zip` from <https://github.com/rsms/inter/releases/tag/v4.1>, file `web/InterVariable.woff2` | 4.1 (2024-11-16) | SIL Open Font License 1.1, `third_party/Inter-LICENSE.txt` |
| Lucide icons (the subset listed in `internal/web/icon_data.go`) | `internal/web/icon_data.go` (inner SVG markup) | `icons/<name>.svg` of <https://github.com/lucide-icons/lucide> at tag `1.61.0` | 1.61.0 (2026-10-21) | ISC (some icons MIT, derived from Feather), `third_party/Lucide-LICENSE.txt` |
| GitHub mark (`mark-github-16`), the link to the project's repository | `internal/web/templates/layout.html` (template `repo-link`, path data unchanged) | `icons/mark-github-16.svg` of <https://github.com/primer/octicons> at tag `@primer/octicons-react-symbols@0.8.0` | 2026-10-23 | MIT, `third_party/Octicons-LICENSE.txt` |

Checksums (SHA-256):

- `Inter-4.1.zip`: `9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e`
- `InterVariable.woff2`: `693b77d4f32ee9b8bfc995589b5fad5e99adf2832738661f5402f9978429a8e3`
- Inter `LICENSE.txt`: `262481e844521b326f5ecd053e59b98c8b2da78c8ee1bdbb6e8174305e54935a`
- Lucide `LICENSE` at 1.61.0: `b495047bd93a9b06913511076f504daba17d5bbeb3e0650f3bb53a4220329c57`
- Octicons `mark-github-16.svg`: `7421820090b50ac79d7c2bf7c951a433d7098a8a9c474809207bdd45f62d9d46`
- Octicons `LICENSE`: `da259c8bd0de62713ccdcf88910aebca810644f98c2c912bad814fc79ea778df`

The font file is unmodified. The icons keep their path data; only the outer `<svg>`
element is replaced by the `icon` template helper (`internal/web/ui.go`), and their
stroke is set by the `.icon` rule in `internal/web/static/app.css`.

To update the font, replace the file and the `?v=` hash in the `@font-face` rule of
`app.css` (the first 12 hex digits of its SHA-256); `TestFont` fails until both agree.
