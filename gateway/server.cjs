'use strict';
// Private HTTPS is terminated by Tailscale Serve. Never bind this to a LAN interface:
// identity headers are trusted only because Serve connects through localhost.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

function createGateway(config, options = {}) {
  const assets = options.assets || path.join(__dirname, 'webui');
  const dataDir = options.dataDir || path.join(__dirname, 'data');
  const upstreamFetch = options.fetch || fetch;
  const allowed = new Set(config.allowedAddresses);
  if (!allowed.has(config.source)) throw new Error('Source must be an allowed TV');
  if (!config.origin.startsWith('https://') || !config.allowedUsers.length) throw new Error('HTTPS origin and allowed users required');
  fs.mkdirSync(dataDir, { recursive: true });
  const fleetFile = path.join(dataDir, 'fleet.json');
  const normalize = tv => {
    const address = String(tv.address || '').trim();
    if (!allowed.has(address)) throw Object.assign(new Error('TV address is not approved'), {status: 400});
    return {name: String(tv.name || address).slice(0, 100), address, mac: String(tv.mac || '').slice(0, 32)};
  };
  let fleet = fs.existsSync(fleetFile) ? JSON.parse(fs.readFileSync(fleetFile, 'utf8')).map(normalize) : null;
  function save(next) {
    const seen = new Set();
    next = next.map(normalize).filter(tv => !seen.has(tv.address) && seen.add(tv.address));
    fs.writeFileSync(fleetFile + '.tmp', JSON.stringify(next, null, 2));
    fs.renameSync(fleetFile + '.tmp', fleetFile);
    fleet = next;
  }
  async function boundedBody(stream, limit) {
    let size = 0; const chunks = [];
    for await (const chunk of stream) {
      size += chunk.length;
      if (size > limit) throw Object.assign(new Error('Response or request too large'), {status: 413});
      chunks.push(Buffer.from(chunk));
    }
    return Buffer.concat(chunks);
  }
  async function upstream(address, route, req, body) {
    const headers = {'Accept': 'application/json'};
    if (req.headers['x-access-key']) headers['X-Access-Key'] = req.headers['x-access-key'];
    if (body) headers['Content-Type'] = 'application/x-www-form-urlencoded';
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), route === '/api/update/check' ? 290000 : 60000);
    try {
      const r = await upstreamFetch('http://' + address + route, {
        method: req.method, headers, body, redirect: 'error', signal: controller.signal
      });
      const bytes = await boundedBody(r.body, 32 * 1024 * 1024);
      const value = JSON.parse(bytes.toString('utf8'));
      return {status: r.status, value};
    } finally { clearTimeout(timeout); }
  }
  const getRoutes = new Set(['/api/status','/api/version','/api/channels','/api/provider','/api/groups']);
  const postRoutes = new Set(['/api/play','/api/stop','/api/retry','/api/refresh','/api/provider','/api/groups/keep','/api/update/check','/api/update/install']);
  const staticFiles = {
    '/dashboard': ['dashboard.html','text/html'],
    '/dashboard.js': ['dashboard.js','text/javascript'],
    '/gateway-ui.js': ['gateway-ui.js','text/javascript'],
    '/style.css': ['style.css','text/css'],
    '/manifest.webmanifest': ['manifest.webmanifest','application/manifest+json'],
    '/sw.js': ['sw.js','text/javascript'],
    '/icon-192.png': ['icon-192.png','image/png'],
    '/icon-512.png': ['icon-512.png','image/png'],
    '/apple-touch-icon.png': ['apple-touch-icon.png','image/png'],
    '/logo.png': ['logo.png','image/png']
  };
  const server = http.createServer(async (req, res) => {
    res.setHeader('Cache-Control','no-store');
    res.setHeader('X-MMG-Gateway','1');
    res.setHeader('X-Content-Type-Options','nosniff');
    res.setHeader('Referrer-Policy','no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'");
    const json = (status, value) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(value)); };
    try {
      if (!config.allowedUsers.includes(req.headers['tailscale-user-login'])) return json(403,{ok:false,message:'Connect using the authorized Marhaba Tailscale account.'});
      if (req.headers.origin && req.headers.origin !== config.origin) return json(403,{ok:false,message:'Origin not allowed'});
      if (req.method !== 'GET' && (req.method !== 'POST' || req.headers.origin !== config.origin)) return json(403,{ok:false,message:'Same-origin request required'});
      const url = new URL(req.url, config.origin);
      if (url.search) return json(400,{ok:false,message:'Query parameters are not supported'});
      if (req.method === 'GET' && url.pathname === '/') { res.writeHead(302,{Location:'/dashboard'}); return res.end(); }
      if (req.method === 'GET' && staticFiles[url.pathname]) {
        const [file, mime] = staticFiles[url.pathname];
        res.writeHead(200, {'Content-Type':mime}); return res.end(fs.readFileSync(path.join(assets,file)));
      }
      const body = req.method === 'POST' ? await boundedBody(req, 1024 * 1024) : undefined;
      if ((req.method === 'GET' && url.pathname === '/api/tvs') || (req.method === 'POST' && url.pathname === '/api/import-tvs')) {
        if (fleet === null || req.method === 'POST') {
          const result = await upstream(config.source,'/api/tvs',{headers:req.headers, method:'GET'}, undefined);
          if (result.status !== 200) return json(result.status,result.value);
          if (!Array.isArray(result.value) || result.value.length === 0) return json(502,{ok:false,message:'Source TV returned no fleet. Existing list was preserved.'});
          save(result.value);
        }
        return json(200,req.method === 'GET' ? fleet : {ok:true,message:'TV list imported and saved on the office computer.'});
      }
      if (req.method === 'POST' && ['/api/tvs/add','/api/tvs/remove'].includes(url.pathname)) {
        if (fleet === null) return json(409,{ok:false,message:'Import the existing TV list first.'});
        const p = Object.fromEntries(new URLSearchParams(body.toString()));
        if (url.pathname.endsWith('/add')) {
          const tv = normalize(p); save([...fleet.filter(t => t.address !== tv.address), tv]);
        } else { save(fleet.filter(t => t.address !== p.address)); }
        return json(200,{ok:true});
      }
      let address, route;
      if (req.method === 'POST' && url.pathname === '/api/wol') { address = config.source; route = '/api/wol'; }
      else {
        const match = url.pathname.match(/^\/tv\/([^/]+)(\/api\/[a-z/]+)$/);
        if (!match) return json(404,{ok:false,message:'Not found'});
        address = decodeURIComponent(match[1]); route = match[2];
        if (!allowed.has(address) || !fleet?.some(tv => tv.address === address)) return json(403,{ok:false,message:'TV is not in the approved fleet'});
        if (!(req.method === 'GET' ? getRoutes : postRoutes).has(route)) return json(403,{ok:false,message:'Command not allowed'});
      }
      const result = await upstream(address,route,req,body);
      // HTTP provider logos cannot be loaded securely from an HTTPS dashboard.
      if (route === '/api/channels' && Array.isArray(result.value)) {
        result.value.forEach(c => { if (typeof c.logo === 'string' && !c.logo.startsWith('https://')) c.logo = ''; });
      }
      return json(result.status,result.value);
    } catch (e) {
      // Do not log URLs, request bodies, access keys, or provider credentials.
      return json(e.status || 502,{ok:false,message:e.status ? e.message : 'TV connection failed. Check Tailscale and the TV, then retry.'});
    }
  });
  server.requestTimeout = 310000;
  return server;
}
module.exports = {createGateway};
if (require.main === module) {
  const config = JSON.parse(fs.readFileSync(path.join(__dirname,'config.json'),'utf8'));
  createGateway(config).listen(8787,'127.0.0.1',() => console.log('MMG dashboard ready on localhost:8787. Use the Tailscale HTTPS address.'));
}
