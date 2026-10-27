# MMG TV 1.0.54 reliability update

The .243 and .244 TCLs had repeated foreground-service startup ANRs. Their firmware denied MMG TV's `startForeground` request, then Android terminated the process for not entering foreground mode. See `tools/tcl-policy/README.md` for the scoped, reversible device exception.

App changes:
- Promote playback before repository initialization and reassert service startup after the activity resumes.
- A separate recovery process checks the playback main-loop heartbeat. While the screen is awake, a missing heartbeat for over 45 seconds triggers a restart and reopens MMG TV. Recovery stops after three attempts within 15 minutes to avoid an endless loop. Force-stopping the app stops its recovery service too.
- Serialize and deduplicate EPG downloads and give each XML parser its own timestamp formatter, avoiding concurrent work and shared formatter races.
- Move startup cache maintenance off the UI thread; skip it in the recovery process.
- Add recovery-budget and XML timestamp tests; run app lint and tests in CI.

Build: commit cfbb7cc1916d0b36b12bf393057ce8c23c3f7aaf, successful Actions run 40514683806. Signed release APK versionCode 54 / versionName 1.0.54. Installed in place, retaining app data, on .240, .242, .243, .244 and Fire TV .245.

Live checks on October 27, 2026:
- Both playback and recovery foreground services active on all five devices.
- Deliberate main-process crash on .243: PID 4931 replaced by 5098 and MainActivity returned without input, within the 50-60 second observation window.
- Deliberate main-process crash on .244: PID 4925 replaced by 5136 and MainActivity returned without input.
- Fire TV .245: crash at 08:57:58; recovery logged at 08:58:43 and MMG TV displayed at 08:58:45.
- Rebooted .243 and Fire TV .245: MMG TV returned automatically with both foreground services active; post-reboot crash buffers were empty. The TCL exception matched the saved configuration after reboot. Fire TV video was visible in its screenshot; .243 controls responded, but its captured video area was black, so physical picture confirmation is pending.
- Android tests/lint and 24 dashboard/gateway tests passed before deployment.

Fire TV setup: Marhaba Home 1.0.13 installed; unused user apps removed; MMG TV configuration preserved. Amazon protects its own launcher against replacement on this firmware, so the Home button still opens Amazon Home. Marhaba Home remains available as an app.

These are controlled recovery tests, not a full-day playback soak or proof that every possible crash is eliminated. AC restoration remains dependent on the television's hardware power-on setting; a Fire TV stick cannot change the connected panel's AC behavior by itself.
