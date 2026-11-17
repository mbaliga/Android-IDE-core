// SPDX-License-Identifier: Apache-2.0
// Continuous draft persistence. Lomiri freezes (SIGSTOPs) unfocused apps within seconds, so a draft
// must already be stored by the time the app loses focus: save on every edit (debounced a few ms)
// and again on visibilitychange/pagehide. Storage is injected so this runs under node tests;
// every access is try/catch'd because WebView storage can be absent or throw.
(function (root) {
  "use strict";
  var KEY = "fonebrew.ut.draft.v1";

  function create(storage, now) {
    now = now || function () { return Date.now(); };
    return {
      save: function (draft) {
        try {
          storage.setItem(KEY, JSON.stringify({ v: 1, savedAt: now(), old: String(draft.old || ""), new: String(draft.new || "") }));
          return true;
        } catch (e) { return false; }
      },
      load: function () {
        try {
          var raw = storage.getItem(KEY);
          if (!raw) return null;
          var d = JSON.parse(raw);
          if (!d || d.v !== 1 || typeof d.old !== "string" || typeof d.new !== "string") return null;
          return d;
        } catch (e) { return null; }
      },
      clear: function () { try { storage.removeItem(KEY); return true; } catch (e) { return false; } }
    };
  }

  var api = { create: create, KEY: KEY };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.FonebrewDrafts = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
