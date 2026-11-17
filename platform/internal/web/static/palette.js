// SipLink management platform: quick-jump palette on Ctrl/⌘+K. It lists the sidebar's
// pages and offers a client search through the clients list's own "q" parameter.
// Nothing depends on it; without JavaScript the sidebar and the list's search remain.
(function () {
  "use strict";
  var dlg = document.getElementById("palette");
  if (!dlg || !dlg.showModal) return;
  var input = dlg.querySelector("[data-palette-input]");
  var list = dlg.querySelector("[data-palette-list]");
  var mac = /Mac|iPhone|iPad/.test(navigator.platform || "");
  var pages = [];
  document.querySelectorAll(".nav a").forEach(function (a) {
    var group = a.closest(".nav-group");
    var g = group && group.querySelector(".nav-label");
    var icon = a.querySelector("svg");
    pages.push({ href: a.getAttribute("href"), text: a.textContent.trim(), where: g ? g.textContent.trim() : "",
      icon: icon ? icon.outerHTML : "" });
  });
  var searchIcon = (dlg.querySelector(".palette-search svg") || {}).outerHTML || "";
  var items = [];
  var active = 0;

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text) e.textContent = text;
    return e;
  }
  function option(it, i) {
    var li = el("li");
    var a = el("a");
    a.href = it.href;
    a.id = "palette-opt-" + i;
    a.setAttribute("role", "option");
    a.innerHTML = it.icon; // our own sidebar icons, not user text
    a.appendChild(el("span", "", it.text));
    if (it.where) a.appendChild(el("span", "where", it.where));
    a.addEventListener("mousemove", function () { select(i); });
    li.appendChild(a);
    return li;
  }
  function render() {
    var q = input.value.trim().toLowerCase();
    items = pages.filter(function (p) { return !q || p.text.toLowerCase().indexOf(q) >= 0 || p.where.toLowerCase().indexOf(q) >= 0; });
    if (q) items.push({ href: "/clients?q=" + encodeURIComponent(input.value.trim()), text: "搜索客户端：" + input.value.trim(), where: "客户端", icon: searchIcon });
    list.textContent = "";
    if (!q) list.appendChild(el("li", "group", "页面"));
    items.forEach(function (it, i) { list.appendChild(option(it, i)); });
    if (!items.length) list.appendChild(el("li", "none", "无匹配结果"));
    select(0);
  }
  function select(i) {
    var opts = list.querySelectorAll("a");
    if (!opts.length) return;
    active = (i + opts.length) % opts.length;
    opts.forEach(function (a, n) { a.setAttribute("aria-selected", n === active ? "true" : "false"); });
    opts[active].scrollIntoView({ block: "nearest" });
    input.setAttribute("aria-activedescendant", opts[active].id);
  }
  function open() {
    if (dlg.open) return;
    input.value = "";
    render();
    dlg.showModal();
    input.focus();
  }

  input.addEventListener("input", render);
  input.addEventListener("keydown", function (ev) {
    if (ev.key === "ArrowDown") { ev.preventDefault(); select(active + 1); }
    else if (ev.key === "ArrowUp") { ev.preventDefault(); select(active - 1); }
    else if (ev.key === "Enter") {
      ev.preventDefault();
      var a = list.querySelectorAll("a")[active];
      if (a) location.href = a.href;
    }
  });
  dlg.addEventListener("click", function (ev) { if (ev.target === dlg) dlg.close(); });
  document.addEventListener("keydown", function (ev) {
    if ((ev.ctrlKey || ev.metaKey) && !ev.altKey && (ev.key === "k" || ev.key === "K")) {
      ev.preventDefault();
      if (dlg.open) dlg.close(); else open();
    }
  });
  document.querySelectorAll("[data-palette-open]").forEach(function (btn) {
    btn.hidden = false;
    btn.addEventListener("click", open);
    var key = btn.querySelector("[data-palette-key]");
    if (key) key.textContent = mac ? "⌘K" : "Ctrl K";
  });
})();
