// Injected by tests/smoke.sh after app.js. Drives the real page and reports through the DOM title.
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var r = [];
  $("old").value = "a\nb\nc\n"; $("new").value = "a\nB\nc\n";
  $("old").dispatchEvent(new Event("input"));
  r.push($("stat").textContent.indexOf("+1 added") === 0 ? "stat-ok" : "stat-BAD:" + $("stat").textContent);
  r.push($("hunks").querySelectorAll(".ln.add").length === 1 && $("hunks").querySelectorAll(".ln.rem").length === 1 ? "hunk-ok" : "hunk-BAD");
  r.push($("patch").value.indexOf("@@ -1,3 +1,3 @@") > 0 ? "patch-ok" : "patch-BAD");
  setTimeout(function () {
    var d = JSON.parse(window.localStorage.getItem(window.FonebrewDrafts.KEY) || "null");
    r.push(d && d.new === "a\nB\nc\n" ? "draft-ok" : "draft-BAD");
    var pre = document.createElement("pre"); pre.id = "smoke-result"; pre.textContent = "SMOKE:" + r.join(",");
    document.body.appendChild(pre);
  }, 400);
})();
