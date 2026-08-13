'use strict';

const $ = (s) => document.querySelector(s);
let allChannels = [];
let activeGroup = null;
let favoritesOnly = false;
let currentChannelId = null;

async function api(path, opts) {
  opts = opts || {};
  opts.headers = Object.assign({}, opts.headers || {}, { 'X-Access-Key': sessionStorage.getItem('ak') || '' });
  let res = await fetch(path, opts);
  if (res.status === 401) {
    const pw = window.prompt('This TV is password-protected. Enter the control password:');
    if (pw != null) {
      sessionStorage.setItem('ak', pw);
      opts.headers['X-Access-Key'] = pw;
      res = await fetch(path, opts);
    }
  }
  const text = await res.text();
  try { return JSON.parse(text); } catch (e) { return text; }
}

function form(data) {
  const body = new URLSearchParams();
  Object.keys(data).forEach((k) => { if (data[k] != null) body.append(k, data[k]); });
  return { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body };
}

// ---------- Status polling ----------
async function pollStatus() {
  try {
    const s = await api('/api/status');
    const pill = $('#statusPill');
    pill.textContent = labelFor(s.state);
    pill.className = 'pill ' + s.state;

    $('#nowChannel').textContent = s.channelName || '—';
    currentChannelId = s.channelId;

    let state = labelFor(s.state);
    if (s.secondsInState) state += ' · ' + fmtDuration(s.secondsInState);
    $('#nowState').textContent = state;

    let diag = '';
    if (s.retryCount) diag += 'reconnect attempts: ' + s.retryCount + '  ';
    if (s.recreateCount) diag += 'player rebuilds: ' + s.recreateCount + '  ';
    if (s.lastError) diag += 'last: ' + s.lastError;
    $('#diag').textContent = diag;

    if (s.provider) renderProviderStatus(s.provider);
    markPlaying();
  } catch (e) { /* server may briefly be unavailable during recovery */ }
}

function labelFor(state) {
  switch (state) {
    case 'playing': return 'Playing';
    case 'buffering': return 'Buffering';
    case 'recovering': return 'Reconnecting';
    case 'error': return 'Error';
    case 'idle': return 'Idle';
    case 'no_provider': return 'No provider';
    default: return state;
  }
}

function fmtDuration(sec) {
  if (sec < 60) return sec + 's';
  const m = Math.floor(sec / 60), s = sec % 60;
  if (m < 60) return m + 'm ' + s + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + (m % 60) + 'm';
}

function renderProviderStatus(p) {
  const msg = $('#providerMsg');
  let t = p.channelCount + ' channels';
  if (p.expiresAt) t += ' · expires ' + new Date(p.expiresAt * 1000).toLocaleDateString();
  if (p.maxConnections) t += ' · max ' + p.maxConnections + ' connections';
  if (p.lastError) t += ' · ⚠ ' + p.lastError;
  msg.textContent = t;
}

// ---------- Channels ----------
async function loadChannels() {
  allChannels = await api('/api/channels');
  renderGroups();
  renderChannels();
}

function renderGroups() {
  const groups = Array.from(new Set(allChannels.map((c) => c.group))).sort();
  const box = $('#groupFilter');
  box.innerHTML = '';
  const mkChip = (label, val) => {
    const el = document.createElement('span');
    el.className = 'chip' + ((!favoritesOnly && activeGroup === val) ? ' active' : '');
    el.textContent = label;
    el.onclick = () => { favoritesOnly = false; activeGroup = val; renderGroups(); renderChannels(); };
    return el;
  };
  // Favorites chip first
  const fav = document.createElement('span');
  fav.className = 'chip' + (favoritesOnly ? ' active' : '');
  fav.textContent = '★ Favorites';
  fav.onclick = () => { favoritesOnly = true; renderGroups(); renderChannels(); };
  box.appendChild(fav);
  box.appendChild(mkChip('All', null));
  groups.forEach((g) => box.appendChild(mkChip(g, g)));
}

function renderChannels() {
  const q = ($('#search').value || '').toLowerCase();
  const list = $('#channelList');
  list.innerHTML = '';
  allChannels
    .filter((c) => (favoritesOnly ? c.favorite : (!activeGroup || c.group === activeGroup)))
    .filter((c) => (!q || c.name.toLowerCase().includes(q)))
    .slice(0, 800)
    .forEach((c) => {
      const el = document.createElement('div');
      el.className = 'channel' + (c.id === currentChannelId ? ' playing' : '');
      el.dataset.id = c.id;
      el.innerHTML =
        '<div class="ch-left">' +
        (c.logo ? '<img class="ch-logo" referrerpolicy="no-referrer" />' : '<div class="ch-logo ch-logo-ph"></div>') +
        '<div class="ch-meta"><div class="cname"></div><div class="cgroup"></div><div class="cepg muted small"></div><div class="cbar"><span></span></div></div>' +
        '</div>' +
        '<button class="star">' + (c.favorite ? '★' : '☆') + '</button>';
      if (c.logo) { const im = el.querySelector('.ch-logo'); im.src = c.logo; im.onerror = () => { im.style.visibility = 'hidden'; }; }
      el.querySelector('.cname').textContent = c.name;
      el.querySelector('.cgroup').textContent = c.group;
      const epg = el.querySelector('.cepg');
      if (c.epgNow) epg.textContent = 'Now: ' + c.epgNow + (c.epgNext ? '  ·  Next: ' + c.epgNext : '');
      const bar = el.querySelector('.cbar');
      if (c.epgProgress >= 0) { bar.style.display = 'block'; bar.firstElementChild.style.width = c.epgProgress + '%'; }
      else bar.style.display = 'none';
      const star = el.querySelector('.star');
      if (c.favorite) star.classList.add('on');
      star.onclick = (e) => { e.stopPropagation(); toggleFavorite(c, star); };
      el.onclick = () => play(c.id);
      list.appendChild(el);
    });
}

async function toggleFavorite(c, starEl) {
  const res = await api('/api/favorite', form({ channelId: c.id }));
  c.favorite = (res && res.message === 'added');
  starEl.textContent = c.favorite ? '★' : '☆';
  starEl.classList.toggle('on', c.favorite);
  if (favoritesOnly && !c.favorite) renderChannels();
}

function markPlaying() {
  document.querySelectorAll('.channel').forEach((el) => {
    el.classList.toggle('playing', Number(el.dataset.id) === currentChannelId);
  });
}

async function play(id) {
  currentChannelId = id;
  markPlaying();
  await api('/api/play', form({ channelId: id }));
  setTimeout(pollStatus, 400);
}

// ---------- Groups management ----------
async function loadGroupManage() {
  const groups = await api('/api/groups');
  const box = $('#groupManage');
  box.innerHTML = '';
  groups.forEach((g) => {
    const el = document.createElement('div');
    el.className = 'groupItem' + (g.hidden ? ' hidden' : '');
    const name = document.createElement('span');
    name.textContent = g.name;
    const btn = document.createElement('button');
    btn.className = 'btn';
    btn.textContent = g.hidden ? 'Show' : 'Hide';
    btn.onclick = async () => {
      await api(g.hidden ? '/api/groups/unhide' : '/api/groups/hide', form({ group: g.name }));
      await loadGroupManage();
      await loadChannels();
    };
    el.appendChild(name); el.appendChild(btn);
    box.appendChild(el);
  });
}

// ---------- Provider form ----------
async function loadProvider() {
  const p = await api('/api/provider');
  if (!p || p === 'null') return;
  $('#pType').value = p.type || 'xtream';
  $('#pName').value = p.name || '';
  $('#pServer').value = p.server || '';
  $('#pUser').value = p.username || '';
  $('#pM3u').value = p.m3uUrl || '';
  $('#pEpg').value = p.epgUrl || '';
  $('#pPass').placeholder = p.hasPassword ? '(unchanged)' : 'password';
  toggleProviderFields();
}

function toggleProviderFields() {
  const t = $('#pType').value;
  document.querySelector('.xtream-fields').style.display = (t === 'xtream') ? '' : 'none';
  document.querySelector('.m3u-fields').style.display = (t === 'm3u') ? '' : 'none';
}

function wire() {
  $('#btnRetry').onclick = () => api('/api/retry', { method: 'POST' }).then(() => setTimeout(pollStatus, 400));
  $('#btnStop').onclick = () => api('/api/stop', { method: 'POST' }).then(() => setTimeout(pollStatus, 400));
  $('#btnRefresh').onclick = async () => {
    $('#btnRefresh').textContent = 'Refreshing…';
    await api('/api/refresh', { method: 'POST' });
    await loadChannels(); await loadGroupManage();
    $('#btnRefresh').textContent = 'Refresh channels';
  };
  $('#search').oninput = renderChannels;
  $('#pType').onchange = toggleProviderFields;

  $('#btnRefreshEpg').onclick = async () => {
    const b = $('#btnRefreshEpg'); b.textContent = 'Loading guide…';
    const r = await api('/api/epg/refresh', { method: 'POST' });
    await loadChannels();
    b.textContent = 'Refresh guide';
    if (r && r.message) $('#providerMsg').textContent = r.message;
  };

  $('#providerForm').onsubmit = async (e) => {
    e.preventDefault();
    $('#providerMsg').textContent = 'Connecting…';
    const fd = new FormData(e.target);
    const data = Object.fromEntries(fd.entries());
    const res = await api('/api/provider', form(data));
    $('#providerMsg').textContent = res.ok ? ('✓ ' + (res.message || 'Loaded')) : ('⚠ ' + (res.message || 'Failed'));
    $('#pPass').value = '';
    await loadProvider(); await loadChannels(); await loadGroupManage();
  };

  $('#btnDeleteProvider').onclick = async () => {
    if (!confirm('Remove this provider and its channels from this TV?')) return;
    await api('/api/provider/delete', { method: 'POST' });
    location.reload();
  };
}

// ---------- Updates ----------
async function loadVersion() {
  try {
    const v = await api('/api/version');
    $('#versionLine').textContent = 'Version ' + v.versionName + ' (build ' + v.versionCode + ')' +
      (v.manifestUrl ? ' · self-host' : ' · GitHub');
    $('#updManifest').value = v.manifestUrl || '';
    $('#btnInstallUpdate').style.display = v.updateAvailable ? '' : 'none';
    if (v.updateAvailable) $('#btnInstallUpdate').textContent = 'Install ' + v.availableVersion + ' now';
    if (v.updateMessage) $('#updateMsg').textContent = v.updateMessage;
  } catch (e) {}
}

function wireUpdates() {
  const f = $('#updateForm');
  if (f) f.onsubmit = async (e) => {
    e.preventDefault();
    $('#updateMsg').textContent = 'Saving…';
    await api('/api/update/config', form({ manifestUrl: $('#updManifest').value }));
    await loadVersion();
    $('#updateMsg').textContent = 'Saved.';
  };
  $('#btnCheckUpdate').onclick = async () => {
    $('#updateMsg').textContent = 'Checking…';
    const r = await api('/api/update/check', { method: 'POST' });
    $('#updateMsg').textContent = (r && r.message) || 'Checked.';
    await loadVersion();
  };
  $('#btnInstallUpdate').onclick = async () => {
    if (!confirm('Install the update now? The app will briefly restart and resume the last channel.')) return;
    $('#updateMsg').textContent = 'Installing…';
    const r = await api('/api/update/install', { method: 'POST' });
    $('#updateMsg').textContent = (r && r.message) || 'Installing…';
  };
}

// ---------- Security ----------
async function loadSecurity() {
  try {
    const s = await fetch('/api/security/status').then((r) => r.json());
    $('#securityLine').textContent = s.protected
      ? 'Protected — a password is required to control this TV.'
      : 'No password set — anyone on the network can control this TV.';
  } catch (e) {}
}
function wireSecurity() {
  $('#securityForm').onsubmit = async (e) => {
    e.preventDefault();
    const pw = $('#secPass').value;
    $('#securityMsg').textContent = 'Saving…';
    const res = await api('/api/security/password', form({ password: pw }));
    if (pw) sessionStorage.setItem('ak', pw);
    $('#secPass').value = '';
    $('#securityMsg').textContent = (res && res.message) || 'Saved.';
    await loadSecurity();
  };
  $('#btnClearPass').onclick = async () => {
    if (!confirm('Remove the password? Anyone on the network will then be able to control this TV.')) return;
    const res = await api('/api/security/password', form({ password: '' }));
    sessionStorage.removeItem('ak');
    $('#securityMsg').textContent = (res && res.message) || 'Removed.';
    await loadSecurity();
  };
}

async function init() {
  wire();
  wireUpdates();
  wireSecurity();
  await loadSecurity();
  await loadProvider();
  await loadChannels();
  await loadGroupManage();
  await loadVersion();
  await pollStatus();
  setInterval(pollStatus, 3000);
}

document.addEventListener('DOMContentLoaded', init);
