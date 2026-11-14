# Release evidence and trial scenarios

verified: ні

[аналіз] Record each gate as pass/fail/not-run, with command and output. A plan, YAML or source file does not prove Android behavior. Release blocks on failed or unimplemented gates.

| Gate | Evidence |
|---|---|
| Pure logic | Deadline arithmetic, restart, overflow, permission transition tests |
| Visual | Roborazzi/Robolectric per screen/state, light/dark, fontScale 1 and 2, reviewed goldens and diff |
| Accessibility | ATF on emulator, semantic labels, contrast and large-font layout |
| Platform | API 35 fallback/API 37, denied/revoked permissions, process death/reboot |
| Startup/background | am start -W, dumpsys services/jobs/alarm/power with task-required work only |
| Lint | lintRelease, warningsAsErrors |
| Performance | Generated baseline profile, actual startup/action exercise, packaging verification |
| Optimizer | R8 release build and release smoke test |
| Delivery | zipalign/apksigner, owner certificate, increasing versionCode, downloaded SHA-256 |

[аналіз] Grep is not visual review. Dynamic colors do not guarantee contrast after custom composition. Screenshots cannot prove no background work. Do not generate fake baseline-prof.txt or approve generated screenshots without review.

## QuickFocus

[аналіз] 25-minute countdown, Focus 25 min tile, Glance home/lock widget. Persist deadline and derive remaining time, no ticking background service. Schedule end event. Test duplicate clicks, tile lifecycle, expiry with dead process, denied notifications, reboot/time change, lock privacy. Widget must reflect state; a static 25 min label is incomplete. No per-second widget wakeups.

## Alarmist

[аналіз] Schedule/cancel/reschedule exact alarms. Default SCHEDULE_EXACT_ALARM; USE_EXACT_ALARM only for dedicated alarm purpose. Check canScheduleExactAlarms. Restore persisted schedule after boot/time change. ProgressStyle 36+, promotion request 36.1+, inspect posted flags/dumpsys. Promotion may be refused and countdown may not qualify; do not claim successful Live Update based on a Builder flag.

## NanoNote, optional

[аналіз] Production engine defaults to unavailable until availability succeeds. Deterministic fake only in tests; no fake Nano result or cloud fallback. Check availability/download, language, foreground, cancellation and quotas on physical phone.

## Representative commands

```sh
./gradlew :apps:quickfocus:testDebugUnitTest :apps:quickfocus:lintRelease -Pheavy=true
./gradlew :apps:quickfocus:verifyRoborazziDebug -Pheavy=true
./gradlew :apps:quickfocus:connectedDebugAndroidTest -Pheavy=true
adb shell am start -W dkurocl.quickfocus/.MainActivity
adb shell dumpsys activity services dkurocl.quickfocus
adb shell dumpsys jobscheduler
adb shell dumpsys alarm
adb shell dumpsys notification
adb shell dumpsys power
```

[аналіз] Discover actual tasks first. Missing task is an unimplemented gate. Initial scaffold deliberately blocks release until visual/device/profile checks are wired. Never remove that guard to make CI green.

