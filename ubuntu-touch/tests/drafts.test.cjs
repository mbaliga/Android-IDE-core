// SPDX-License-Identifier: Apache-2.0
const assert = require("node:assert/strict");
const D = require("../www/drafts.js");

function memStorage() { const m = new Map(); return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, v), removeItem: k => m.delete(k), _m: m }; }

const s = memStorage();
const d = D.create(s, () => 42);
assert.equal(d.load(), null);
assert.equal(d.save({ old: "a\n", new: "b\n" }), true);
assert.deepEqual(d.load(), { v: 1, savedAt: 42, old: "a\n", new: "b\n" });
assert.equal(d.clear(), true);
assert.equal(d.load(), null);

// corrupt / foreign payloads are ignored, never thrown
s.setItem(D.KEY, "{not json"); assert.equal(d.load(), null);
s.setItem(D.KEY, JSON.stringify({ v: 2, old: "", new: "" })); assert.equal(d.load(), null);
s.setItem(D.KEY, JSON.stringify({ v: 1, old: 1, new: "" })); assert.equal(d.load(), null);

// throwing storage (quota, disabled) degrades to false/null
const bad = { getItem() { throw new Error("denied"); }, setItem() { throw new Error("quota"); }, removeItem() { throw new Error("denied"); } };
const db = D.create(bad);
assert.equal(db.save({ old: "x", new: "y" }), false);
assert.equal(db.load(), null);
assert.equal(db.clear(), false);

console.log("drafts: 9 assertions OK");
