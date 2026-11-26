/* SPDX-License-Identifier: Apache-2.0 */
(function (root) {
  'use strict';
  const modes = ['Single', 'Council · models', 'Council · experts'];
  function initial() {
    return { connected: true, host: 'Phone nearby', mode: modes[0], screen: 'home',
      draft: '', quote: false, rating: 0, branch: 1, revision: 1, task: 'idle',
      outbox: [], history: [], notice: 'Ready for a short request.',
      eink: false, displayOnline: true, page: 0, large: false, document: null, pending: null };
  }
  function document(s) {
    return { revision: s.revision, title: 'Build investigation', pages: [
      'Summary\n\nThe sample build failed because a configuration value was missing. The proposed change validates it before starting the build.\n\nThis is sample content. No repository or build has been changed.',
      'Proposed change · sample diff\n\n− startBuild(config.target)\n+ require(config.target.isNotBlank())\n+ startBuild(config.target)\n\nReview on a larger surface before applying. This prototype never commits changes.',
      'Next steps\n\n1. Inspect the actual build error.\n2. Check the target configuration.\n3. Review a proposed diff.\n4. Run the relevant tests.\n\nHost: ' + s.host + '\nConversation mode: ' + s.mode + '\nBranch: ' + s.branch
    ] };
  }
  function reduce(state, action) {
    const s = structuredClone(state);
    const log = text => s.history.push(text);
    switch (action.type) {
      case 'screen': s.screen = action.value; break;
      case 'draft': s.draft = action.value.slice(0, 1200); break;
      case 'connection': s.connected = action.value; s.notice = s.connected ? 'Host reachable. Review queued requests before syncing.' : 'Host unreachable. New requests stay queued.'; break;
      case 'host':
        if (s.task === 'running' || s.task === 'stop-requested' || s.outbox.length) { s.notice = 'Finish this task and sync queued requests before changing host.'; break; }
        s.host = action.value; s.notice = 'Demo host selected: ' + s.host; break;
      case 'send': {
        if (!s.draft.trim()) { s.notice = 'Add a request first.'; break; }
        if (s.task === 'running' || s.task === 'stop-requested') { s.notice = 'Wait for the current task or request a stop.'; break; }
        const item = { text: s.draft.trim(), quote: s.quote, mode: s.mode, host: s.host, branch: s.branch };
        if (!s.connected) { s.outbox.push(item); s.notice = 'Queued on this demo device. Reconnect, then Sync queued.'; }
        else { s.task = 'running'; log('Accepted sample request: ' + item.text); s.notice = 'Sample task started. Simulate a result when ready.'; }
        s.draft = ''; s.quote = false; s.screen = 'task'; break;
      }
      case 'sync':
        if (!s.connected || !s.outbox.length || s.task === 'running' || s.task === 'stop-requested') break;
        log('Accepted queued sample request: ' + s.outbox.shift().text); s.task = 'running'; s.notice = 'One queued request accepted by the simulated host.'; break;
      case 'result':
        if (!s.connected || !['running', 'stop-requested'].includes(s.task)) break;
        s.task = 'complete'; s.revision++; s.pending = document(s); s.notice = 'Sample result ready. Read it on the watch or optional display.'; log('Result received at revision ' + s.revision); break;
      case 'stop':
        if (!s.connected || s.task !== 'running') break;
        s.task = 'stop-requested'; s.notice = 'Stop requested. Waiting for host confirmation.'; log('Stop requested'); break;
      case 'stopped':
        if (!s.connected || s.task !== 'stop-requested') break;
        s.task = 'stopped'; s.notice = 'Host confirmed that the sample task stopped.'; log('Stop confirmed'); break;
      case 'rate': s.rating = Math.max(-2, Math.min(2, Number(action.value) || 0)); log('Message value ' + s.rating); break;
      case 'reply': s.quote = true; s.screen = 'capture'; s.notice = 'Reply linked to the sample result.'; break;
      case 'regenerate':
        if (!s.connected || ['running', 'stop-requested'].includes(s.task) || s.outbox.length) { s.notice = 'Reconnect and finish pending work before branching.'; break; }
        s.branch++; s.task = 'running'; s.screen = 'task'; log('Regenerate from sample message on branch ' + s.branch); s.notice = 'New sample branch started. Earlier history is preserved.'; break;
      case 'mode':
        if (!modes.includes(action.value)) break;
        s.mode = action.value; log('Next turn mode: ' + s.mode); s.notice = s.mode + ' applies to the next request.'; break;
      case 'eink': s.eink = action.value; break;
      case 'displayConnection': s.displayOnline = action.value; break;
      case 'publish':
        if (!s.eink) { s.notice = 'Optional display is off. The result is available under Message.'; break; }
        if (!s.displayOnline) { s.notice = 'Display offline. It keeps its last page.'; break; }
        s.pending = document(s); s.notice = 'Document ready. Refresh the display when you want to read it.'; break;
      case 'refresh':
        if (s.eink && s.displayOnline && s.pending) { s.document = s.pending; s.pending = null; s.page = 0; } break;
      case 'page':
        if (s.document) s.page = Math.max(0, Math.min(s.document.pages.length - 1, s.page + action.value)); break;
      case 'font': s.large = action.value; break;
      case 'reset': return initial();
    }
    return s;
  }
  const api = { initial, reduce, modes };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.WatchBrew = api;
})(globalThis);
