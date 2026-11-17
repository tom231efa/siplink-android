// SipLink management platform: reorder the rows of a list by dragging their handle
// (mouse, touch or pen) or with the arrow keys on the focused handle.
//
// A container marked data-sortable holds the rows (children with data-sort-id); each
// row has a [data-sort-handle] button. With data-sort-url the new order is posted
// there at once (id=…&id=…, the rows' data-sort-id); a failed save puts the rows
// back and shows the error. Without it, the inputs of the rows marked data-sort-pos
// get the new positions (1, 2, …) and are saved with their form.
//
// A list inside a scrolling element (overflow auto, e.g. a card with a bounded height)
// scrolls that element while a row is dragged near its edges or with the wheel;
// otherwise the page scrolls. Disabled handles (a search is entered) do nothing.
(function () {
  "use strict";
  var root = document.documentElement;
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  var live;

  function rows(box) {
    return Array.prototype.filter.call(box.children, function (el) { return el.hasAttribute("data-sort-id"); });
  }
  function order(box) {
    return rows(box).map(function (r) { return r.getAttribute("data-sort-id"); });
  }

  // scroller is the nearest ancestor of the list that scrolls, or null for the page.
  function scroller(box) {
    for (var el = box.parentElement; el && el !== document.body; el = el.parentElement) {
      var y = getComputedStyle(el).overflowY;
      if ((y === "auto" || y === "scroll") && el.scrollHeight > el.clientHeight + 1) return el;
    }
    return null;
  }
  // offset turns viewport positions into positions that scrolling does not change.
  function offset(sc) { return window.scrollY + (sc ? sc.scrollTop : 0); }

  // flip runs mutate (which moves rows, and may scroll) and lets every moved row glide
  // from its old place to the new one; the stylesheet's transition on rows sets the
  // timing. Reads and writes are batched so that long lists stay quick.
  function flip(box, mutate) {
    var list = rows(box), sc = scroller(box), at = offset(sc);
    var before = list.map(function (r) { return r.getBoundingClientRect().top + at; });
    mutate();
    if (reduce.matches) return;
    at = offset(scroller(box));
    var moved = [];
    list.forEach(function (r, i) {
      var dy = before[i] - (r.getBoundingClientRect().top + at);
      if (Math.abs(dy) >= 1) moved.push([r, dy]);
    });
    moved.forEach(function (m) {
      m[0].style.transition = "none";
      m[0].style.transform = "translateY(" + m[1] + "px)";
    });
    if (!moved.length) return;
    box.getBoundingClientRect();
    moved.forEach(function (m) {
      m[0].style.transition = "";
      m[0].style.transform = "";
    });
  }

  function announce(text) {
    if (!live) {
      live = document.createElement("div");
      live.className = "sr-only";
      live.setAttribute("aria-live", "polite");
      document.body.appendChild(live);
    }
    live.textContent = text;
  }

  // A failed save shows the error above the list's card, in the flash style of the pages.
  function showError(box, text) {
    var card = box.closest(".card") || box;
    clearError(box);
    var flash = document.createElement("div");
    flash.className = "flash error";
    flash.setAttribute("role", "alert");
    flash.setAttribute("data-sort-error", "");
    flash.textContent = text;
    card.parentElement.insertBefore(flash, card);
  }
  function clearError(box) {
    var card = box.closest(".card") || box;
    var old = card.parentElement.querySelector(".flash[data-sort-error]");
    if (old) old.remove();
  }

  // errorText is the message of the platform's error page, or a general one.
  function errorText(res) {
    var fallback = "顺序未能保存，请刷新页面后重试。";
    if (!res || !res.text) return Promise.resolve(fallback);
    return res.text().then(function (html) {
      var p = new DOMParser().parseFromString(html, "text/html").querySelector(".error-page p");
      return p && p.textContent.trim() ? p.textContent.trim() : fallback;
    }, function () { return fallback; });
  }

  function arrange(box, ids) {
    var byID = {};
    rows(box).forEach(function (r) { byID[r.getAttribute("data-sort-id")] = r; });
    flip(box, function () { ids.forEach(function (id) { if (byID[id]) box.appendChild(byID[id]); }); });
  }

  // save posts the order; while a request runs, later moves wait and are sent after it.
  function save(box) {
    var url = box.getAttribute("data-sort-url");
    if (!url) {
      rows(box).forEach(function (r, i) {
        r.querySelectorAll("[data-sort-pos]").forEach(function (input) { input.value = i + 1; });
      });
      return;
    }
    if (box.sortBusy) { box.sortDirty = true; return; }
    box.sortBusy = true;
    box.sortDirty = false;
    var ids = order(box);
    var body = new URLSearchParams();
    ids.forEach(function (id) { body.append("id", id); });
    fetch(url, { method: "POST", body: body, credentials: "same-origin" }).then(function (res) {
      if (!res.ok) return Promise.reject(res);
      box.sortSaved = ids;
      clearError(box);
    }).catch(function (res) {
      box.sortDirty = false;
      arrange(box, box.sortSaved);
      return errorText(res).then(function (text) { showError(box, text); });
    }).then(function () {
      box.sortBusy = false;
      if (box.sortDirty) save(box);
    });
  }

  // edgeStep is how far to scroll this frame: more the closer the pointer is to an edge
  // of area, and the most once it has left it.
  function edgeStep(y, area) {
    var top = y - (area.top + area.zoneTop), bottom = area.bottom - area.zoneBottom - y;
    if (top < 0) return -Math.min(32, Math.ceil(-top / 3));
    if (bottom < 0) return Math.min(32, Math.ceil(-bottom / 3));
    return 0;
  }
  function scrollArea(sc) {
    if (!sc) return { top: 0, bottom: window.innerHeight, zoneTop: 72, zoneBottom: 40 };
    var r = sc.getBoundingClientRect();
    return { top: Math.max(r.top, 0), bottom: Math.min(r.bottom, window.innerHeight), zoneTop: 64, zoneBottom: 40 };
  }

  function drag(ev) {
    var handle = ev.target.closest("[data-sort-handle]");
    if (!handle || handle.disabled || ev.button !== 0 || !ev.isPrimary) return;
    var row = handle.closest("[data-sort-id]");
    var box = row && row.parentElement;
    if (!box || !box.hasAttribute("data-sortable")) return;
    ev.preventDefault();
    try { handle.setPointerCapture(ev.pointerId); } catch (e) { /* not an active pointer: events still reach the handle */ }

    var sc = scroller(box), at = offset(sc); // positions are kept in scroll-independent coordinates
    var list = rows(box), from = list.indexOf(row), others = list.filter(function (r) { return r !== row; });
    var rect = function (r) { var b = r.getBoundingClientRect(); return { top: b.top + at, bottom: b.bottom + at }; };
    var me = rect(row), boxes = others.map(rect), first = rect(list[0]), last = rect(list[list.length - 1]);
    var height = me.bottom - me.top, startY = ev.clientY + at, pointerY = ev.clientY, to = from, frame = 0;
    var shifts = others.map(function () { return 0; }), seen = at;
    row.classList.add("sort-dragging");
    root.classList.add("sorting");

    // The row follows the pointer inside the list; the rows it passes step aside, so
    // the open slot shows where it lands.
    function update() {
      var dy = pointerY + offset(sc) - startY;
      var centre = (me.top + me.bottom) / 2 + dy; // the pointer may leave the list; the row may not
      row.style.transform = "translateY(" + Math.max(first.top - me.top, Math.min(last.bottom - me.bottom, dy)) + "px)";
      to = 0;
      boxes.forEach(function (b) { if ((b.top + b.bottom) / 2 < centre) to++; });
      others.forEach(function (r, i) {
        var shift = i >= from && i < to ? -height : i < from && i >= to ? height : 0;
        if (shifts[i] !== shift) {
          shifts[i] = shift;
          r.style.transform = shift ? "translateY(" + shift + "px)" : "";
        }
      });
    }
    // Every frame: scroll when the pointer is near an edge, and follow any scrolling
    // (also the wheel's) so that the row stays under the pointer.
    function tick() {
      var step = edgeStep(pointerY, scrollArea(sc));
      if (step) {
        if (sc) sc.scrollTop += step; else window.scrollBy(0, step);
      }
      if (offset(sc) !== seen) {
        seen = offset(sc);
        update();
      }
      frame = requestAnimationFrame(tick);
    }
    function move(e) {
      pointerY = e.clientY;
      update();
    }
    function finish(cancel) {
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", up);
      handle.removeEventListener("pointercancel", abort);
      document.removeEventListener("keydown", esc, true);
      cancelAnimationFrame(frame);
      root.classList.remove("sorting");
      row.classList.remove("sort-dragging");
      if (cancel || to === from) {
        list.forEach(function (r) { r.style.transform = ""; });
        return;
      }
      flip(box, function () {
        list.forEach(function (r) { r.style.transform = ""; });
        var ref = others[to];
        box.insertBefore(row, ref || others[others.length - 1].nextSibling);
      });
      save(box);
    }
    function up() { finish(false); }
    function abort() { finish(true); }
    function esc(e) {
      if (e.key !== "Escape") return;
      e.preventDefault();
      e.stopPropagation();
      finish(true);
    }
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", up);
    handle.addEventListener("pointercancel", abort);
    document.addEventListener("keydown", esc, true);
    update();
    frame = requestAnimationFrame(tick);
  }

  function key(ev) {
    if (ev.key !== "ArrowUp" && ev.key !== "ArrowDown") return;
    var handle = ev.target.closest("[data-sort-handle]");
    var row = handle && !handle.disabled && handle.closest("[data-sort-id]");
    var box = row && row.parentElement;
    if (!box || !box.hasAttribute("data-sortable")) return;
    ev.preventDefault();
    var list = rows(box), i = list.indexOf(row), j = ev.key === "ArrowUp" ? i - 1 : i + 1;
    if (j < 0 || j >= list.length) return;
    flip(box, function () {
      box.insertBefore(row, ev.key === "ArrowUp" ? list[j] : list[j].nextSibling);
      row.scrollIntoView({ block: "nearest" }); // the list's scroll-margin keeps it clear of a sticky head
    });
    handle.focus({ preventScroll: true });
    announce("已移至第 " + (j + 1) + " 位，共 " + list.length + " 位");
    save(box);
  }

  document.querySelectorAll("[data-sortable]").forEach(function (box) {
    box.sortSaved = order(box);
    box.addEventListener("pointerdown", drag);
    box.addEventListener("keydown", key);
  });
})();
