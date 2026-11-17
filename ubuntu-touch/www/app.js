// SPDX-License-Identifier: Apache-2.0
(function () {
  "use strict";
  var LD = window.FonebrewLineDiff, D = window.FonebrewDrafts;
  var $ = function (id) { return document.getElementById(id); };
  var store = null;
  try { store = window.localStorage; } catch (e) { store = null; }
  var drafts = store ? D.create(store) : null;
  var oldEl = $("old"), newEl = $("new"), ctxEl = $("ctx");

  function ctx() { var n = parseInt(ctxEl.value, 10); return isNaN(n) ? 3 : Math.max(0, Math.min(20, n)); }

  function render() {
    var o = oldEl.value, n = newEl.value, hunks = LD.diff(o, n, ctx()), st = LD.stat(o, n);
    var box = $("hunks"); box.textContent = "";
    $("stat").textContent = (!o && !n) ? "Nothing to compare yet."
      : st.added || st.removed ? "+" + st.added + " added, −" + st.removed + " removed in " + hunks.length + (hunks.length === 1 ? " hunk" : " hunks")
      : "No differences.";
    hunks.forEach(function (h, i) {
      var d = document.createElement("div"); d.className = "hunk";
      var t = document.createElement("h3");
      t.textContent = "Hunk " + (i + 1) + ": old line " + h.oldStart + " (" + h.oldCount + "), new line " + h.newStart + " (" + h.newCount + ")";
      d.appendChild(t);
      h.lines.forEach(function (l) {
        var r = document.createElement("div");
        r.className = "ln " + (l[0] === "+" ? "add" : l[0] === "-" ? "rem" : "ctx");
        var g = document.createElement("span"); g.className = "g"; g.textContent = l[0] === " " ? " " : l[0];
        var s = document.createElement("span");
        s.textContent = l[1];
        // screen-reader / non-colour redundancy
        r.setAttribute("aria-label", (l[0] === "+" ? "added: " : l[0] === "-" ? "removed: " : "unchanged: ") + l[1]);
        r.appendChild(g); r.appendChild(s); d.appendChild(r);
      });
      box.appendChild(d);
    });
    $("patch").value = LD.unified(o, n, "a/before", "b/after", ctx());
  }

  var timer = null;
  function persist() {
    if (!drafts) { $("saved").textContent = "Draft storage unavailable: text is not kept."; return; }
    var ok = drafts.save({ old: oldEl.value, new: newEl.value });
    $("saved").textContent = ok ? "Draft saved on this device." : "Draft could not be saved.";
  }
  function onEdit() { render(); clearTimeout(timer); timer = setTimeout(persist, 150); }
  function flush() { clearTimeout(timer); persist(); }

  if (drafts) { var d = drafts.load(); if (d) { oldEl.value = d.old; newEl.value = d.new; $("saved").textContent = "Restored your last draft."; } }
  oldEl.addEventListener("input", onEdit); newEl.addEventListener("input", onEdit); ctxEl.addEventListener("input", render);
  document.addEventListener("visibilitychange", function () { if (document.hidden) flush(); });
  window.addEventListener("pagehide", flush);
  $("clear").addEventListener("click", function () {
    oldEl.value = ""; newEl.value = ""; if (drafts) drafts.clear(); $("saved").textContent = "Draft cleared."; render();
  });
  render();
})();
