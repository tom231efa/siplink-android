package web

import (
	"errors"
	"io"
	"net/http"
	"os"
	"strconv"

	"filippo.io/age"

	"siplink/internal/auth"
	"siplink/internal/backup"
)

// backupDownload sends one of the listed backups as an attachment (design §6.6): the
// administrator's password typed again, and either the file as it is on disk or the
// file encrypted in the age format with a passphrase (scrypt). Nothing is written to
// disk; the passphrase is neither logged nor audited.
func (s *Server) backupDownload(w http.ResponseWriter, r *http.Request) {
	a := current(r)
	name := r.FormValue("name")
	f, info, err := backup.Open(backup.Dir(s.Cfg.DataDir), name)
	if errors.Is(err, os.ErrNotExist) {
		s.notFound(w, r)
		return
	} else if err != nil {
		s.internal(w, r, err)
		return
	}
	defer f.Close()
	encrypt := r.FormValue("encrypt") == "1"
	pass := r.FormValue("passphrase")
	msg := s.recheckPassword(a, r.FormValue("password"))
	if msg == "" && encrypt {
		strict, err := s.St.PasswordCheck(r.Context())
		if err != nil {
			s.internal(w, r, err)
			return
		}
		if msg = auth.PassphraseProblem(pass, strict); msg == "" && pass != r.FormValue("passphrase2") {
			msg = "两次输入的加密口令不一致。"
		}
	}
	if msg != "" {
		s.renderSystem(w, r, http.StatusBadRequest, func(d *systemData) { d.Download, d.DownloadError, d.DownloadPlain = name, msg, !encrypt })
		return
	}
	s.audit(r, "backup.download", "backup", 0, name, nil, map[string]any{"encrypted": encrypt})
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Cache-Control", "no-store")
	if !encrypt {
		h.Set("Content-Disposition", `attachment; filename="`+name+`"`)
		h.Set("Content-Length", strconv.FormatInt(info.Size, 10))
		if _, err := io.Copy(w, f); err != nil {
			s.Log.Warn("backup download interrupted", "file", name, "err", err)
		}
		return
	}
	recipient, err := age.NewScryptRecipient(pass)
	if err != nil {
		s.internal(w, r, err)
		return
	}
	h.Set("Content-Disposition", `attachment; filename="`+name+`.age"`)
	enc, err := age.Encrypt(w, recipient)
	if err == nil {
		if _, err = io.Copy(enc, f); err == nil {
			err = enc.Close()
		}
	}
	if err != nil {
		s.Log.Warn("backup download interrupted", "file", name, "err", err)
	}
}
