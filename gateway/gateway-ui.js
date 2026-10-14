'use strict';
let installPrompt;
window.addEventListener('beforeinstallprompt', e => {
  e.preventDefault(); installPrompt = e; document.querySelector('#installApp').hidden = false;
});
document.querySelector('#installApp').onclick = async () => {
  if (!installPrompt) return;
  await installPrompt.prompt(); installPrompt = null; document.querySelector('#installApp').hidden = true;
};
document.querySelector('#setKey').onclick = () => {
  const key = prompt('Enter the TV control password. It is kept only for this browser session.');
  if (key === null) return;
  sessionStorage.setItem('ak', key); location.reload();
};
document.querySelector('#importFleet').onclick = async () => {
  if (!confirm('Replace the office TV list with the list from the primary TV?')) return;
  const msg = document.querySelector('#gatewayMsg');
  try {
    await command('/api/import-tvs',{method:'POST'}); await loadTvs(); await loadFleetVersions();
    msg.textContent = 'TV list imported and saved on the office computer.';
  } catch (e) { msg.textContent = 'Import failed. The saved list was preserved. Check the TV password and connection.'; }
};
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
window.addEventListener('offline', () => { document.querySelector('#gatewayMsg').textContent = 'Connection lost. TV statuses may be out of date; controls need a connection.'; });

window.addEventListener('unhandledrejection', event => {
  document.querySelector('#gatewayMsg').textContent = 'The action failed. Check the connection, TV password, and approved TV address, then retry.';
  event.preventDefault();
});
