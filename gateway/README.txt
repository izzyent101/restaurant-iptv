MMG TV Office Dashboard

Setup on the designated Windows office computer
1. Extract the whole ZIP first.
2. Connect Tailscale to the Marhaba account.
3. Double-click Setup.cmd and approve the Windows administrator prompt.
4. If Tailscale asks to enable HTTPS, follow its approval link.
5. Open the HTTPS address printed by setup in Chrome, with Tailscale connected.
6. Enter your existing TV control password when prompted. The TV list imports
   from the primary TV once and is then saved on the office computer.
7. Choose Install app, or use Chrome's installation menu.

Operation
- Office computer must stay on, awake, and connected to Tailscale.
- The restaurant's existing Tailscale gateway must also be running.
- Enable Tailscale's Run unattended setting on the office computer.
- TV playback remains independent of the office dashboard.
- Only the Tailscale user that runs setup is allowed to access this dashboard.
- The password is held in browser session storage, not saved on the server.
- The office TV list is independent after import. Importing again replaces it.
- Approved TV addresses are configured in config.json. New addresses require
  updating that allowlist before adding them in the dashboard.
- HTTPS protects browser-to-office traffic. Tailscale protects office-to-gateway
  traffic. The last hop from restaurant gateway to TVs remains local HTTP.
- No Internet port forwarding or public Tailscale Funnel is used.
- This app does not cache API responses for offline use. Controls require a
  connection; do not interpret stale cards as live status when disconnected.

Files
Installation: C:\ProgramData\MMG-TV-Office
Fleet backup: data\fleet.json (contains names, IP addresses and optional MACs)
Startup task: MMG TV Office Dashboard (runs as Local Service at Windows startup)
Runtime: official Node.js 22 portable runtime; checksum checked during setup.

Uninstall (Administrator PowerShell)
Stop-ScheduledTask -TaskName 'MMG TV Office Dashboard'
Unregister-ScheduledTask -TaskName 'MMG TV Office Dashboard' -Confirm:$false
& "$env:ProgramFiles\Tailscale\tailscale.exe" serve --https=443 off

Keep the installation folder to retain your saved TV list, or remove it after
backing up data\fleet.json. Turning off HTTPS above affects this port; only run
that command while it is still assigned to MMG TV.
