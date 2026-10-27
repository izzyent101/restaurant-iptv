# Marhaba Home

Lightweight native Android TV home screen, separate from MMG TV. Olive, ivory and brass styling, procedural palm-tree and pyramid backdrop, clock, favorites and installed TV apps. No network permission, advertising SDK, background service or feed. MMG TV is always pinned. All apps and TV settings remain available.

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
