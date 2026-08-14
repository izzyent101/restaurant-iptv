'use strict';

// The dashboard is served by one TV but controls all of them. It talks to
// each TV directly at http://<address>/api/* (CORS is enabled on every TV).

const $ = (s) => document.querySelector(s);
let tvs = [];                 // [{name, address}]
const channelCache = {};      // address -> [channels]
const cardEls = {};           // address -> card element

// Shared control password (same on all TVs). Sent to every TV as X-Access-Key.
function ak() { return sessionStorage.getItem('ak') || ''; }
function promptKey() {
  const pw = window.prompt('A TV is password-protected. Enter the control password (must match on all TVs):');
  if (pw != null) sessionStorage.setItem('ak', pw);
}

function form(data) {
  const body = new URLSearchParams();
  Object.keys(data).forEach((k) => { if (data[k] != null) body.append(k, data[k]); });
  return { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-Access-Key': ak() }, body };
}
async function jget(url) {
  let r = await fetch(url, { cache: 'no-store', headers: { 'X-Access-Key': ak() } });
  if (r.status === 401) { promptKey(); r = await fetch(url, { cache: 'no-store', headers: { 'X-Access-Key': ak() } }); }
  return r.json();
}
function base(tv) { return 'http://' + tv.address; }

// ---------- TV list ----------
async function loadTvs() {
  tvs = await jget('/api/tvs');
  $('#emptyHint').style.display = tvs.length ? 'none' : 'block';
  renderGrid();
  populateSyncSource();
}

function populateSyncSource() {
  ['#syncSource', '#browseSource'].forEach((id) => {
    const sel = $(id);
    if (!sel) return;
    const prev = sel.value;
    sel.innerHTML = '';
    tvs.forEach((tv) => {
      const o = document.createElement('option');
      o.value = tv.address;
      o.textContent = tv.name || tv.address;
      sel.appendChild(o);
    });
    if (prev) sel.value = prev;
  });
}

$('#addTvForm').onsubmit = async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  await fetch('/api/tvs/add', form(Object.fromEntries(fd.entries())));
  e.target.reset();
  await loadTvs();
};

function renderGrid() {
  const grid = $('#tvGrid');
  grid.innerHTML = '';
  Object.keys(cardEls).forEach((k) => delete cardEls[k]);
  tvs.forEach((tv) => grid.appendChild(buildCard(tv)));
  pollAll();
}

// ---------- One TV card ----------
function buildCard(tv) {
  const card = document.createElement('section');
  card.className = 'card tvcard';
  card.innerHTML = `
    <div class="row">
      <label class="selwrap"><input type="checkbox" class="tvsel"><span class="tvname"></span></label>
      <span class="pill state">…</span>
    </div>
    <div class="muted small tvaddr"></div>
    <div class="big tvchannel">—</div>
    <div class="muted small tvprovider"></div>
    <div class="tvhealth"></div>
    <div class="controls">
      <button class="btn act-channels">Channels</button>
      <button class="btn act-reconnect">Reconnect</button>
      <button class="btn act-wake" style="display:none">Wake</button>
      <button class="btn act-refresh">Refresh</button>
      <button class="btn act-stop">Stop</button>
      <button class="btn act-login">Login</button>
      <button class="btn danger act-remove">Remove</button>
    </div>
    <div class="tvchannelbox" style="display:none">
      <input class="search tvsearch" placeholder="Search channels…">
      <div class="tvchannellist"></div>
    </div>
    <div class="tvconfigbox" style="display:none">
      <div class="grid">
        <label>Type
          <select class="cfg-type">
            <option value="xtream">Xtream Codes</option>
            <option value="m3u">M3U URL</option>
          </select>
        </label>
        <label>Name <input class="cfg-name" placeholder="Provider"></label>
        <label class="cfg-x">Server URL <input class="cfg-server" placeholder="http://host:port"></label>
        <label class="cfg-x">Username <input class="cfg-user" placeholder="username"></label>
        <label class="cfg-x">Password <input class="cfg-pass" type="password" placeholder="(unchanged)"></label>
        <label class="cfg-m" style="display:none">M3U URL <input class="cfg-m3u" placeholder="http://.../playlist.m3u"></label>
        <button class="btn primary act-save-login">Save &amp; load</button>
        <div class="muted small cfg-msg"></div>
      </div>
    </div>`;

  card.querySelector('.tvname').textContent = tv.name || tv.address;
  card.querySelector('.tvaddr').textContent = tv.address;

  card.querySelector('.act-remove').onclick = async () => {
    if (!confirm('Remove ' + (tv.name || tv.address) + ' from the dashboard?')) return;
    await fetch('/api/tvs/remove', form({ address: tv.address }));
    await loadTvs();
  };
  card.querySelector('.act-reconnect').onclick = () => fetch(base(tv) + '/api/retry', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {});
  // Wake: this page's own TV (which is awake) broadcasts the magic packet.
  if (tv.mac) {
    const wake = card.querySelector('.act-wake');
    wake.style.display = '';
    wake.onclick = async () => {
      wake.textContent = '…';
      try {
        const r = await fetch('/api/wol', form({ mac: tv.mac })).then((x) => x.json());
        wake.textContent = r.ok ? 'Wake sent ✓' : 'Failed';
      } catch (e) { wake.textContent = 'Failed'; }
      setTimeout(() => { wake.textContent = 'Wake'; pollTv(tv); }, 4000);
    };
  }
  card.querySelector('.act-stop').onclick = () => fetch(base(tv) + '/api/stop', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {});
  card.querySelector('.act-refresh').onclick = async (e) => {
    e.target.textContent = '…';
    await fetch(base(tv) + '/api/refresh', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {});
    delete channelCache[tv.address];
    e.target.textContent = 'Refresh';
  };
  card.querySelector('.act-channels').onclick = () => toggleChannels(tv, card);
  card.querySelector('.act-login').onclick = () => toggleConfig(tv, card);

  const typeSel = card.querySelector('.cfg-type');
  typeSel.onchange = () => {
    const x = typeSel.value === 'xtream';
    card.querySelectorAll('.cfg-x').forEach((el) => el.style.display = x ? '' : 'none');
    card.querySelector('.cfg-m').style.display = x ? 'none' : '';
  };
  card.querySelector('.act-save-login').onclick = () => saveLogin(tv, card);
  card.querySelector('.tvsearch').oninput = () => renderChannelList(tv, card);

  cardEls[tv.address] = card;
  return card;
}

// ---------- Status polling ----------
const health = {};   // address -> 'ok' | 'warn' | 'bad' | 'off'

function fmtDur(sec) {
  if (!sec || sec < 0) return '';
  if (sec < 60) return sec + 's';
  const m = Math.floor(sec / 60);
  if (m < 60) return m + 'm';
  return Math.floor(m / 60) + 'h ' + (m % 60) + 'm';
}

function updateSummary() {
  const vals = Object.values(health);
  if (!vals.length) { $('#fleetSummary').textContent = ''; return; }
  const ok = vals.filter((v) => v === 'ok').length;
  const warn = vals.filter((v) => v === 'warn').length;
  const bad = vals.filter((v) => v === 'bad').length;
  const off = vals.filter((v) => v === 'off').length;
  let t = ok + ' live';
  if (warn) t += ' · ' + warn + ' stopped/buffering';
  if (bad) t += ' · ' + bad + ' reconnecting';
  if (off) t += ' · ' + off + ' offline';
  $('#fleetSummary').textContent = t;
}

async function pollAll() { tvs.forEach(pollTv); }

async function pollTv(tv) {
  const card = cardEls[tv.address];
  if (!card) return;
  try {
    const s = await jget(base(tv) + '/api/status');
    card.classList.remove('offline');
    const pill = card.querySelector('.state');
    pill.textContent = labelFor(s.state);
    pill.className = 'pill state ' + s.state;
    card.querySelector('.tvchannel').textContent = s.channelName || '—';
    let pinfo = '';
    if (s.provider) {
      pinfo = s.provider.channelCount + ' channels';
      if (s.provider.expiresAt) pinfo += ' · exp ' + new Date(s.provider.expiresAt * 1000).toLocaleDateString();
      if (s.provider.lastError) pinfo += ' · ⚠ ' + s.provider.lastError;
    } else {
      pinfo = 'No provider — click Login';
    }
    if (s.retryCount) pinfo += ' · reconnect ' + s.retryCount;
    card.querySelector('.tvprovider').textContent = pinfo;

    // Simple status: colored dot + one word.
    const hp = card.querySelector('.tvhealth');
    card.classList.remove('alert');
    const setDot = (cls, word) => { hp.className = 'tvhealth ' + cls; hp.innerHTML = '<span class="dot"></span>' + word; };
    if (s.state === 'playing') {
      health[tv.address] = 'ok';
      setDot('hOk', 'Live');
    } else if (s.state === 'recovering' || s.state === 'error') {
      health[tv.address] = 'bad';
      setDot('hBad', 'Reconnecting');
      card.classList.add('alert');
    } else if (s.state === 'buffering') {
      health[tv.address] = 'warn';
      setDot('hWarn', 'Buffering');
    } else {
      // idle / stopped / no provider
      health[tv.address] = 'warn';
      setDot('hWarn', 'Stopped');
    }
    updateSummary();
  } catch (e) {
    card.classList.add('offline');
    card.classList.add('alert');
    const pill = card.querySelector('.state');
    pill.textContent = 'Offline';
    pill.className = 'pill state error';
    card.querySelector('.tvprovider').textContent = 'Not reachable at ' + tv.address;
    const hp = card.querySelector('.tvhealth');
    hp.className = 'tvhealth hOff';
    hp.innerHTML = '<span class="dot"></span>Offline';
    health[tv.address] = 'off';
    updateSummary();
  }
}

function labelFor(state) {
  return ({ playing: 'Playing', buffering: 'Buffering', recovering: 'Reconnecting',
    error: 'Error', idle: 'Idle', no_provider: 'No provider' })[state] || state;
}

// ---------- Channels per TV ----------
async function toggleChannels(tv, card) {
  const box = card.querySelector('.tvchannelbox');
  const show = box.style.display === 'none';
  box.style.display = show ? 'block' : 'none';
  if (show) { await ensureChannels(tv); renderChannelList(tv, card); }
}
async function ensureChannels(tv) {
  if (!channelCache[tv.address]) {
    try { channelCache[tv.address] = await jget(base(tv) + '/api/channels'); }
    catch (e) { channelCache[tv.address] = []; }
  }
  return channelCache[tv.address];
}
function renderChannelList(tv, card) {
  const q = (card.querySelector('.tvsearch').value || '').toLowerCase();
  const list = card.querySelector('.tvchannellist');
  list.innerHTML = '';
  (channelCache[tv.address] || [])
    .filter((c) => !q || c.name.toLowerCase().includes(q))
    .slice(0, 300)
    .forEach((c) => {
      const el = document.createElement('div');
      el.className = 'channel';
      el.innerHTML = '<div class="cname"></div>';
      el.querySelector('.cname').textContent = c.name;
      el.onclick = () => fetch(base(tv) + '/api/play', form({ channelId: c.id })).then(() => setTimeout(() => pollTv(tv), 400));
      list.appendChild(el);
    });
}

// ---------- Per-TV provider login ----------
async function toggleConfig(tv, card) {
  const box = card.querySelector('.tvconfigbox');
  const show = box.style.display === 'none';
  box.style.display = show ? 'block' : 'none';
  if (show) {
    try {
      const p = await jget(base(tv) + '/api/provider');
      if (p && p !== null) {
        card.querySelector('.cfg-type').value = p.type || 'xtream';
        card.querySelector('.cfg-name').value = p.name || '';
        card.querySelector('.cfg-server').value = p.server || '';
        card.querySelector('.cfg-user').value = p.username || '';
        card.querySelector('.cfg-m3u').value = p.m3uUrl || '';
        card.querySelector('.cfg-pass').placeholder = p.hasPassword ? '(unchanged)' : 'password';
      }
    } catch (e) {}
    card.querySelector('.cfg-type').onchange();
  }
}
async function saveLogin(tv, card) {
  const msg = card.querySelector('.cfg-msg');
  msg.textContent = 'Connecting…';
  const data = {
    type: card.querySelector('.cfg-type').value,
    name: card.querySelector('.cfg-name').value,
    server: card.querySelector('.cfg-server').value,
    username: card.querySelector('.cfg-user').value,
    password: card.querySelector('.cfg-pass').value,
    m3uUrl: card.querySelector('.cfg-m3u').value,
  };
  try {
    const res = await fetch(base(tv) + '/api/provider', form(data)).then((r) => r.json());
    msg.textContent = res.ok ? ('✓ ' + (res.message || 'Loaded')) : ('⚠ ' + (res.message || 'Failed'));
    card.querySelector('.cfg-pass').value = '';
    delete channelCache[tv.address];
    pollTv(tv);
  } catch (e) {
    msg.textContent = '⚠ Could not reach ' + tv.address;
  }
}

// ---------- Bulk actions ----------
function selectedTvs() {
  return tvs.filter((tv) => {
    const card = cardEls[tv.address];
    return card && card.querySelector('.tvsel').checked;
  });
}
async function bulkPlay(targets) {
  const name = ($('#bulkChannel').value || '').trim().toLowerCase();
  if (!name) { $('#bulkMsg').textContent = 'Type a channel name first.'; return; }
  let ok = 0, miss = 0, off = 0;
  for (const tv of targets) {
    try {
      const chans = await ensureChannels(tv);
      const match = chans.find((c) => c.name.toLowerCase().includes(name));
      if (match) { await fetch(base(tv) + '/api/play', form({ channelId: match.id })); ok++; }
      else miss++;
    } catch (e) { off++; }
  }
  $('#bulkMsg').textContent = `Played on ${ok} TV(s)` + (miss ? `, ${miss} had no match` : '') + (off ? `, ${off} offline` : '') + '.';
  setTimeout(pollAll, 500);
}
$('#bulkPlayAll').onclick = () => bulkPlay(tvs);

// ---------- Bulk browse: pick a channel by category, play everywhere ----------
let browseChannels = [];
let browseGroup = null;

async function openBrowse() {
  const src = tvs.find((t) => t.address === $('#browseSource').value);
  if (!src) { $('#bulkMsg').textContent = 'Add a TV first.'; return; }
  $('#bulkMsg').textContent = 'Loading channels from ' + (src.name || src.address) + '…';
  browseChannels = await ensureChannels(src);
  if (!browseChannels.length) { $('#bulkMsg').textContent = '⚠ Could not load channels from ' + src.address; return; }
  $('#bulkMsg').textContent = 'Click a channel to select it, then hit "Play on selected" or "Play on all".';
  $('#browseBox').style.display = 'block';
  $('#btnBrowse').textContent = 'Hide channels';
  renderBrowseGroups();
  renderBrowseList();
}

function closeBrowse() {
  $('#browseBox').style.display = 'none';
  $('#btnBrowse').textContent = 'Browse channels';
}

function renderBrowseGroups() {
  const groups = Array.from(new Set(browseChannels.map((c) => c.group))).sort();
  const box = $('#browseGroups');
  box.innerHTML = '';
  const mk = (label, val) => {
    const el = document.createElement('span');
    el.className = 'chip' + (browseGroup === val ? ' active' : '');
    el.textContent = label;
    el.onclick = () => { browseGroup = val; renderBrowseGroups(); renderBrowseList(); };
    return el;
  };
  box.appendChild(mk('All', null));
  groups.forEach((g) => box.appendChild(mk(g, g)));
}

function renderBrowseList() {
  const q = ($('#browseSearch').value || '').toLowerCase();
  const list = $('#browseList');
  list.innerHTML = '';
  browseChannels
    .filter((c) => !browseGroup || c.group === browseGroup)
    .filter((c) => !q || c.name.toLowerCase().includes(q))
    .slice(0, 500)
    .forEach((c) => {
      const el = document.createElement('div');
      el.className = 'channel';
      el.innerHTML =
        '<div class="ch-left">' +
        (c.logo ? '<img class="ch-logo" referrerpolicy="no-referrer" />' : '<div class="ch-logo"></div>') +
        '<div class="ch-meta"><div class="cname"></div><div class="cgroup"></div></div>' +
        '</div>';
      if (c.logo) { const im = el.querySelector('.ch-logo'); im.src = c.logo; im.onerror = () => { im.style.visibility = 'hidden'; }; }
      el.querySelector('.cname').textContent = c.name;
      el.querySelector('.cgroup').textContent = c.group;
      el.onclick = () => pickBrowseChannel(c, el);
      list.appendChild(el);
    });
}

// Clicking a channel only SELECTS it: it fills the bulk box and waits for the
// "Play on selected" / "Play on all" buttons — nothing plays on its own.
function pickBrowseChannel(c, el) {
  document.querySelectorAll('#browseList .channel.playing').forEach((x) => x.classList.remove('playing'));
  el.classList.add('playing');
  $('#bulkChannel').value = c.name;
  $('#bulkMsg').textContent = 'Selected "' + c.name + '" — now hit "Play on selected" or "Play on all".';
}

$('#btnBrowse').onclick = () => {
  ($('#browseBox').style.display === 'none') ? openBrowse() : closeBrowse();
};
$('#browseSearch').oninput = renderBrowseList;
$('#browseSource').onchange = () => { if ($('#browseBox').style.display !== 'none') { browseGroup = null; openBrowse(); } };
$('#bulkPlaySel').onclick = () => bulkPlay(selectedTvs());
$('#bulkStop').onclick = () => { tvs.forEach((tv) => fetch(base(tv) + '/api/stop', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {})); };
$('#bulkReconnect').onclick = () => { tvs.forEach((tv) => fetch(base(tv) + '/api/retry', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {})); };

// ---------- Category sync ----------
async function copyCategories(targets) {
  const srcAddr = $('#syncSource').value;
  const src = tvs.find((t) => t.address === srcAddr);
  if (!src) { $('#syncMsg').textContent = 'Pick a source TV first.'; return; }
  $('#syncMsg').textContent = 'Reading categories from ' + (src.name || src.address) + '…';
  let groups;
  try { groups = await jget(base(src) + '/api/groups'); }
  catch (e) { $('#syncMsg').textContent = '⚠ Could not read categories from ' + src.address; return; }
  if (!Array.isArray(groups) || !groups.length) {
    $('#syncMsg').textContent = 'That TV has no categories loaded yet — set up its provider first.';
    return;
  }
  const visible = groups.filter((g) => !g.hidden).map((g) => g.name);
  const others = targets.filter((t) => t.address !== srcAddr);
  if (!others.length) { $('#syncMsg').textContent = 'No other TVs to copy to.'; return; }
  $('#syncMsg').textContent = 'Copying…';
  let ok = 0, off = 0;
  for (const tv of others) {
    try {
      await fetch(base(tv) + '/api/groups/keep', form({ groups: visible.join('\n') }));
      delete channelCache[tv.address];
      ok++;
    } catch (e) { off++; }
  }
  const n = visible.length;
  $('#syncMsg').textContent = `Copied ${n} shown categor${n === 1 ? 'y' : 'ies'} to ${ok} TV(s)` +
    (off ? `, ${off} offline` : '') + '. Their screens update on the next channel reload.';
  setTimeout(pollAll, 500);
}
$('#syncAll').onclick = () => copyCategories(tvs);
$('#syncSel').onclick = () => copyCategories(selectedTvs());

// ---------- Fleet software & updates ----------
const fleetVer = {};   // address -> VersionDto | null

async function loadFleetVersions() {
  const box = $('#fleetVersions');
  box.innerHTML = '';
  await Promise.all(tvs.map(async (tv) => {
    try { fleetVer[tv.address] = await jget(base(tv) + '/api/version'); }
    catch (e) { fleetVer[tv.address] = null; }
  }));
  tvs.forEach((tv) => {
    const v = fleetVer[tv.address];
    const el = document.createElement('div');
    el.className = 'groupItem';
    const name = document.createElement('span');
    name.textContent = tv.name || tv.address;
    const right = document.createElement('span');
    right.style.display = 'flex';
    right.style.alignItems = 'center';
    right.style.gap = '10px';
    const st = document.createElement('span');
    st.className = 'muted small';
    if (!v) { st.textContent = 'offline'; st.style.color = '#ff8b84'; }
    else if (v.updateAvailable) { st.textContent = v.versionName + ' → ' + v.availableVersion + ' ready'; st.style.color = '#d29922'; }
    else { st.textContent = v.versionName + ' · up to date'; st.style.color = '#3fb950'; }
    right.appendChild(st);
    // Per-TV install click — update just this one when you choose to.
    if (v && v.updateAvailable) {
      const btn = document.createElement('button');
      btn.className = 'btn';
      btn.textContent = 'Update';
      btn.onclick = async () => {
        if (!confirm('Install the update on ' + (tv.name || tv.address) + '? The app restarts briefly and resumes its last channel.')) return;
        btn.textContent = '…';
        await fetch(base(tv) + '/api/update/install', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {});
        btn.textContent = 'Sent ✓';
        setTimeout(loadFleetVersions, 20000);
      };
      right.appendChild(btn);
    }
    el.appendChild(name); el.appendChild(right);
    box.appendChild(el);
  });
}

$('#fleetCheck').onclick = async () => {
  $('#fleetUpdMsg').textContent = 'Checking every TV…';
  await Promise.all(tvs.map((tv) =>
    fetch(base(tv) + '/api/update/check', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {})
  ));
  await loadFleetVersions();
  const ready = tvs.filter((tv) => fleetVer[tv.address] && fleetVer[tv.address].updateAvailable).length;
  $('#fleetUpdMsg').textContent = ready
    ? ready + ' TV(s) have an update downloaded and ready — hit "Update all ready TVs".'
    : 'All reachable TVs are up to date.';
};

$('#fleetInstall').onclick = async () => {
  const ready = tvs.filter((tv) => fleetVer[tv.address] && fleetVer[tv.address].updateAvailable);
  if (!ready.length) { $('#fleetUpdMsg').textContent = 'Nothing to install — run "Check all" first.'; return; }
  if (!confirm('Install the update on ' + ready.length + ' TV(s)? Each app restarts briefly and resumes its last channel.')) return;
  $('#fleetUpdMsg').textContent = 'Pushing update to ' + ready.length + ' TV(s)…';
  await Promise.all(ready.map((tv) =>
    fetch(base(tv) + '/api/update/install', { method: 'POST', headers: { 'X-Access-Key': ak() } }).catch(() => {})
  ));
  $('#fleetUpdMsg').textContent = 'Install sent to ' + ready.length + ' TV(s). If a TV shows an install prompt on screen, press OK on its remote once.';
  setTimeout(loadFleetVersions, 20000);
};

// ---------- Boot ----------
async function init() {
  await loadTvs();
  loadFleetVersions();
  setInterval(pollAll, 5000);
}
document.addEventListener('DOMContentLoaded', init);
