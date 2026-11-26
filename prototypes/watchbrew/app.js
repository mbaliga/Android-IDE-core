/* SPDX-License-Identifier: Apache-2.0 */
'use strict';
let state = WatchBrew.initial();
const $ = id => document.getElementById(id);
const escapeHtml = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const button = (label, action, primary = false) => `<button data-action="${action}" class="${primary ? 'primary' : ''}">${label}</button>`;
function dispatch(action) {
  const previousScreen = state.screen;
  const focused = document.activeElement;
  const focusId = focused && focused.id;
  const rating = focused && focused.dataset && focused.dataset.rating;
  state = WatchBrew.reduce(state, action); render();
  if (state.screen !== previousScreen) {
    document.querySelector('.watch-scroll').scrollTop = 0;
    const heading = document.querySelector('#watch-content h2');
    if (heading) { heading.tabIndex = -1; heading.focus({preventScroll:true}); }
  } else if (focusId && $(focusId)) $(focusId).focus({preventScroll:true});
  else if (rating !== undefined) {
    const control = document.querySelector('[data-rating="' + rating + '"]');
    if (control) control.focus({preventScroll:true});
  }
}
function render() {
  const s = state;
  $('connected').checked = s.connected; $('host').value = s.host;
  $('eink-enabled').checked = s.eink; $('display-online').checked = s.displayOnline;
  $('font-large').checked = s.large; $('notice').textContent = s.notice;
  $('watch-link').textContent = s.connected ? 'LINKED · DEMO' : 'OFFLINE';
  const back = button('← Home', 'home');
  const statuses = { idle: 'Ready when you are.', running: 'Working remotely.', 'stop-requested': 'Waiting for stop.', stopped: 'Task stopped.', complete: 'Result ready.' };
  let content = '';
  if (s.screen === 'home') content = `<p class="mini">FONEBREW / IDE CORE</p><h2>Your work,<br>within reach.</h2><p>${escapeHtml(s.host)}<br>${escapeHtml(s.mode)}</p>` + button('Capture a request', 'capture', true) + button('Task · ' + statuses[s.task], 'task') + button('Message & mode', 'message') + `<p class="mini">${s.outbox.length} queued · branch ${s.branch}</p>`;
  if (s.screen === 'capture') content = back + '<h2>What’s next?</h2><p>Type a request or try sample dictation. Review before sending.</p>' + (s.quote ? '<p class="mini">Replying to: sample build result</p>' : '') + `<label for="draft">Your request</label><textarea id="draft" maxlength="1200" placeholder="Investigate the failing build…">${escapeHtml(s.draft)}</textarea>` + button('Use sample dictation', 'sample') + button(s.connected ? 'Send to host' : 'Queue request', 'send', true);
  if (s.screen === 'task') content = back + `<h2>${statuses[s.task]}</h2><p>${escapeHtml(s.host)}<br>${s.connected ? 'Connection available' : 'Last known state · offline'}</p><p class="mini">${s.outbox.length} request(s) queued</p>` + (s.task === 'running' ? button('Request stop', 'stop') : '') + (s.task === 'stop-requested' ? '<p>Cancellation is not confirmed. The host may still be working.</p>' : '') + (s.outbox.length ? button('Sync queued request', 'sync') : '') + button('Read sample result', 'message');
  if (s.screen === 'message') content = back + '<p class="mini">SAMPLE MESSAGE · NO MODEL CALLED</p><h2>Review the target.</h2><p>The build needs a valid target configuration. Check it before starting again.</p><label>Message value</label><div class="rating">' + [-2,-1,0,1,2].map(n => `<button data-rating="${n}" aria-label="Set message value to ${n}" aria-pressed="${s.rating === n}">${n > 0 ? '+' : ''}${n}</button>`).join('') + '</div>' + button('Reply / quote', 'reply') + button('Regenerate from here', 'review') + button('Show on e-ink', 'publish') + '<label for="mode">Mode for next request</label><select id="mode">' + WatchBrew.modes.map(m => `<option${s.mode === m ? ' selected' : ''}>${m}</option>`).join('') + '</select><p class="mini">Mode changes preserve previous turns.</p>';
  if (s.screen === 'review') content = back + '<h2>Start a branch?</h2><p>Regenerate from this sample message. Existing history stays available.</p><p class="mini">Host: ' + escapeHtml(s.host) + '<br>Mode: ' + escapeHtml(s.mode) + '</p>' + button('Create branch & regenerate', 'regenerate', true) + button('Keep current branch', 'message');
  const scroller = document.querySelector('.watch-scroll');
  const oldScroll = scroller.scrollTop;
  $('watch-content').innerHTML = content;
  scroller.scrollTop = oldScroll;
  if ($('draft')) $('draft').addEventListener('input', e => { state = WatchBrew.reduce(state, { type: 'draft', value: e.target.value }); });
  if ($('mode')) $('mode').addEventListener('change', e => dispatch({type:'mode',value:e.target.value}));
  $('reader-state').textContent = !s.eink ? 'OPTIONAL · OFF' : !s.displayOnline ? 'OFFLINE · CACHED' : s.pending ? 'UPDATE AVAILABLE' : 'READY';
  const reader = $('reader-content'); reader.classList.toggle('large', s.large);
  if (!s.eink) reader.innerHTML = '<div class="empty"><h2>Room for the longer thought.</h2><p>Turn on the optional e-ink view to read code, conversations and results here. Your watch works independently.</p></div>';
  else if (!s.document) reader.innerHTML = '<div class="empty"><h2>Your reading space.</h2><p>On the watch, open Message → Show on e-ink. Then refresh this document when you’re ready.</p></div>';
  else reader.innerHTML = `<h2>${escapeHtml(s.document.title)}</h2><pre>${escapeHtml(s.document.pages[s.page])}</pre>`;
  $('page-count').textContent = s.eink && s.document ? `${s.page+1} / ${s.document.pages.length} · rev ${s.document.revision}` : '—';
  $('previous').disabled = !s.eink || !s.document || s.page === 0;
  $('next').disabled = !s.eink || !s.document || s.page >= s.document.pages.length-1;
  $('refresh').disabled = !s.eink || !s.displayOnline || !s.pending;
  $('result').disabled = !s.connected || !['running','stop-requested'].includes(s.task);
  $('stopped').disabled = !s.connected || s.task !== 'stop-requested';
  $('events').replaceChildren(...s.history.map(text => { const li = document.createElement('li'); li.textContent = text; return li; }));
}
$('watch-content').addEventListener('click', event => {
  const target = event.target.closest('button'); if (!target) return;
  if (target.dataset.rating !== undefined) return dispatch({type:'rate',value:target.dataset.rating});
  const action = target.dataset.action;
  if (['home','capture','task','message','review'].includes(action)) { dispatch({type:'screen',value:action}); document.querySelector('.watch-scroll').scrollTop = 0; }
  else if (action === 'sample') dispatch({type:'draft',value:'Investigate the failing build and prepare a short explanation and proposed diff.'});
  else dispatch({type:action});
});
for (const [id,type] of [['connected','connection'],['eink-enabled','eink'],['display-online','displayConnection'],['font-large','font']]) $(id).addEventListener('change',e => dispatch({type,value:e.target.checked}));
$('host').addEventListener('change',e => dispatch({type:'host',value:e.target.value}));
for (const [id,type] of [['result','result'],['stopped','stopped'],['reset','reset'],['refresh','refresh']]) $(id).addEventListener('click',() => dispatch({type}));
$('previous').addEventListener('click',() => dispatch({type:'page',value:-1}));
$('next').addEventListener('click',() => dispatch({type:'page',value:1}));
render();
