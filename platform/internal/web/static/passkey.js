// SipLink management platform: passkeys (WebAuthn) on the account page and in the second step
// of the sign-in (design §6.8). The server sends the library's options as JSON with
// base64url fields; this file turns them into buffers and the answer back into JSON.
(function () {
  "use strict";
  if (!window.PublicKeyCredential || !navigator.credentials) return;
  var text = window.siplinkText || String;

  function toBuf(s) {
    s = s.replace(/-/g, "+").replace(/_/g, "/");
    while (s.length % 4) s += "=";
    var bin = atob(s), out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out.buffer;
  }
  function toText(buf) {
    var b = new Uint8Array(buf), s = "";
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function post(url, body) {
    return fetch(url, {
      method: "POST", credentials: "same-origin",
      headers: { "Content-Type": "application/json" }, body: JSON.stringify(body || {})
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (!r.ok) throw new Error(j.error || text("request_failed"));
        return j;
      });
    });
  }
  function ids(list) {
    (list || []).forEach(function (c) { c.id = toBuf(c.id); });
  }
  function encode(cred) {
    var r = cred.response;
    var out = { id: cred.id, rawId: toText(cred.rawId), type: cred.type, response: { clientDataJSON: toText(r.clientDataJSON) },
      clientExtensionResults: cred.getClientExtensionResults ? cred.getClientExtensionResults() : {} };
    if (cred.authenticatorAttachment) out.authenticatorAttachment = cred.authenticatorAttachment;
    if (r.attestationObject) {
      out.response.attestationObject = toText(r.attestationObject);
      if (r.getTransports) out.response.transports = r.getTransports();
    }
    if (r.authenticatorData) {
      out.response.authenticatorData = toText(r.authenticatorData);
      out.response.signature = toText(r.signature);
      if (r.userHandle) out.response.userHandle = toText(r.userHandle);
    }
    return out;
  }
  function failed(box, btn) {
    return function (err) {
      var el = box.querySelector("[data-passkey-error]");
      if (el) {
        el.textContent = err && err.name === "NotAllowedError" ? text("cancelled") : (err && err.message) || text("failed");
        el.hidden = false;
      }
      btn.disabled = false;
    };
  }

  // Account page: add a passkey under the name typed in.
  document.querySelectorAll("[data-passkey-register]").forEach(function (form) {
    var btn = form.querySelector("button");
    form.hidden = false;
    form.addEventListener("submit", function (ev) {
      ev.preventDefault();
      btn.disabled = true;
      var name = form.elements.name.value.trim();
      post("/me/passkeys/options").then(function (j) {
        var pk = j.options.publicKey;
        pk.challenge = toBuf(pk.challenge);
        pk.user.id = toBuf(pk.user.id);
        ids(pk.excludeCredentials);
        return navigator.credentials.create({ publicKey: pk }).then(function (cred) {
          return post("/me/passkeys", { token: j.token, name: name, credential: encode(cred) });
        });
      }).then(function (j) { location.assign(j.redirect); }).catch(failed(form, btn));
    });
  });

  // Second step of the sign-in.
  document.querySelectorAll("[data-passkey-login]").forEach(function (box) {
    var btn = box.querySelector("button");
    box.hidden = false;
    btn.addEventListener("click", function () {
      btn.disabled = true;
      post("/login/step/passkey/options").then(function (j) {
        var pk = j.options.publicKey;
        pk.challenge = toBuf(pk.challenge);
        ids(pk.allowCredentials);
        return navigator.credentials.get({ publicKey: pk }).then(function (cred) {
          return post("/login/step/passkey", { token: j.token, next: box.getAttribute("data-next"), credential: encode(cred) });
        });
      }).then(function (j) { location.assign(j.redirect); }).catch(failed(box, btn));
    });
  });
})();
