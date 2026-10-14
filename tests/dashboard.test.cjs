const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/webui/dashboard.js', 'utf8');

function harness(fetchImpl, prompt = () => null) {
  const elements = new Map();
  function element() {
    const children = new Map();
    return { textContent: '', innerHTML: '', value: '', style: {}, disabled: false,
      classList: { add() {}, remove() {} }, appendChild() {},
      querySelector(s) { if (!children.has(s)) children.set(s, element()); return children.get(s); },
      querySelectorAll() { return []; } };
  }
  const storage = new Map();
  const timers = new Set();
  const ctx = vm.createContext({ fetch: fetchImpl, AbortController, URLSearchParams,
    setTimeout(fn, ms) { const id = setTimeout(fn, ms); id.unref(); timers.add(id); return id; },
    clearTimeout(id) { clearTimeout(id); timers.delete(id); }, setInterval() {},
    window: { prompt }, confirm: () => true,
    localStorage: { getItem: k => storage.get(k), setItem: (k, v) => storage.set(k, v) },
    sessionStorage: { getItem: () => null },
    document: { querySelector(s) { if (!elements.has(s)) elements.set(s, element()); return elements.get(s); },
      querySelectorAll() { return []; }, createElement: element, addEventListener() {} }
  });
  vm.runInContext(source, ctx);
  return { ctx, run: code => vm.runInContext(code, ctx), elements, storage,
    cleanup() { timers.forEach(clearTimeout); } };
}
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });

test('cancelled authentication prompts only once and never repeats the failed request', async () => {
  let calls = 0, prompts = 0;
  const h = harness(async () => { calls++; return response({}, 401); }, () => { prompts++; return null; });
  await Promise.all([1, 2, 3].map(() => assert.rejects(h.run("jget('/api/status')"), /401/)));
  assert.equal(calls, 3); assert.equal(prompts, 1); h.cleanup();
});

test('parallel unauthorized requests reuse the corrected password', async () => {
  let prompts = 0;
  const h = harness(async (_, opts) => response({}, opts.headers['X-Access-Key'] === 'correct' ? 200 : 401),
    () => { prompts++; return 'correct'; });
  await Promise.all([h.run("jget('/api/status')"), h.run("jget('/api/version')")]);
  assert.equal(prompts, 1); h.cleanup();
});

test('HTTP and API-level failures cannot be reported as successful commands', async () => {
  const h = harness(async url => url.includes('http-error') ? response({}, 500) : response({ ok: false, message: 'No update downloaded' }));
  await assert.rejects(h.run("command('/http-error', {})"), /500/);
  await assert.rejects(h.run("command('/api/update/install', {})"), /No update downloaded/);
  h.cleanup();
});

test('request timeout aborts an unreachable TV', async () => {
  const h = harness((_, opts) => new Promise((resolve, reject) => {
    opts.signal.addEventListener('abort', () => reject(new Error('aborted')));
  }));
  // Keep the test alive while the production timeout is unref'ed in this harness.
  const keepAlive = setTimeout(() => {}, 1000);
  await assert.rejects(h.run("requestJson('/api/status', {}, 5)"), /aborted/);
  clearTimeout(keepAlive); h.cleanup();
});

test('status polling allows only one pending request per TV', async () => {
  let finish, calls = 0;
  const h = harness(() => { calls++; return new Promise(resolve => { finish = resolve; }); });
  h.run("tvs = [{address: 'tv:8080'}]; cardEls['tv:8080'] = document.createElement('section')");
  const first = h.run('pollTv(tvs[0])');
  await h.run('pollTv(tvs[0])');
  assert.equal(calls, 1);
  finish(response({ state: 'playing' })); await first;
  assert.equal(h.run('polling.size'), 0); h.cleanup();
});

test('removed TVs do not inflate fleet counts', () => {
  const h = harness(async () => response({}));
  h.run("tvs = [{address: 'live'}]; health.live = 'ok'; health.removed = 'off'; updateSummary()");
  assert.equal(h.elements.get('#fleetSummary').textContent, '1 live'); h.cleanup();
});

test('failed channel loading is retried instead of caching an empty list', async () => {
  let calls = 0;
  const h = harness(async () => ++calls === 1 ? response({}, 503) : response([{ id: 1, name: 'News' }]));
  await assert.rejects(h.run("ensureChannels({address: 'tv'})"));
  const channels = await h.run("ensureChannels({address: 'tv'})");
  assert.equal(channels.length, 1); assert.equal(calls, 2); h.cleanup();
});

test('bulk play prefers the exact selected name over a similar earlier channel', async () => {
  let selected;
  const h = harness(async (url, opts) => {
    if (url.endsWith('/api/channels')) return response([{ id: 1, name: 'News Extra' }, { id: 2, name: 'News' }]);
    selected = opts.body.get('channelId'); return response({ ok: true });
  });
  h.run("$('#bulkChannel').value = 'News'");
  await h.run("bulkPlay([{address: 'tv'}])");
  assert.equal(selected, '2'); h.cleanup();
});

test('bulk play and category sync count rejected commands as failures', async () => {
  const h = harness(async url => {
    if (url.endsWith('/api/channels')) return response([{ id: 1, name: 'News' }]);
    if (url.endsWith('/api/groups')) return response([{ name: 'News', hidden: false }]);
    return response({ ok: false });
  });
  h.run("tvs = [{address: 'source'}, {address: 'target'}]; $('#bulkChannel').value = 'News'; $('#syncSource').value = 'source'");
  await h.run('bulkPlay(tvs)');
  assert.match(h.elements.get('#bulkMsg').textContent, /Played on 0 TV/);
  await h.run('copyCategories(tvs)');
  assert.match(h.elements.get('#syncMsg').textContent, /to 0 TV/); h.cleanup();
});

test('fleet check distinguishes ready, current, failed and offline TVs', async () => {
  const h = harness(async url => {
    if (url.endsWith('/api/version')) return response({ versionName: '1.0.47' });
    if (url.includes('ready')) return response({ ok: true });
    if (url.includes('current')) return response({ ok: false, message: 'Up to date (v1.0.47)' });
    if (url.includes('failed')) return response({ ok: false, message: 'Update check failed: timeout' });
    throw new Error('offline');
  });
  h.run("tvs = ['ready','current','failed','offline'].map(address => ({address}))");
  await h.run("$('#fleetCheck').onclick()");
  assert.match(h.elements.get('#fleetUpdMsg').textContent, /1 ready to update · 1 up to date · 2 could not be checked/);
  assert.equal(h.elements.get('#fleetCheck').disabled, false); h.cleanup();
});

test('fleet install counts only accepted requests', async () => {
  const h = harness(async url => {
    if (url.includes('accepted')) return response({ ok: true });
    if (url.includes('rejected')) return response({ ok: false });
    throw new Error('offline');
  });
  h.run("tvs = ['accepted','rejected','offline'].map(address => ({address})); tvs.forEach(tv => fleetVer[tv.address] = {updateAvailable:true})");
  await h.run("$('#fleetInstall').onclick()");
  assert.match(h.elements.get('#fleetUpdMsg').textContent, /Install accepted by 1 TV\(s\). 2 failed/);
  assert.equal(h.elements.get('#fleetInstall').disabled, false); h.cleanup();
});

test('repeated fleet checks cannot overlap', async () => {
  let calls = 0, finish;
  const h = harness(url => {
    if (url.endsWith('/api/version')) return Promise.resolve(response({versionName: '1.0.47'}));
    calls++; return new Promise(resolve => { finish = resolve; });
  });
  h.run("tvs = [{address:'tv'}]");
  const first = h.run("$('#fleetCheck').onclick()");
  await h.run("$('#fleetCheck').onclick()");
  assert.equal(calls, 1);
  finish(response({ok: false, message: 'Up to date (v1.0.47)'})); await first; h.cleanup();
});
