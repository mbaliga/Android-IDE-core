// SPDX-License-Identifier: Apache-2.0
// Parity test: the JS port must reproduce the Kotlin-generated golden vectors exactly.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const LD = require("../www/linediff.js");

const golden = JSON.parse(fs.readFileSync(path.join(__dirname, "../golden/linediff.v1.json"), "utf8"));
assert.equal(golden.schema, "fonebrew.linediff.golden.v1");
assert.ok(golden.cases.length >= 15, "golden file unexpectedly small");

let n = 0;
for (const c of golden.cases) {
  assert.deepEqual(LD.stat(c.old, c.new), c.stat, c.name + ": stat");
  assert.equal(LD.unified(c.old, c.new, "a/f.txt", "b/f.txt", c.context), c.unified, c.name + ": unified");
  assert.deepEqual(LD.diff(c.old, c.new, c.context), c.hunks, c.name + ": hunks");
  n++;
}

// Safety valve (not in the golden file: too large): pathological input falls back to whole replace.
const big = Array.from({ length: 3000 }, (_, i) => "a" + i).join("\n");
const big2 = Array.from({ length: 3000 }, (_, i) => "b" + i).join("\n");
const st = LD.stat(big, big2);
assert.deepEqual(st, { added: 3000, removed: 3000 });

console.log("linediff parity: " + n + " golden cases + safety valve OK");
