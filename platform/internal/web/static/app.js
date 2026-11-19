// SipLink management platform: small progressive enhancements. Every page works
// without this file.
(function () {
  "use strict";
  var root = document.documentElement;
  root.classList.add("js");

  // The page's texts for the scripts (the catalog's js.* keys, the language, its direction
  // and date patterns), with {name} arguments filled in; strings are isolated in a
  // right-to-left page. The other scripts use it as window.siplinkText.
  var texts = {};
  try { texts = JSON.parse(document.getElementById("i18n").textContent); } catch (e) { /* keys show */ }
  function text(key, args) {
    return (texts[key] || key).replace(/\{([a-z_]+)\}/g, function (m, name) {
      if (!args || !(name in args)) return m;
      var v = args[name];
      return typeof v === "string" && texts.dir === "rtl" ? "\u2068" + v + "\u2069" : String(v);
    });
  }
  window.siplinkText = text;

  // Confirmation dialog for forms and buttons marked data-confirm="...".
  var dialog = document.getElementById("confirm-dialog");
  document.addEventListener("submit", function (ev) {
    var form = ev.target;
    var text = form.getAttribute("data-confirm") ||
      (ev.submitter && ev.submitter.getAttribute("data-confirm"));
    var unless = form.getAttribute("data-confirm-unless");
    if (unless && form.elements[unless] && form.elements[unless].checked) return;
    if (!text || !dialog || form.dataset.confirmed === "1") return;
    ev.preventDefault();
    dialog.querySelector(".dialog-text").textContent = text;
    dialog.returnValue = "";
    dialog.onclose = function () {
      if (dialog.returnValue !== "ok") return;
      form.dataset.confirmed = "1";
      if (ev.submitter && ev.submitter.name) {
        var hidden = document.createElement("input");
        hidden.type = "hidden";
        hidden.name = ev.submitter.name;
        hidden.value = ev.submitter.value;
        form.appendChild(hidden);
      }
      form.submit();
      form.dispatchEvent(new Event("confirmed"));
    };
    dialog.showModal();
  });

  // Form dialogs (the backup download, the dialogs of groups and tags): a link marked
  // data-dialog="<id>" opens that <dialog data-form-dialog> in place. The form is reset and
  // filled from the link: data-action is its action, data-set-<name> the value of its field
  // <name> (a checkbox is on for "1", a radio is chosen by value) and the text of the
  // elements marked data-show="<name>". Without JavaScript the link's address asks the
  // server, which renders the page with the dialog open (a plain dialog over a scrim, made
  // modal here); a refusal comes back the same way with its message inside. Closing the
  // dialog clears its password fields and sets the address back to data-return. A dialog
  // marked data-close-on-submit closes once its form is sent (a download keeps the page).
  document.querySelectorAll("dialog[data-form-dialog]").forEach(function (dlg) {
    if (!dlg.showModal) return;
    var form = dlg.querySelector("form"), opener = null, reopening = false;
    var links = document.querySelectorAll('[data-dialog="' + dlg.id + '"]');
    function show(from) {
      opener = from;
      if (dlg.open) { reopening = true; dlg.close(); }
      dlg.showModal();
      var first = form.querySelector('input:not([type="hidden"]):not([type="radio"]):not([disabled]), select');
      if (first) first.focus();
    }
    links.forEach(function (a) {
      a.addEventListener("click", function (ev) {
        ev.preventDefault();
        var refusal = dlg.querySelector(".flash.error");
        if (refusal) refusal.remove();
        form.reset();
        if (a.hasAttribute("data-action")) form.action = a.getAttribute("data-action");
        Object.keys(a.dataset).forEach(function (key) {
          if (!/^set[A-Z]/.test(key)) return;
          var name = key.charAt(3).toLowerCase() + key.slice(4), v = a.dataset[key];
          form.querySelectorAll('[name="' + name + '"]').forEach(function (el) {
            if (el.type === "radio") el.checked = el.value === v;
            else if (el.type === "checkbox") el.checked = v === "1";
            else el.value = v;
          });
          dlg.querySelectorAll('[data-show="' + name + '"]').forEach(function (el) { el.textContent = v; });
        });
        show(a);
      });
    });
    if (dlg.open) {
      var scrim = document.querySelector(".dialog-scrim");
      if (scrim) scrim.remove();
      var here = location.pathname + location.search;
      show(Array.prototype.find.call(links, function (a) { return a.getAttribute("href") === here; }) || null);
    }
    dlg.querySelectorAll("[data-dialog-close]").forEach(function (c) {
      c.addEventListener("click", function (ev) { ev.preventDefault(); dlg.close(); });
    });
    dlg.addEventListener("click", function (ev) {
      var r = dlg.getBoundingClientRect();
      if (ev.target === dlg && (ev.clientX < r.left || ev.clientX > r.right || ev.clientY < r.top || ev.clientY > r.bottom)) dlg.close();
    });
    dlg.addEventListener("close", function () {
      if (reopening) { reopening = false; return; }
      form.querySelectorAll('input[type="password"]').forEach(function (i) { i.value = ""; });
      var back = dlg.getAttribute("data-return");
      if (back && location.pathname + location.search !== back) history.replaceState(null, "", back);
      if (opener) opener.focus();
    });
    if (dlg.hasAttribute("data-close-on-submit")) {
      form.addEventListener("submit", function (ev) {
        setTimeout(function () { if (!ev.defaultPrevented) dlg.close(); }, 0);
      });
      form.addEventListener("confirmed", function () { dlg.close(); });
    }
  });

  // Tag pickers (<details data-tag-picker>): the summary shows the chosen tags as chips, a
  // search field narrows the list, and with data-max no more than that many can be on. In
  // a filter row (GET form) the form is sent when the list closes after a change.
  document.querySelectorAll("[data-tag-picker]").forEach(function (pick) {
    var boxes = pick.querySelectorAll('input[type="checkbox"]');
    var shown = pick.querySelector("[data-picked]");
    var search = pick.querySelector("[data-pick-search]");
    var max = parseInt(pick.getAttribute("data-max"), 10) || 0;
    var changed = false;
    function sync() {
      var n = 0;
      shown.textContent = "";
      boxes.forEach(function (b) {
        if (!b.checked) return;
        n++;
        shown.appendChild(b.parentNode.querySelector(".chip").cloneNode(true));
      });
      if (!n) {
        var empty = document.createElement("span");
        empty.className = "picker-empty";
        empty.textContent = shown.getAttribute("data-empty");
        shown.appendChild(empty);
      }
      if (max) boxes.forEach(function (b) { b.disabled = !b.checked && n >= max; });
    }
    pick.addEventListener("change", function (ev) {
      if (ev.target.type !== "checkbox") return;
      changed = true;
      sync();
    });
    if (search) {
      search.hidden = false;
      search.addEventListener("input", function () {
        var q = search.value.trim().toLowerCase();
        boxes.forEach(function (b) {
          b.parentNode.hidden = q !== "" && b.parentNode.textContent.toLowerCase().indexOf(q) < 0;
        });
      });
      search.addEventListener("keydown", function (ev) { if (ev.key === "Enter") ev.preventDefault(); });
    }
    pick.addEventListener("toggle", function () {
      if (pick.open && search) search.focus();
      if (!pick.open && changed && pick.classList.contains("filter") && pick.closest("form")) pick.closest("form").submit();
    });
    sync();
  });

  // Selects marked data-autosubmit submit their form when changed; the form's
  // fallback button (data-js-hide) is hidden by the stylesheet.
  document.querySelectorAll("select[data-autosubmit], input[data-autosubmit]").forEach(function (sel) {
    sel.addEventListener("change", function () { sel.form.submit(); });
  });

  // Times: the server writes its own zone; show them in the viewer's local time, in the
  // language's date pattern. Those marked data-rel read "3 minutes ago" (the browser's own
  // words for the language, in ASCII digits) and keep the full time in the title.
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function full(d, dateOnly) {
    var parts = { YYYY: d.getFullYear(), MM: pad(d.getMonth() + 1), DD: pad(d.getDate()), HH: pad(d.getHours()), mm: pad(d.getMinutes()) };
    var pattern = (dateOnly ? texts.date : texts.datetime) || (dateOnly ? "YYYY-MM-DD" : "YYYY-MM-DD HH:mm");
    return pattern.replace(/YYYY|MM|DD|HH|mm/g, function (tok) { return parts[tok]; });
  }
  var ago = null;
  try { ago = new Intl.RelativeTimeFormat((texts.lang || "en") + "-u-nu-latn", { numeric: "auto" }); } catch (e) { /* absolute times */ }
  function relative(d) {
    var s = (Date.now() - d.getTime()) / 1000;
    if (s < -60 || !ago) return null;
    if (s < 60) return text("just_now");
    if (s < 3600) return ago.format(-Math.floor(s / 60), "minute");
    if (s < 86400) return ago.format(-Math.floor(s / 3600), "hour");
    if (s < 86400 * 2) return text("yesterday", { time: pad(d.getHours()) + ":" + pad(d.getMinutes()) });
    if (s < 86400 * 7) return ago.format(-Math.floor(s / 86400), "day");
    return null;
  }
  document.querySelectorAll("time[data-local]").forEach(function (el) {
    var d = new Date(el.getAttribute("datetime"));
    if (isNaN(d.getTime())) return;
    var abs = full(d, el.getAttribute("data-local") === "date");
    var rel = el.hasAttribute("data-rel") ? relative(d) : null;
    el.title = rel ? abs : el.textContent;
    el.textContent = rel || abs;
  });

  // Date filters mean the viewer's days: tell the server the browser's time zone.
  document.querySelectorAll("input[data-tz]").forEach(function (input) {
    try { input.value = Intl.DateTimeFormat().resolvedOptions().timeZone || ""; } catch (e) { /* keep the server's zone */ }
  });

  // Hosted book credentials: the manual fields only count when "manual" is chosen.
  document.querySelectorAll("fieldset[data-manual]").forEach(function (box) {
    var form = box.form;
    if (!form) return;
    function sync() {
      var on = form.querySelector('input[name="credentials"][value="manual"]');
      box.disabled = !(on && on.checked);
    }
    form.querySelectorAll('input[name="credentials"]').forEach(function (r) { r.addEventListener("change", sync); });
    sync();
  });

  // A control marked data-requires="name" is disabled while the checkbox of that name in
  // its form is off; a checkbox is then also off, a select keeps its value (the server
  // applies the same rule).
  document.querySelectorAll("[data-requires]").forEach(function (box) {
    var lead = box.form && box.form.querySelector('input[name="' + box.getAttribute("data-requires") + '"]');
    if (!lead) return;
    function sync() {
      box.disabled = !lead.checked;
      if (!lead.checked && box.type === "checkbox") box.checked = false;
    }
    lead.addEventListener("change", sync);
    sync();
  });

  // Long values (.fade): faded out at their end, with the full value as a title, only while
  // they do not fit; checked again whenever their box changes size and once the font is in.
  function fade(el) {
    var over = el.scrollHeight > el.clientHeight + 1;
    el.classList.toggle("overflowing", over);
    if (over && !el.title) {
      el.title = el.textContent.trim();
      el.setAttribute("data-fade-title", "");
    } else if (!over && el.hasAttribute("data-fade-title")) {
      el.removeAttribute("title");
      el.removeAttribute("data-fade-title");
    }
  }
  var fades = document.querySelectorAll(".fade");
  if (fades.length && "ResizeObserver" in window) {
    var watch = new ResizeObserver(function (entries) { entries.forEach(function (e) { fade(e.target); }); });
    fades.forEach(function (el) { watch.observe(el); });
    if (document.fonts) document.fonts.ready.then(function () { fades.forEach(fade); });
  } else {
    fades.forEach(fade);
  }

  // Print buttons.
  document.querySelectorAll("[data-print]").forEach(function (btn) {
    btn.addEventListener("click", function () { window.print(); });
  });

  // A checkbox marked data-check-all="name" ticks every checkbox of that name.
  document.querySelectorAll("[data-check-all]").forEach(function (box) {
    box.addEventListener("change", function () {
      document.querySelectorAll('input[type=checkbox][name="' + box.getAttribute("data-check-all") + '"]')
        .forEach(function (c) { c.checked = box.checked; });
      document.dispatchEvent(new Event("selection"));
    });
  });

  // The text of a button: its <span> when it has an icon, else the button itself.
  function label(btn) { return btn.querySelector("span") || btn; }

  // Copy buttons: data-copy="#element-id" copies that element's text.
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.hidden = false;
    btn.addEventListener("click", function () {
      var el = document.querySelector(btn.getAttribute("data-copy"));
      if (!el || !navigator.clipboard) return;
      navigator.clipboard.writeText(el.textContent.trim()).then(function () {
        var t = label(btn), old = t.textContent;
        t.textContent = text("copied");
        setTimeout(function () { t.textContent = old; }, 1500);
      });
    });
  });

  // Password reveal: data-reveal="#input-id" toggles the input type.
  document.querySelectorAll("[data-reveal]").forEach(function (btn) {
    btn.hidden = false;
    btn.addEventListener("click", function () {
      var input = document.querySelector(btn.getAttribute("data-reveal"));
      if (!input) return;
      var show = input.type === "password";
      input.type = show ? "text" : "password";
      label(btn).textContent = text(show ? "hide" : "show");
    });
  });

  // Success toasts leave by themselves; they can be closed earlier.
  document.querySelectorAll(".toast").forEach(function (toast) {
    function leave() {
      toast.classList.add("leaving");
      setTimeout(function () { toast.remove(); }, 200);
    }
    var close = toast.querySelector("[data-dismiss]");
    if (close) { close.hidden = false; close.addEventListener("click", leave); }
    var timer = setTimeout(leave, 5000);
    toast.addEventListener("mouseenter", function () { clearTimeout(timer); });
    toast.addEventListener("mouseleave", function () { timer = setTimeout(leave, 2500); });
  });

  // Theme switch: store the choice in the cookie the server reads, and apply it
  // without a reload. Without JavaScript the form posts to /theme.
  document.querySelectorAll(".theme-switch").forEach(function (form) {
    form.addEventListener("submit", function (ev) {
      var v = ev.submitter && ev.submitter.value;
      if (!v) return;
      ev.preventDefault();
      var secure = location.protocol === "https:" ? "; Secure" : "";
      document.cookie = v === "system"
        ? "siplink_theme=; Path=/; Max-Age=0; SameSite=Lax" + secure
        : "siplink_theme=" + v + "; Path=/; Max-Age=31536000; SameSite=Lax" + secure;
      if (v === "system") root.removeAttribute("data-theme"); else root.setAttribute("data-theme", v);
      var meta = document.querySelector('meta[name="color-scheme"]');
      if (meta) meta.content = v === "system" ? "light dark" : v;
      form.querySelectorAll("button[name=theme]").forEach(function (b) {
        b.setAttribute("aria-pressed", b.value === v ? "true" : "false");
      });
    });
  });

  // Menus (<details>) close on a click elsewhere and on Escape; Escape also closes the drawer.
  document.addEventListener("click", function (ev) {
    document.querySelectorAll("details[open]").forEach(function (d) {
      if (!d.contains(ev.target)) d.open = false;
    });
  });
  document.addEventListener("keydown", function (ev) {
    if (ev.key !== "Escape") return;
    document.querySelectorAll("details[open]").forEach(function (d) { d.open = false; });
    var nav = document.getElementById("nav-toggle");
    if (nav && nav.checked) { nav.checked = false; nav.focus(); }
  });

  // Help tooltips (.help): shown on hover and focus by the stylesheet; here a tap toggles
  // them, Esc closes them, and they are kept inside the window.
  function fit(tip) {
    tip.style.translate = "";
    var r = tip.getBoundingClientRect(), room = document.documentElement.clientWidth - 16, dx = 0;
    if (r.right > room) dx = Math.max(16 - r.left, room - r.right);
    else if (r.left < 16) dx = Math.min(16 - r.left, room - r.right);
    if (dx) tip.style.translate = dx + "px 0";
  }
  document.querySelectorAll(".help").forEach(function (help) {
    var btn = help.querySelector(".help-tip"), tip = help.querySelector(".tooltip");
    if (!btn || !tip) return;
    function show() { help.classList.remove("closed"); fit(tip); }
    help.addEventListener("mouseenter", show);
    btn.addEventListener("focus", show);
    btn.addEventListener("click", function () {
      var open = !help.classList.contains("open");
      help.classList.toggle("open", open);
      if (open) show(); else help.classList.add("closed");
    });
    help.addEventListener("mouseleave", function () { help.classList.remove("closed"); });
    btn.addEventListener("blur", function () { help.classList.remove("open", "closed"); });
    help.addEventListener("keydown", function (ev) {
      if (ev.key !== "Escape") return;
      ev.stopPropagation();
      help.classList.remove("open");
      help.classList.add("closed");
    });
  });
  document.addEventListener("click", function (ev) {
    document.querySelectorAll(".help.open").forEach(function (h) {
      if (!h.contains(ev.target)) { h.classList.remove("open"); h.classList.add("closed"); }
    });
  });

  // Bulk actions: the bar shows how many rows are chosen and floats while some are; a
  // choice marked data-bulk-for="<actions>" only shows for those actions.
  document.querySelectorAll("[data-bulkbar]").forEach(function (bar) {
    var form = bar.form || bar.closest("form");
    var count = bar.querySelector("[data-bulk-count]");
    var action = bar.querySelector("[data-bulk-action]");
    var choices = bar.querySelectorAll("[data-bulk-for]");
    var clear = bar.querySelector("[data-bulk-clear]");
    var boxes = form.querySelectorAll('input[type=checkbox][name="ids"]');
    var all = document.querySelector('[data-check-all="ids"]');
    function sync() {
      var n = 0;
      boxes.forEach(function (b) { if (b.checked) n++; });
      if (n) bar.removeAttribute("data-idle"); else bar.setAttribute("data-idle", "");
      count.textContent = text("selected", { n: n });
      if (all) { all.checked = n === boxes.length; all.indeterminate = n > 0 && n < boxes.length; }
      choices.forEach(function (c) {
        c.hidden = !action || c.getAttribute("data-bulk-for").split(" ").indexOf(action.value) < 0;
      });
    }
    form.addEventListener("change", sync);
    document.addEventListener("selection", sync);
    if (clear) {
      clear.hidden = false;
      clear.addEventListener("click", function () {
        boxes.forEach(function (b) { b.checked = false; });
        sync();
      });
    }
    sync();
  });
})();
