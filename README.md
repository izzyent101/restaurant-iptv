# Restaurant IPTV — unattended appliance build

A single-purpose Android TV / Fire TV IPTV player built to run **10+ hours a
day, unattended**, and recover from stream/network failures on its own. Each TV
runs its own copy, holds its own Xtream login, and exposes its own web control
interface on the LAN.

This is the appliance-grade rebuild described in the project handoff. It uses
only permissively-licensed components (see **Licensing** below) — no GPL mpv /
FFmpeg.

## What it does

- **Xtream Codes + M3U live playback** on Media3/ExoPlayer.
- **Self-healing watchdog:** detects dead, frozen, or endlessly-buffering
  streams and reconnects forever with capped backoff; rebuilds the whole player
  after repeated failures. No employee ever has to touch the remote.
- **Foreground keep-alive service** with a wake lock, so playback and the web
  server survive the UI being torn down and the TV going 10+ hours unattended.
- **Boot resume:** on power-on the app relaunches and resumes the last channel.
- **Embedded web control interface** (per TV): set up the provider login, browse
  channels, play/switch the channel on that TV, hide unwanted groups, and see
  live playback health — all from your phone or PC over the LAN / Tailscale.
- **On-TV UI** is deliberately minimal: full-screen video + a remote-driven
  channel list (press OK/Menu). All rich control is in the web interface.

## Get the APK

### Option A — GitHub Actions (no local tooling)
Every push builds the APKs. Open the repo's **Actions** tab → latest run →
**Artifacts** → download `RestaurantIPTV-apks` (contains debug + release APK).
Sideload the debug APK onto each TV.

### Option B — build locally
```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```
Or open the project in Android Studio and press Run.

## Set up a TV

1. Sideload and open the app on the TV. Until it has a provider it shows
   `Open http://<this-tv-ip>:8080` on screen.
2. From your phone/PC on the same LAN (or over Tailscale), open that URL.
3. In **Provider / Xtream login**, choose *Xtream Codes*, enter the server URL,
   username, and password for **that TV's account**, and Save. Channels load and
   playback starts.
4. Repeat per TV with each TV's own account. Each TV is independent; credentials
   never leave the device.

## Reliability tuning

All watchdog thresholds live in `PlaybackService.kt` companion constants:
`WATCHDOG_INTERVAL_MS`, `STALL_TIMEOUT_MS`, `FROZEN_TIMEOUT_MS`,
`RECOVERY_PROBE_MS`, `RECREATE_EVERY`, and `backoffMs(...)`. The live stream
format is `XtreamClient.LIVE_EXT` (`ts` by default; switch to `m3u8` if a
provider only serves HLS for live).

## Architecture

```
MainActivity ──binds──▶ PlaybackService (foreground, mediaPlayback)
                         ├─ ExoPlayer (Media3)          ← plays the stream
                         ├─ Watchdog                    ← detects stalls, recovers forever
                         └─ WebServer (Ktor CIO :8080)  ← browser control + status API
Repository ─▶ Room (providers, channels, hidden groups) + Xtream/M3U clients
BootReceiver ─▶ relaunch on boot ─▶ resume last channel
```

## Licensing

Clean/permissive only: Media3 (Apache-2.0), Ktor (Apache-2.0), Room / AndroidX
(Apache-2.0), Kotlin (Apache-2.0). No copyleft mpv/FFmpeg. You can ship this
without GPL obligations.

## Status / roadmap

Implemented: Phases 1–4 of the handoff (Xtream+M3U live, hardened self-healing
playback, boot/last-channel resume, per-TV web control). Next: Phase 5 central
multi-TV dashboard (an aggregator over each TV's `/api` on Tailscale) and
Phase 6 EPG/favorites polish.
