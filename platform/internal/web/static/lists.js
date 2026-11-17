// SipLink management platform: long lists inside a card.
//
// A search field marked data-list-filter="<tbody id>" hides the rows whose data-filter
// text (or, without it, their text) does not contain every word typed; the card's
// [data-list-count] shows "shown / all" while a search is entered and [data-list-empty]
// shows when nothing matches. While a search is entered the rows cannot be reordered:
// their drag handles are disabled.
//
// A bar marked data-resize="<name>" sets the height of the scrolling list right above
// it: drag it (mouse, touch, pen), use the arrow keys on it, double-click to reset. The
// height is remembered per browser.
(function () {
  "use strict";
  var root = document.documentElement;

  document.querySelectorAll("[data-list-filter]").forEach(function (input) {
    var body = document.getElementById(input.getAttribute("data-list-filter"));
    var card = input.closest(".card");
    if (!body || !card) return;
    var count = card.querySelector("[data-list-count]");
    var empty = card.querySelector("[data-list-empty]");
    var rows = Array.prototype.slice.call(body.children);
    var texts = rows.map(function (r) { return (r.getAttribute("data-filter") || r.textContent).toLowerCase(); });
    var frame = 0, searching = false;
    function apply() {
      frame = 0;
      var words = input.value.toLowerCase().split(/\s+/).filter(Boolean), shown = 0;
      rows.forEach(function (r, i) {
        var hit = words.every(function (w) { return texts[i].indexOf(w) >= 0; });
        if (r.hidden === hit) r.hidden = !hit;
        if (hit) shown++;
      });
      if (count) {
        count.hidden = !words.length;
        count.textContent = shown + " / " + rows.length;
      }
      if (empty) empty.hidden = shown > 0;
      if (searching !== words.length > 0) {
        searching = words.length > 0;
        body.querySelectorAll("[data-sort-handle]").forEach(function (h) { h.disabled = searching; });
      }
    }
    input.addEventListener("input", function () { if (!frame) frame = requestAnimationFrame(apply); });
    apply(); // a value the browser restored on "back"
  });

  var MIN = 160, STEP = 24;
  function remember(key, h) {
    try {
      if (h) localStorage.setItem(key, String(h)); else localStorage.removeItem(key);
    } catch (e) { /* storage unavailable: the height lasts for this page only */ }
  }
  function recalled(key) {
    try { return parseInt(localStorage.getItem(key), 10) || 0; } catch (e) { return 0; }
  }

  document.querySelectorAll("[data-resize]").forEach(function (bar) {
    var list = bar.previousElementSibling;
    if (!list) return;
    var key = "siplink.listHeight." + bar.getAttribute("data-resize");
    // Never taller than the list's rows nor than the window: it stays a local scroller.
    function most() { return Math.max(MIN, Math.min(list.scrollHeight, window.innerHeight - 120)); }
    function show() {
      bar.setAttribute("aria-valuemin", MIN);
      bar.setAttribute("aria-valuemax", most());
      bar.setAttribute("aria-valuenow", Math.round(list.getBoundingClientRect().height));
    }
    function set(h, keep) {
      h = Math.round(Math.max(MIN, Math.min(most(), h)));
      list.style.maxHeight = h + "px";
      if (keep) remember(key, h);
      show();
    }
    var saved = recalled(key);
    if (saved) list.style.maxHeight = Math.max(MIN, saved) + "px";
    show();

    bar.addEventListener("pointerdown", function (ev) {
      if (ev.button !== 0 || !ev.isPrimary) return;
      ev.preventDefault();
      try { bar.setPointerCapture(ev.pointerId); } catch (e) { /* not an active pointer */ }
      var startY = ev.clientY, startH = list.getBoundingClientRect().height;
      bar.classList.add("active");
      root.classList.add("resizing");
      function move(e) { set(startH + e.clientY - startY, false); }
      function up() {
        bar.removeEventListener("pointermove", move);
        bar.removeEventListener("pointerup", up);
        bar.removeEventListener("pointercancel", up);
        bar.classList.remove("active");
        root.classList.remove("resizing");
        remember(key, Math.round(list.getBoundingClientRect().height));
      }
      bar.addEventListener("pointermove", move);
      bar.addEventListener("pointerup", up);
      bar.addEventListener("pointercancel", up);
    });
    bar.addEventListener("keydown", function (ev) {
      var h = list.getBoundingClientRect().height, step = ev.shiftKey ? STEP * 4 : STEP;
      if (ev.key === "ArrowUp") set(h - step, true);
      else if (ev.key === "ArrowDown") set(h + step, true);
      else if (ev.key === "Home") set(MIN, true);
      else if (ev.key === "End") set(most(), true);
      else return;
      ev.preventDefault();
    });
    bar.addEventListener("dblclick", function () {
      list.style.maxHeight = "";
      remember(key, 0);
      show();
    });
  });
})();
