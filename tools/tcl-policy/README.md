# TCL foreground-service exception

On TCL 55S41CG / Android 12 firmware V8-R75PT01-LF1V499.001852, `TclAppBootManagerImpl.canStartForegroundGo` passes `ServiceRecord.userId` into a helper that calls `getUidProcessState`. This rejects third-party foreground services even while their activity is TOP. Logs show `forbid startForeground`; Android later closes MMG TV with `Context.startForegroundService() did not then call Service.startForeground()`.

The installed workaround adds **only `com.restaurant.iptv`** to `serviceStartForegroundWhiteList.packageName` in the existing `AppBootPolicy` configuration. It preserves all other entries and settings. It does not enable TCL test mode, disable the policy, or alter system files. The older Android 11 TCLs and Fire TV did not need it.

`PolicyUpdate.java` is a small ADB-shell maintenance helper for the exported TCL configuration provider. Android's bundled `content --bind` parser rejects JSON containing colons, so the helper supplies a normal `ContentValues` string using the shell caller identity. It updates only the existing AppBootPolicy row. Compile against Android SDK 34, convert with d8, push the resulting jar and JSON to `/data/local/tmp`, then invoke with `CLASSPATH=... app_process /system/bin PolicyUpdate <json-path>`.

Before applying: query `content://com.tcl.providers.config/AppBootPolicy`, save the complete original JSON, append the MMG package only if absent, and inspect the diff. After applying: query again, verify exact JSON equality, restart MMG TV, and confirm both PlaybackService and RecoveryService report `isForeground=true`. Reboot and repeat. Do not apply blindly to another firmware.

Rollback uses the same helper with that TV's original JSON. Firmware updates or TCL cloud-policy refreshes may replace this exception; re-check the policy if the same ANR returns.

Device-specific backups are kept outside this repository in `../mmg-tv-device-backups/2026-10-27/{243,244}/AppBootPolicy-before.json` and `AppBootPolicy-mmg.json`.
