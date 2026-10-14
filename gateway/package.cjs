'use strict';
// Create an independent install bundle without changing the Android app's UI.
const fs = require('node:fs');
const path = require('node:path');
const out = path.resolve(process.argv[2] || 'build/office-dashboard');
const assets = path.resolve(__dirname,'../app/src/main/assets/webui');
fs.mkdirSync(path.join(out,'webui'), {recursive:true});
for (const file of ['server.cjs','setup.ps1','Setup.cmd','README.txt']) fs.copyFileSync(path.join(__dirname,file),path.join(out,file));
for (const file of ['style.css','logo.png','icon-192.png','icon-512.png','apple-touch-icon.png']) fs.copyFileSync(path.join(assets,file),path.join(out,'webui',file));
let js = fs.readFileSync(path.join(assets,'dashboard.js'),'utf8');
const base = "function base(tv) { return 'http://' + tv.address; }";
if (!js.includes(base)) throw new Error('Dashboard base function changed; review the gateway adapter');
js = js.replace(base,"function base(tv) { return '/tv/' + encodeURIComponent(tv.address); }")
 .replace("localStorage.getItem('ak') || sessionStorage.getItem('ak')", "sessionStorage.getItem('ak')")
 .replace("localStorage.setItem('ak', pw)", "sessionStorage.setItem('ak', pw)")
 .replace("await fetch('/api/tvs/add',", "await command('/api/tvs/add',")
 .replace("await fetch('/api/tvs/remove',", "await command('/api/tvs/remove',")
 .replace("document.addEventListener('DOMContentLoaded', init);", "document.addEventListener('DOMContentLoaded', () => init().catch(() => { $('#gatewayMsg').textContent = 'Enter the TV control password above, then retry. If it still fails, check the restaurant connection.'; }));");
fs.writeFileSync(path.join(out,'webui/dashboard.js'),js);
let html = fs.readFileSync(path.join(assets,'dashboard.html'),'utf8');
html = html.replace('<a class="dash-link" href="/">This TV →</a>', '<button class="btn" id="installApp" hidden>Install app</button>');
html = html.replace('<main>', `<main>
<section class="card"><h2>Office control center</h2>
<p class="muted small">Private access through your Marhaba Tailscale account. TV controls use the restaurant connection.</p>
<div class="bulkbar"><button class="btn" id="setKey">TV control password</button><button class="btn" id="importFleet">Import TV list from primary TV</button></div>
<p id="gatewayMsg" role="status" class="muted small">The fleet list is saved on the office computer after the first successful import.</p></section>
<div id="fleetSummary" class="muted small" style="margin:2px 0 10px"></div><div id="tvGrid" class="tvgrid"></div>
<p class="muted small" id="emptyHint" style="display:none">No TVs yet. Import your list from the primary TV.</p>`);
const old = '  <div id="fleetSummary" class="muted small" style="margin:2px 0 10px"></div>\n  <div id="tvGrid" class="tvgrid"></div>\n  <p class="muted small" id="emptyHint" style="display:none">No TVs yet. Add each TV above by its address.</p>';
if (!html.includes(old)) throw new Error('Dashboard layout changed; review adapter');
html = html.replace(old,'').replace('Add each TV by its Tailscale IP (or LAN IP). Port defaults to 8080.', 'Use an approved restaurant TV address with :8080. New addresses must first be approved in the office configuration.').replace('<script src="/dashboard.js"></script>', '<script src="/dashboard.js"></script>\n<script src="/gateway-ui.js"></script>');
fs.writeFileSync(path.join(out,'webui/dashboard.html'),html);
fs.copyFileSync(path.join(__dirname,'gateway-ui.js'),path.join(out,'webui/gateway-ui.js'));
fs.writeFileSync(path.join(out,'webui/manifest.webmanifest'),JSON.stringify({id:'/',name:'MMG TV Control Center',short_name:'MMG TV',start_url:'/dashboard',scope:'/',display:'standalone',background_color:'#0e1116',theme_color:'#0e1116',icons:[192,512].map(n=>({src:`/icon-${n}.png`,sizes:`${n}x${n}`,type:'image/png'}))},null,2));
// Network-only service worker: never cache passwords, provider data, commands,
// or an authenticated dashboard for another user of the same browser.
fs.writeFileSync(path.join(out,'webui/sw.js'),"self.addEventListener('install', () => self.skipWaiting());\nself.addEventListener('activate', event => event.waitUntil(self.clients.claim()));\nself.addEventListener('fetch', () => {});\n");
console.log(out);
