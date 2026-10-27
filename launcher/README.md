# Marhaba Home

Lightweight native Android TV home screen, separate from MMG TV. Olive, ivory and brass styling, procedural palm-tree and pyramid backdrop, clock, favorites and installed TV apps. No advertising SDK, background service or recommendation feed. HTTPS internet access is used only for NWS forecasts for Minneapolis 55408; fetched off the UI thread, cached, and refreshed every 30 minutes while the launcher is visible. Failed refreshes show a cached label; expired periods are hidden. Solar elevation is calculated locally for 44.95,-93.278 to select sunrise/day/sunset/night palettes, checked once per minute while visible. The centered logo is the existing Marhaba Grill brand asset. MMG TV is always pinned. All apps and TV settings remain available.

Build: `./gradlew :launcher:assembleRelease :launcher:lintRelease`. Sign the unsigned release APK with the dedicated Marhaba Home key; retain the same key for upgrades. The local signing key is outside this repository under `~/.mmg-tv-signing/`; back it up securely. Do not commit it.

## Tested device and setup

TCL G10, Android 12, 192.168.1.243. Install signed APK with `adb -s ADDRESS:5555 install -r APK`. Launch `com.marhaba.launcher/.HomeActivity` and verify it renders before changing the home screen.

On this firmware, `cmd package set-home-activity` alone is overridden by system home priorities. These packages were disabled for user 0 (data retained):

- com.google.android.apps.tv.launcherx
- com.google.android.tungsten.setupwraith (setup/recovery home interception; reenable before device setup)
- com.tcl.channelplus
- com.tcl.tv.plus

Then run `cmd package set-home-activity com.marhaba.launcher/.HomeActivity`. Verify Home resolves to it and that MMG TV opens with the remote. Package states vary by device; inspect each device before deployment. Do not apply TCL cleanup to Fire TV.

## Restore original home

With ADB, run `pm enable --user 0 PACKAGE` for the four packages above, then `cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity`. Press Home and verify before uninstalling Marhaba Home. Do not uninstall the only enabled launcher.

## Device cleanup and power

Disposable caches were trimmed. Haramain Live Viewer and SmartTube were explicitly uninstalled at the owner's request. Other application data and MMG TV credentials/fleet were preserved. Package inventory before cleanup is saved outside this repo in the local device backup folder.

Automatic screen power-on after AC restoration remains unverified. No factory/service-menu power setting was changed. MMG TV's existing boot receiver remains responsible for playback resume; this launcher does not override it or add another boot loop. A physical power-restoration test is required. Remaining TCLs need ADB access; the device at .245 is an Amazon Fire TV, not TCL, and has not been modified.

Forecast cards group NWS day/night periods by local Minneapolis date, showing seven dates, highs/lows, maximum precipitation probability, and vector weather animation. Missing temperatures are shown as dashes. One shared 12 fps drawing loop runs only while the launcher is visible.

Version 1.0.4 uses translucent gradient panels, a compact favorites grid, floating controls dock, and restrained weather dividers. Weather is rendered in one software-backed view to avoid partial-frame artifacts on the TCL GPU. Dialogs use a bounded, readable window.

Version 1.0.6: weather starts in an 80dp compact strip; OK toggles details and Back collapses it. Only the bottom control dock hides after 20 seconds without interaction; app tiles and weather stay visible. The first navigation press reveals controls without launching an action. Dialogs pause the hide timer, use an opaque panel, and request system blur behind on Android 12+ where supported.

At the owner's explicit request, remaining third-party applications except MMG TV and Marhaba Home were removed from user 0. Prime Video, YouTube, YouTube Music and Netflix system apps were disabled. OS, Play services, networking, settings and updates were preserved. Cleanup results are recorded in the local device backup folder.

Version 1.0.8 replaces the compact weather strip with a 48dp bottom ticker. It scrolls seven forecast days, pauses on focus, supports left/right browsing and OK to expand. Back collapses. App tiles and weather remain visible when the controls auto-hide.

Version 1.0.9 keeps all seven days simultaneously visible in the slim bottom bar; scrolling was removed at the owner's request. Weather icons remain animated and OK still expands details. The app tile is reduced to 112dp and the backdrop divider behind it is removed.

Version 1.0.10 adds a vector seven-segment clock respecting 12/24-hour preference, top-left date, left-aligned controls, full-width scenery, palm sway, subtle solar glow and night star twinkle. Scenery refreshes at 8 fps only while the activity is resumed, and the clock once per second; both stop on pause. Reset backdrop paint opacity each frame to prevent stale alpha flattening the sky gradient.

Version 1.0.11 replaces overlapping scenery with one continuous landscape: consistent horizon and pyramid lighting, layered ground planes, and two edge-framing palms. Keeps solar phases and subtle animation. Build and release lint passed; installed and visually checked on .243.

Version 1.0.12 changes ground and pyramid shadows to warm sand and ochre, with asymmetric dune crests, a lit dune face and sparse sand texture. Built, lint checked and visually verified after installation on .243.
