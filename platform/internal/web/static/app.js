// SipLink management platform: small progressive enhancements. Every page works
// without this file.
(function () {
  "use strict";
  var root = document.documentElement;
  root.classList.add("js");

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
    };
    dialog.showModal();
  });

  // Selects marked data-autosubmit submit their form when changed; the form's
  // fallback button (data-js-hide) is hidden by the stylesheet.
  document.querySelectorAll("select[data-autosubmit], input[data-autosubmit]").forEach(function (sel) {
    sel.addEventListener("change", function () { sel.form.submit(); });
  });

  // Times: the server writes its own zone; show them in the viewer's local time.
  // Those marked data-rel read "3 分钟前" and keep the full time in the title.
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function full(d, dateOnly) {
    var text = d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
    return dateOnly ? text : text + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
  }
  function relative(d) {
    var s = (Date.now() - d.getTime()) / 1000;
    if (s < -60) return null;
    if (s < 60) return "刚刚";
    if (s < 3600) return Math.floor(s / 60) + " 分钟前";
    if (s < 86400) return Math.floor(s / 3600) + " 小时前";
    if (s < 86400 * 2) return "昨天 " + pad(d.getHours()) + ":" + pad(d.getMinutes());
    if (s < 86400 * 7) return Math.floor(s / 86400) + " 天前";
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

  // Hosted book credentials: the manual fields only count when "手动指定" is chosen.
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
        t.textContent = "已复制";
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
      label(btn).textContent = show ? "隐藏" : "显示";
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
    tip.style.left = "0px";
    var r = tip.getBoundingClientRect(), room = document.documentElement.clientWidth - 16;
    if (r.right > room) tip.style.left = Math.max(16 - r.left, room - r.right) + "px";
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

  // Bulk actions: the bar shows how many rows are chosen and floats while some are;
  // the template choice only shows for "更换客户端模板".
  document.querySelectorAll("[data-bulkbar]").forEach(function (bar) {
    var form = bar.form || bar.closest("form");
    var count = bar.querySelector("[data-bulk-count]");
    var action = bar.querySelector("[data-bulk-action]");
    var tpl = bar.querySelector("[data-bulk-template]");
    var clear = bar.querySelector("[data-bulk-clear]");
    var boxes = form.querySelectorAll('input[type=checkbox][name="ids"]');
    var all = document.querySelector('[data-check-all="ids"]');
    function sync() {
      var n = 0;
      boxes.forEach(function (b) { if (b.checked) n++; });
      if (n) bar.removeAttribute("data-idle"); else bar.setAttribute("data-idle", "");
      count.textContent = "已选 " + n + " 个";
      if (all) { all.checked = n === boxes.length; all.indeterminate = n > 0 && n < boxes.length; }
      if (tpl && action) tpl.hidden = action.value !== "template";
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
