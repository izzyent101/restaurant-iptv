const {test: nodeTest} = require('node:test');
const test = (name, fn) => nodeTest(name, {timeout: 15000}, fn);
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {createGateway} = require('./server.cjs');
const origin = 'https://office.example.ts.net';
const identity = {'tailscale-user-login':'owner@example.com'};
const config = {origin,source:'192.168.1.243:8080',allowedAddresses:['192.168.1.243:8080','192.168.1.240:8080'],allowedUsers:['owner@example.com']};
const list = [{name:'Primary',address:config.source},{name:'Stairs',address:config.allowedAddresses[1]}];
async function start(t, upstream, dataDir) {
  const dir = dataDir || fs.mkdtempSync(path.join(os.tmpdir(),'mmg-test-'));
  const server = createGateway(config,{dataDir:dir,assets:path.resolve('build/office-dashboard/webui'),fetch:upstream});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  t.after(async()=>{server.closeAllConnections(); await new Promise(resolve=>server.close(resolve)); if(!dataDir)fs.rmSync(dir,{recursive:true,force:true});});
  const base = `http://127.0.0.1:${server.address().port}`;
  return {dir,call:(route,opts={})=>fetch(base+route,{...opts,headers:{...identity,...opts.headers}})};
}
const reply = (value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});

test('rejects missing and unauthorized identity before serving the dashboard',async t=>{
 const h=await start(t,()=>{throw new Error('must not call upstream');});
 assert.equal((await h.call('/dashboard',{headers:{'tailscale-user-login':''}})).status,403);
 assert.equal((await h.call('/dashboard',{headers:{'tailscale-user-login':'other@example.com'}})).status,403);
 assert.equal((await h.call('/dashboard')).status,200);
});
test('requires same-origin POST and rejects cross-site reads',async t=>{
 const h=await start(t,()=>{throw new Error('must not call upstream');});
 assert.equal((await h.call('/api/tvs/add',{method:'POST'})).status,403);
 assert.equal((await h.call('/api/tvs',{headers:{origin:'https://evil.example'}})).status,403);
 assert.equal((await h.call('/api/tvs/add',{method:'POST',headers:{origin:'https://evil.example'}})).status,403);
});
test('imports fleet with access key but saves no credentials, then serves saved list independently',async t=>{
 let count=0;
 const h=await start(t,async(url,opts)=>{count++;assert.equal(url,'http://192.168.1.243:8080/api/tvs');assert.equal(opts.headers['X-Access-Key'],'secret');assert.equal(opts.headers.Cookie,undefined);return reply(list);});
 assert.equal((await h.call('/api/tvs',{headers:{'x-access-key':'secret',cookie:'mak=private'}})).status,200);
 const r=await h.call('/api/tvs');assert.deepEqual(await r.json(),list.map(t=>({...t,mac:''})));
 assert.equal(count,1);
 assert.ok(!fs.readFileSync(path.join(h.dir,'fleet.json'),'utf8').includes('secret'));
});
test('propagates login failures instead of importing an error as a TV list',async t=>{
 const h=await start(t,async()=>reply({ok:false,message:'unauthorized'},401));
 assert.equal((await h.call('/api/tvs')).status,401);
 assert.ok(!fs.existsSync(path.join(h.dir,'fleet.json')));
});
test('saved fleet survives server restart and source downtime',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'mmg-persist-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
 const first=await start(t,async()=>reply(list),dir);await first.call('/api/tvs');
 const second=await start(t,async()=>{throw new Error('source offline');},dir);
 assert.equal((await second.call('/api/tvs')).status,200);
});
test('rejects arbitrary proxy destinations and unapproved imported addresses',async t=>{
 const h=await start(t,async()=>reply(list));await h.call('/api/tvs');
 assert.equal((await h.call('/tv/127.0.0.1%3A8080/api/status')).status,403);
 assert.equal((await h.call('/tv/example.com%3A80/api/status')).status,403);
 const bad=await start(t,async()=>reply([{name:'Bad',address:'127.0.0.1:8080'}]));
 assert.equal((await bad.call('/api/tvs')).status,400);
});
test('proxies approved commands and preserves API rejection and HTTP status',async t=>{
 let actual;
 const h=await start(t,async(url,opts)=>{
  if(url.endsWith('/api/tvs')) return reply(list);
  actual={url,opts};return reply({ok:false,message:'No update ready'},409);
 });await h.call('/api/tvs');
 const r=await h.call('/tv/192.168.1.240%3A8080/api/update/install',{method:'POST',headers:{origin,'x-access-key':'secret'}});
 assert.equal(r.status,409);assert.equal((await r.json()).ok,false);
 assert.equal(actual.url,'http://192.168.1.240:8080/api/update/install');
 assert.equal(actual.opts.headers['X-Access-Key'],'secret');assert.equal(actual.opts.redirect,'error');
 assert.equal(actual.opts.headers['tailscale-user-login'],undefined);
});
test('unknown routes and traversal never expose configuration or files',async t=>{
 const h=await start(t,async()=>reply(list));await h.call('/api/tvs');
 assert.equal((await h.call('/config.json')).status,404);
 assert.equal((await h.call('/data/fleet.json')).status,404);
 assert.equal((await h.call('/tv/192.168.1.240%3A8080/api/security/status')).status,403);
 assert.equal((await h.call('/api/tvs?target=http://example.com')).status,400);
});
test('failed or empty reimport preserves the existing fleet',async t=>{
 let n=0; const h=await start(t,async()=>reply(n++===0?list:[]));await h.call('/api/tvs');
 assert.equal((await h.call('/api/import-tvs',{method:'POST',headers:{origin}})).status,502);
 assert.equal((await(await h.call('/api/tvs')).json()).length,2);
});
test('removing a TV changes only the office list and stops proxying to it',async t=>{
 let n=0;const h=await start(t,async()=>{n++;return reply(list);});await h.call('/api/tvs');
 const r=await h.call('/api/tvs/remove',{method:'POST',headers:{origin},body:new URLSearchParams({address:'192.168.1.240:8080'})});
 assert.equal(r.status,200);assert.equal(n,1);
 assert.equal((await h.call('/tv/192.168.1.240%3A8080/api/status')).status,403);
});
test('packaged browser uses same-origin controls, session-only password, install manifest and no API cache',async t=>{
 const h=await start(t,async()=>reply(list));
 const js=await(await h.call('/dashboard.js')).text();
 assert.ok(js.includes("return '/tv/' + encodeURIComponent(tv.address)"));
 assert.ok(!js.includes("localStorage.getItem('ak')"));
 const manifest=await(await h.call('/manifest.webmanifest')).json();
 assert.equal(manifest.start_url,'/dashboard');assert.equal(manifest.display,'standalone');
 const html=await(await h.call('/dashboard')).text();
 assert.equal((html.match(/id="tvGrid"/g)||[]).length,1);
 assert.ok(html.indexOf('id="tvGrid"')<html.indexOf('Bulk control'));
 assert.equal((await h.call('/dashboard')).headers.get('cache-control'),'no-store');
});
