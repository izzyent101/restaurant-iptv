// Minimal service worker: makes the launcher installable and keeps its shell
// cached so it opens instantly even with no internet.
const CACHE = 'mmg-launcher-v1';
const SHELL = ['./', './index.html', './manifest.webmanifest', './logo.png', './icon-192.png', './icon-512.png'];
self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', (e) => {
  e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request)));
});
