#Requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$installDir = Join-Path $env:ProgramData 'MMG-TV-Office'
$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (!(Test-Path $tailscale)) { throw 'Install Tailscale for Windows and sign in to the Marhaba account first.' }
$state = (& $tailscale status --json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0 -or $state.BackendState -ne 'Running') { throw 'Connect Tailscale before running setup.' }
$site = Get-Content (Join-Path $PSScriptRoot 'site.json') -Raw | ConvertFrom-Json
$dns = $state.Self.DNSName.TrimEnd('.')
if ($dns -ne $site.expectedHostname) { throw "This package is for $($site.expectedHostname). Current computer: $dns" }
$login = $state.User.PSObject.Properties[[string]$state.Self.UserID].Value.LoginName
if (!$login) { throw 'Cannot determine the Tailscale account.' }
$origin = "https://$dns"
$serve = (& $tailscale serve status --json | ConvertFrom-Json)
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect existing Tailscale Serve settings.' }
# Do not overwrite another service on this computer.
if ($serve.Web) {
  foreach ($entry in $serve.Web.PSObject.Properties) {
    if ($entry.Name -eq "${dns}:443") {
      foreach ($handler in $entry.Value.Handlers.PSObject.Properties) {
        if ($handler.Name -ne '/' -or $handler.Value.Proxy -ne 'http://127.0.0.1:8787') {
          throw 'HTTPS port 443 already hosts another service. Stop here and ask for a separate-port configuration.'
        }
      }
    }
  }
}
New-Item -ItemType Directory -Force -Path $installDir | Out-Null
# This folder contains executable code run by the startup task. Only admins and
# SYSTEM can change code. The service account can read it and write fleet data only.
& icacls.exe $installDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)RX' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect installation folder permissions.' }
$dataDir = Join-Path $installDir 'data'
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
& icacls.exe $dataDir /grant:r '*S-1-5-19:(OI)(CI)M' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not set fleet-data permissions.' }
$taskName = 'MMG TV Office Dashboard'
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { Stop-ScheduledTask -TaskName $taskName }
foreach ($file in @('server.cjs','README.txt')) { Copy-Item (Join-Path $PSScriptRoot $file) $installDir -Force }
Copy-Item (Join-Path $PSScriptRoot 'webui') $installDir -Recurse -Force
$runtime = Join-Path $installDir 'runtime'
$node = Join-Path $runtime 'node.exe'
if (!(Test-Path $node)) {
  Write-Host 'Downloading the official Node.js runtime...'
  [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
  $base = 'https://nodejs.org/dist/latest-v22.x/'
  $sums = (Invoke-WebRequest ($base + 'SHASUMS256.txt') -UseBasicParsing).Content
  $match = [regex]::Match($sums, '(?m)^([a-f0-9]{64})\s+(node-v22\.\d+\.\d+-win-x64\.zip)\r?$')
  if (!$match.Success) { throw 'Could not find an official Windows runtime checksum.' }
  $zip = Join-Path $installDir 'node-runtime.zip'
  Invoke-WebRequest ($base + $match.Groups[2].Value) -OutFile $zip -UseBasicParsing
  if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $match.Groups[1].Value) { throw 'Runtime checksum mismatch. Installation stopped.' }
  $unpack = Join-Path $installDir 'runtime-unpack'
  Expand-Archive $zip $unpack -Force
  $folder = Join-Path $unpack ($match.Groups[2].Value -replace '\.zip$','')
  Move-Item $folder $runtime
  Remove-Item $zip
  Remove-Item $unpack -Recurse
}
$config = @{source=$site.source; allowedAddresses=@($site.allowedAddresses); allowedUsers=@($login); origin=$origin}
# UTF-8 without a BOM, compatible with Node's JSON parser.
[IO.File]::WriteAllText((Join-Path $installDir 'config.json'), ($config | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
$action = New-ScheduledTaskAction -Execute $node -Argument ('"' + (Join-Path $installDir 'server.cjs') + '"') -WorkingDirectory $installDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$principal = New-ScheduledTaskPrincipal -UserId 'S-1-5-19' -LogonType ServiceAccount -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 3
$health = $null
try { $health = Invoke-WebRequest 'http://127.0.0.1:8787/dashboard' -UseBasicParsing }
catch { if ($_.Exception.Response.StatusCode.value__ -ne 403 -or $_.Exception.Response.Headers['X-MMG-Gateway'] -ne '1') { throw 'Dashboard did not start. Inspect the MMG TV Office Dashboard scheduled task.' } }
if ($health -and $health.StatusCode -eq 200) { throw 'Dashboard unexpectedly allowed unauthenticated local access.' }
& $tailscale set --unattended=true
if ($LASTEXITCODE -ne 0) { throw 'Enable Run unattended in Tailscale Preferences, then rerun setup.' }
Write-Host 'Enabling private trusted HTTPS. If Tailscale supplies an approval link, open it and enable HTTPS.'
& $tailscale serve --bg --https=443 http://127.0.0.1:8787
if ($LASTEXITCODE -ne 0) { throw 'HTTPS setup is not complete. Complete the Tailscale approval, then run Setup.cmd again.' }
Write-Host ''
Write-Host "Dashboard address: $origin/dashboard" -ForegroundColor Green
Write-Host 'Open it in Chrome while connected to Tailscale. Enter the TV control password to import the TV list.'
Write-Host 'Use Install app (or the Chrome install menu) to add MMG TV to this computer.'
Write-Host 'Keep the office PC plugged in and awake. Keep Tailscale running in unattended mode.'
Start-Process "$origin/dashboard"
