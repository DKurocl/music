# Core Perfection acceptance

This is an execution record for the owner-signed branch candidate and eventual stable build.
Use [CORE_PERFECTION.md](CORE_PERFECTION.md) for evidence against each issue. Existing components
remain part of the verification, including features that did not need a second implementation.

## Processing and readiness evidence

| Milestone | Source implementation and verification boundary |
| --- | --- |
| M0 | Source ledger, launch readiness marker, redacted source counts, Media3 playing and audio-position proxies, and synthetic host index baseline. Acoustic, frame, energy and release-equivalent device measurements are pending. |
| M1 | Exact video intent and mastering safeguards, shared resolver, cold indexed lookup, eligible local fallback, durable source evidence and one pre-READY promotion of a verified pre-existing file. Actual first tap without an upstream audio fetch still needs the phone. |
| M2 | Seed-first service command, cancellable refill, generation checks for replacement/addition, live occurrence checks and pause precedence. The deferred command fixtures and full session stress matrix still need execution. |
| M3 | Pause on shared network/account/output errors, bounded 429 handling, existing expired-URL retry, current-only guarded recovery, unavailable state in checkpoints and a coalesced journal. System media, Bluetooth/Cast/focus, process reclaim and 24h soak remain physical validation. |
| M4 | Existing preserved Room migrations, exact-key cold query, 1k/10k/50k identity fixtures and unique provider rank/occurrence keys. Room and MediaStore regeneration, revoked permission, large playlists and cache/scan probes remain unverified on this head. |
| M5 | Validated default-network handover, account-bound radio and likes, bounded continuation work, visible prefetch and optional-transfer deference to audio. Live provider/cache, bandwidth and account conflict matrix remain pending. |
| M6 | Existing Media3 PCM handling, ReplayGain, EQ and USB integration retained. Offload excludes requested DSP and non-unity speed/pitch. Pins are unchanged. Actual decoder precision, gapless and Sony/USB route measurements remain physical. |
| M7 | Initial usable-library draw marker and producer-side journal coalescing; lifecycle-bound optional loads and no buffer or codec retuning from speculation. Trace, repeated energy tests, thermal state, memory and baseline profile generation remain physical. |
| M8 | MediaStore publication wakes the existing scanner, verified lyrics can follow compatible source switches, and future queue occurrences can pick a local copy. Verify external files, partial transfers and actual audio continuity before closing. |
| M9 | Branch test candidate remains separate from stable. Runner validates alignment, incumbent certificate and APK digest. Candidate results, physical acceptance and final release remain pending. |

## Ordering contract exercised by the consolidated suite

| Event | Commit condition |
| --- | --- |
| Play(A), then Play(B) or Clear | A's generation becomes obsolete. Clear also supersedes the durable restore. |
| Pause or play during radio/add-to-empty resolution | The newer transport intent owns autoplay; installing a paused queue is allowed. |
| Initial queued selection while disconnected or copying metadata | The pending controller intent is used when the accepted request reaches the service. |
| Explicit radio on the current recording | Same live occurrence and queue order at commit; preserve the seed and position. |
| Automatic radio during manual edits | Same generation, occurrence order, traversal and repeat; a rejected continuation does not move the cursor. |
| Seek or next during recovery | Only recover the live failed occurrence. Explicit seek position is read at commit; a later pause, replacement or Cast cancels the recovery commit. |
| Offline, throttling or route failure | Keep the occurrence for manual retry. Exhausted local/video candidates are staged before moving to the next eligible occurrence. |
| Restore and service teardown | Use the journal read barrier and exact generation, preserve unavailable placeholders and load paused. |

## Candidate and stable

1. Run the branch build once for the fully reviewed source head. Use the complete unit/lint/R8
   workflow and confirm the release job was skipped on the candidate branch.
2. Download `Music-install`. Verify the APK file against `SHA256SUMS` and the owner certificate
   from published `music-v1307`. Check that the version code is newer than the installed build.
   Keep the signed candidate separate from the Obtainium stable feed.
3. For the first Pixel pass, use the phone-only scenario in [PHONE_CHECK.md](PHONE_CHECK.md) and
   record installed version, Android build, exact source and first audible outcome. Test the same
   song on baseline and candidate. Follow with restore, offline, Bluetooth and sleep cases.
4. Use a connected Pixel for the extended matrix. Run at least twenty measured cold/warm starts,
   use matched settings for paired two-hour screen-off runs, and keep a 24-hour playback soak.
5. Verify the exact final source head again if it differs from the candidate. Merge to `main`
   only when the required gates pass. Download the stable release and verify its digest and signer.

The verified baseline APK asset SHA-256 is
`a479d8a9c2333ad5be2caa98089376770f644446bc9d2462c80be8aa01c334b3`. Its single signer fingerprint is
`5c2e57db99715e0fc65befdcf8febd1a00222a104a24d635f27f97d949abc107`. Android APK signature verification
against API 37 passed for that baseline asset. It is independent of candidate software checks.

## Connected-device measurement

Use an owner-signed release-equivalent build. Record Android build, version code, output, EQ,
ReplayGain, offload preference, speed, pitch, volume, charger state, screen refresh and network.

```sh
adb shell getprop ro.build.fingerprint
adb shell am force-stop ua.olex.music
adb shell am start -W -n ua.olex.music/.MainActivity
adb shell dumpsys gfxinfo ua.olex.music reset
adb shell dumpsys gfxinfo ua.olex.music framestats
adb logcat -s MusicCore
```

Run the frame capture after a fixed timed library/lyrics/queue journey. Record startup TTID and
the fully drawn event separately. The fully drawn marker represents the first usable catalogue
or its permission/error state. On debug builds, `playingEventProxyMs` starts at occurrence
transition. `audioPositionEventProxyMs` reports the first media audio-position advance for that
occurrence. Neither proxy includes measured acoustic output. Use an external recording or
calibrated wired capture for tap-to-audible, gapless gaps and recovery discontinuities.

Use [Macrobenchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)
and Perfetto frame/audio traces when a profileable test build and a connected Pixel are available.
Use independent repetitions for battery and memory, record variance, and avoid interpreting
global battery percentage as app-attributed power. This checkpoint contains no Pixel performance
or power claim.
