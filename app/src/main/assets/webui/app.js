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

// ---------- Categories management ----------
let allGroups = [];
let groupReload = null;

function scheduleChannelReload() {
  clearTimeout(groupReload);
  groupReload = setTimeout(loadChannels, 400);
}

function saveGroupOrder() {
  clearTimeout(saveGroupOrder._t);
  saveGroupOrder._t = setTimeout(() => {
    api('/api/groups/order', form({ groups: allGroups.map((x) => x.name).join('\n') }));
  }, 600);
}

function renderGroupManage() {
  const q = ($('#groupSearch').value || '').toLowerCase();
  const box = $('#groupManage');
  box.innerHTML = '';
  const shown = allGroups.filter((g) => g.name.toLowerCase().includes(q));
  const visibleCount = allGroups.filter((g) => !g.hidden).length;
  $('#groupCount').textContent = visibleCount + ' of ' + allGroups.length + ' shown';

  shown.forEach((g) => {
    const el = document.createElement('label');
    el.className = 'groupItem' + (g.hidden ? ' hidden' : '');
    const left = document.createElement('span');
    left.textContent = g.name;
    left.style.minWidth = '0';
    left.style.overflow = 'hidden';
    left.style.textOverflow = 'ellipsis';
    left.style.whiteSpace = 'nowrap';
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = !g.hidden;
    cb.style.width = 'auto';
    cb.style.flex = 'none';
    cb.onchange = async () => {
      g.hidden = !cb.checked;
      el.classList.toggle('hidden', g.hidden);
      $('#groupCount').textContent = allGroups.filter((x) => !x.hidden).length + ' of ' + allGroups.length + ' shown';
      await api(g.hidden ? '/api/groups/hide' : '/api/groups/unhide', form({ group: g.name }));
      renderArrange();
      scheduleChannelReload();
    };
    el.appendChild(left);
    el.appendChild(cb);
    box.appendChild(el);
  });
  if (shown.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'muted small';
    empty.textContent = 'No categories match \u201c' + q + '\u201d.';
    box.appendChild(empty);
  }
}

// ---------- Arrange (order applies to SHOWN categories only) ----------
// Drag the ≡ handle (mouse or touch) or use the ▲▼ arrows.
function commitArrangedOrder(newVisNames) {
  const byName = {};
  allGroups.forEach((g) => { byName[g.name] = g; });
  const vis = newVisNames.map((n) => byName[n]).filter(Boolean);
  allGroups = vis.concat(allGroups.filter((x) => x.hidden));
  saveGroupOrder();
  renderArrange();
  scheduleChannelReload();
}

function renderArrange() {
  const box = $('#arrangeList');
  if (!box) return;
  box.innerHTML = '';
  const vis = allGroups.filter((g) => !g.hidden);
  if (!vis.length) {
    const empty = document.createElement('div');
    empty.className = 'muted small';
    empty.textContent = 'No categories are shown yet — tick some above first.';
    box.appendChild(empty);
    return;
  }
  const move = (g, dir) => {
    const names = vis.map((x) => x.name);
    const i = names.indexOf(g.name);
    const j = i + dir;
    if (j < 0 || j >= names.length) return;
    names.splice(i, 1);
    names.splice(j, 0, g.name);
    commitArrangedOrder(names);
  };

  vis.forEach((g, i) => {
    const el = document.createElement('div');
    el.className = 'groupItem arrRow';
    el.dataset.name = g.name;

    const left = document.createElement('span');
    left.style.display = 'flex';
    left.style.alignItems = 'center';
    left.style.gap = '10px';
    left.style.minWidth = '0';

    const handle = document.createElement('span');
    handle.className = 'dragHandle';
    handle.textContent = '\u2261';
    handle.title = 'Drag to reorder';

    const name = document.createElement('span');
    name.textContent = (i + 1) + '.  ' + g.name;
    name.style.overflow = 'hidden';
    name.style.textOverflow = 'ellipsis';
    name.style.whiteSpace = 'nowrap';

    left.appendChild(handle);
    left.appendChild(name);

    const right = document.createElement('span');
    right.style.display = 'flex';
    right.style.gap = '6px';
    right.style.flex = 'none';
    const mk = (txt, dir, disabled) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'ordBtn';
      b.textContent = txt;
      b.disabled = disabled;
      if (disabled) b.style.opacity = '.3';
      b.onclick = () => move(g, dir);
      return b;
    };
    right.appendChild(mk('\u25b2', -1, i === 0));
    right.appendChild(mk('\u25bc', 1, i === vis.length - 1));

    el.appendChild(left);
    el.appendChild(right);
    box.appendChild(el);

    // --- Drag & drop: document-level pointer tracking (robust on mouse + touch) ---
    handle.addEventListener('pointerdown', (ev) => {
      ev.preventDefault();
      ev.stopPropagation();
      el.classList.add('dragging');

      const onMove = (mv) => {
        mv.preventDefault();
        // Auto-scroll the list when dragging near its edges
        const bRect = box.getBoundingClientRect();
        if (mv.clientY < bRect.top + 34) box.scrollTop -= 9;
        else if (mv.clientY > bRect.bottom - 34) box.scrollTop += 9;
        // Reposition the row under the pointer
        const rows = Array.from(box.querySelectorAll('.arrRow')).filter((r) => r !== el);
        let placed = false;
        for (const r of rows) {
          const rect = r.getBoundingClientRect();
          if (mv.clientY < rect.top + rect.height / 2) {
            if (el.nextSibling !== r) box.insertBefore(el, r);
            placed = true;
            break;
          }
        }
        if (!placed) box.appendChild(el);
      };
      const onUp = () => {
        document.removeEventListener('pointermove', onMove);
        document.removeEventListener('pointerup', onUp);
        document.removeEventListener('pointercancel', onUp);
        el.classList.remove('dragging');
        const names = Array.from(box.querySelectorAll('.arrRow')).map((r) => r.dataset.name);
        commitArrangedOrder(names);
      };
      document.addEventListener('pointermove', onMove, { passive: false });
      document.addEventListener('pointerup', onUp);
      document.addEventListener('pointercancel', onUp);
    });
  });
}

async function loadGroupManage() {
  allGroups = await api('/api/groups');
  if (!Array.isArray(allGroups)) allGroups = [];
  renderGroupManage();
  renderArrange();
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

  $('#groupSearch').oninput = renderGroupManage;
  $('#btnGroupsAll').onclick = async () => {
    await api('/api/groups/showall', { method: 'POST' });
    await loadGroupManage();
    scheduleChannelReload();
  };
  $('#btnOrderReset').onclick = async () => {
    if (!confirm('Reset the order to A\u2013Z?')) return;
    allGroups.sort((a, b) => a.name.localeCompare(b.name));
    saveGroupOrder();
    renderGroupManage();
    renderArrange();
    scheduleChannelReload();
  };
  $('#btnGroupsNone').onclick = async () => {
    if (!confirm('Hide every category? You can re-show any of them afterward.')) return;
    await api('/api/groups/hideall', { method: 'POST' });
    await loadGroupManage();
    scheduleChannelReload();
  };

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

async function loadCompat() {
  try {
    const c = await api('/api/compat');
    $('#compatToggle').checked = !!(c && c.on);
  } catch (e) {}
}

function wireCompat() {
  $('#compatToggle').onchange = async () => {
    const on = $('#compatToggle').checked;
    $('#compatMsg').textContent = 'Applying…';
    const r = await api('/api/compat', form({ on: on ? 'true' : 'false' }));
    $('#compatMsg').textContent = (r && r.message) || 'Saved.';
  };
}

async function init() {
  wire();
  wireUpdates();
  wireSecurity();
  wireCompat();
  await loadSecurity();
  await loadCompat();
  await loadProvider();
  await loadChannels();
  await loadGroupManage();
  await loadVersion();
  await pollStatus();
  setInterval(pollStatus, 3000);
}

document.addEventListener('DOMContentLoaded', init);
