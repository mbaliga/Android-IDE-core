// SPDX-License-Identifier: Apache-2.0
const test = require('node:test');
const assert = require('node:assert/strict');
const {initial, reduce} = require('./model.js');
const apply = (s,...actions) => actions.reduce(reduce,s);
const request = [{type:'draft',value:'Check the build'},{type:'send'}];
test('watch-only request completes with optional display disabled', () => {
  const s = apply(initial(),...request,{type:'result'});
  assert.equal(s.eink,false); assert.equal(s.task,'complete'); assert.equal(s.document,null);
});
test('offline requests require explicit sync and are delivered one at a time', () => {
  let s = apply(initial(),{type:'connection',value:false},...request,...request);
  assert.equal(s.outbox.length,2); assert.equal(s.task,'idle');
  s = reduce(s,{type:'connection',value:true}); assert.equal(s.outbox.length,2);
  s = reduce(s,{type:'sync'}); assert.equal(s.outbox.length,1); assert.equal(s.task,'running');
  assert.deepEqual(reduce(s,{type:'sync'}),s);
});
test('stop request is not confirmation; a result can arrive first', () => {
  const s = apply(initial(),...request,{type:'stop'});
  assert.equal(s.task,'stop-requested');
  assert.equal(reduce(s,{type:'stopped'}).task,'stopped');
  assert.equal(reduce(s,{type:'result'}).task,'complete');
});
test('host cannot change underneath active or queued work', () => {
  const active = apply(initial(),...request,{type:'host',value:'My remote computer'});
  assert.equal(active.host,'Phone nearby');
  const queued = apply(initial(),{type:'connection',value:false},...request,{type:'host',value:'My remote computer'});
  assert.equal(queued.host,'Phone nearby');
});
test('e-ink preserves the current page until an explicit online refresh', () => {
  let s = apply(initial(),{type:'eink',value:true},{type:'publish'},{type:'refresh'},{type:'page',value:1});
  const old = s.document;
  s = apply(s,...request,{type:'result'});
  assert.deepEqual(s.document,old); assert.equal(s.page,1); assert.ok(s.pending);
  s = apply(s,{type:'displayConnection',value:false},{type:'refresh'});
  assert.deepEqual(s.document,old);
  s = apply(s,{type:'displayConnection',value:true},{type:'refresh'});
  assert.equal(s.page,0); assert.equal(s.document.revision,2); assert.equal(s.pending,null);
});
test('disabling display preserves its page and never blocks a watch task', () => {
  let s = apply(initial(),{type:'eink',value:true},{type:'publish'},{type:'refresh'},{type:'page',value:1},{type:'eink',value:false},...request);
  assert.equal(s.task,'running'); assert.equal(s.page,1); assert.ok(s.document);
});
test('rating is bounded; mode changes and regeneration preserve history', () => {
  let s = apply(initial(),{type:'rate',value:99},{type:'mode',value:'Council · experts'});
  assert.equal(s.rating,2); assert.equal(s.mode,'Council · experts');
  const before = s.history.slice(); s = reduce(s,{type:'regenerate'});
  assert.equal(s.branch,2); assert.equal(s.task,'running'); assert.deepEqual(s.history.slice(0,before.length),before);
});
test('reply attaches context and empty requests cannot start a task', () => {
  assert.equal(reduce(initial(),{type:'send'}).task,'idle');
  const s = apply(initial(),{type:'connection',value:false},{type:'reply'},...request);
  assert.equal(s.outbox[0].quote,true); assert.equal(s.quote,false);
});
test('reducer does not mutate its input', () => {
  const original = initial(), copy = structuredClone(original);
  apply(original,...request,{type:'result'}); assert.deepEqual(original,copy);
});
