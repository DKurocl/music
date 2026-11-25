# Core Perfection execution ledger

Started 2026-11-25 on `agent/core-perfection-first-tap` from `main`
`5655cb2c4ab76ba06b5a3696180a8c73417bb462`. The attached Core Perfection assignment is the
implementation reference. The original roadmap keeps its own milestone numbering.

The executable candidate, command ordering and device matrix are in [CORE_ACCEPTANCE.md](CORE_ACCEPTANCE.md).

## Baseline and gates

- Baseline CI [45005510536](https://github.com/DKurocl/music/actions/runs/45005510536) passed tests,
  both lints and builds, owner signing and release. Published
  [music-v1307](https://github.com/DKurocl/music/releases/tag/music-v1307), version name 2.0.0.
  Published asset digest is `a479d8a9c2333ad5be2caa98089376770f644446bc9d2462c80be8aa01c334b3`.
  The device's installed version and firmware build are unknown.
- Build pins: Gradle 9.9.1, AGP 9.5.1, Kotlin 2.2.21, KSP 2.3.13, Media3 1.11.1, Room 2.8.5,
  Robolectric 4.17 at API 35, production SDK 37 and arm64-v8a. No dependency or schema changes.
  Current shell: JDK 21, no Android SDK, emulator, adb or authenticated Git CLI. Both wrapper
  gates stopped before project configuration at the Gradle distribution download (Java
  `SocketException: Network is unreachable`). A checksum-verified Gradle 9.9.1 download via the
  shell proxy then started successfully. Running that Gradle binary with `test -PminSdk=35 --info`
  stopped during configuration. Java could not reach the Maven proxy and could not resolve the
  Android Gradle Plugin 9.5.1 marker, although a shell download reached the marker and the AGP POM.
  The Android SDK and device are still unavailable. Hosted Android CI is pending. GitHub created the task branch at baseline,
  then automatic approval review rejected uploading the changed source because the repository is
  public. The owner subsequently requested local-only implementation until the full scope is ready.
  The candidate is committed locally. The remote task ref and main still point to baseline.
- Local JUnit 4.13.2 with Kotlin 2.2.21, Java target 17: the new metadata regression failed on
  baseline behaviour (expected 0 ms, actual 202000 ms). The shortlist test also failed on the
  old catalogue-wide availability scan. After the fixes, the full pure JVM suite (model, lyrics,
  tags, DSP) passed 211 tests, including 144 model tests. A separate JVM harness compiled the actual
  YouTube Music, lyrics parser, artwork URI and online cache sources against the API 35 Android
  classes. All 43 provider/pagination/account fixtures passed with injected transport. A separate
  smoke fixture exercised the production mix coordinator with fake app state and a delayed provider.
  Full app and Media3 integration fixtures still need Android tooling.
  `git diff --check` passed.
- M0 is open. Release-mode 1k/10k/50k baselines, end-to-end audio onset, Android startup/frame/seek
  measurements, memory, network, underruns and idle/energy need Android execution and the device.
  M1 is open. The fixture covers parser-to-policy; a full service-to-audio run and physical
  first-tap reproduction are pending.
- Keep implementation and commits local. The owner directed that test execution, Android CI, APK
  assembly and phone acceptance be consolidated after implementation of the full assignment.
  Add regression cases with each change and distinguish implemented items from verified ones.
  The GitHub pause remains in place. When resumed, candidate delivery uses branch artifacts.
  The workflow publishes owner-signed builds automatically only from `main`. Follow the physical
  acceptance and stable gate before merging. See [PHONE_CHECK.md](PHONE_CHECK.md#core-perfection-first-tap).

### Host index smoke baseline

Linux host, OpenJDK 21, Kotlin JVM target 17, `-Xms256m -Xmx768m`. Synthetic files with 120
artists, 12 tracks per album and a FLAC-style path. For each size, five warmup builds and 20 timed
`LibraryIndex.build` runs using `System.nanoTime`. First keyed lookup includes initialising the
lazy `bySongKey` map and is one sample. This bypasses Room, Android, R8 and audio.

| Tracks | Index build p50 | Index build p95 | First keyed lookup |
| ---: | ---: | ---: | ---: |
| 1,000 | 4.66 ms | 8.65 ms | 23.73 ms |
| 10,000 | 41.59 ms | 46.51 ms | 26.03 ms |
| 50,000 | 236.46 ms | 255.01 ms | 115.71 ms |


## Known paths and current decisions

| Entry | Command and source/queue owner |
| --- | --- |
| Home quick picks, mood song rows, Library and YouTube Music Search song rows | `playSelectionMix` -> seed-first `PlayerConnection.startRadio` -> custom service command -> `selectSources`. The service's existing refill runs once playing and verifies the same queue before appending. |
| Search top result | Artists/albums/playlists keep their existing open and play actions. Exact top song evidence feeds ordinary Songs rows; a top-only song used to be lost because `topCard` requires a browse ID. |
| Albums, artists, provider pages, folders, saved/imported playlists, favourites, history, shuffle | `playFrom` / `PlayerConnection.play` -> Media3 `onSetMediaItems`. Off-network unavailable occurrences retain their slots and a playable start can be chosen. |
| Explicit Start mix, curated provider mixes, moods with a provider playlist | Existing radio preparation path -> custom command. Active-track mix uses the current occurrence key. This path has separate ordering work under M2. |
| Play next and Add to queue, including bulk selection | `PlayerConnection.enqueue` -> custom service command. An enqueue belongs to the queue generation observed when accepted into the service; a later replacement/clear invalidates it. |
| Queue tap, skip, seek, shuffle/repeat, widget, tile, notification, earbud/BT keys | Commands target the current session queue. Future items are reselected on source changes, and open failure enters recovery. A notification body tap opens the player UI. |
| Restore and system resume | `PlaybackPersistence` and `onPlaybackResumption` share the checkpoint and source resolution. Queue occurrence keys and traversal are restored. |
| MediaSession browse/search, Android Auto and voice when granted | Session browse tree exposes the catalogue. `onSetMediaItems` / `onAddMediaItems` expand requested IDs and resolve. `MusicFunctions` supplies the existing app-function routes; system presentation/authorization remains a device gate. |

Search displays files from the visible catalogue and uses the same source policy as the service.
The row retains the raw provider request separately, so an initial provider row and a later projected
file submit the same video intent. Service playback includes hidden folders and reuses any published all-files index when permission
is granted. If the index is still cold, a single selection can read the indexed cached keys in
one query while the full snapshot builds. Playback waits at most a provisional 250 ms and the
observer can still apply a later published snapshot. A session can fall back without publishing
a permanent empty library or waiting on a MediaStore scan. The cold lookup regression has been
added and awaits the consolidated verification pass. A truly empty cache before import remains
a separate timing case.

For an explicitly selected single online occurrence with unknown length, a plausible indexed file
and no exact usable local source, source selection can look up metadata for the requested video
for at most 400 ms.
This is a provisional budget, pending measurement. The response must identify the exact video and
supply a duration. Existing matching rules still apply. Provider failure or timeout leaves the
usual source choice actionable. Playlist batches, refills, recovery, restore and automatic source
refresh do not use this metadata lookup.

An installed current local occurrence carries its original video. When both durations are known
and compatible, the service persists that exact pair asynchronously through the recording registrar.
The write creates no play event, favourite or collection entry. A small service-local set suppresses
duplicate writes for repeated player events and failed writes remain retryable. This does not store
arbitrary search rows or alter provider reporting. The pure extraction regression passed; the Room
regression that reopens the database and resolves without play history needs Android verification.

The source observer now follows publication of the completed all-files index. Previously it
could consume the raw track invalidation before the index completed and resolve against the old
revision. For an explicit selection whose stream is still in initial buffering at position zero,
the service can apply one local replacement. The local MediaStore row must predate the tap,
the video reserve must be the exact same video, and both known timelines must agree within one
second with no clipping. The tap's entire clock second is excluded because MediaStore records
only whole seconds. Once the occurrence reaches READY, or after progress, a pause or a Cast route,
the current healthy source stays protected and the replacement applies to future occurrences.
Hot replacement after audio onset remains deferred. A timestamp cannot prove that a physical
MediaStore row was already indexed when the user tapped. Device checks are still required.

## Issue ledger

Status for the local implementation batches, pending Android CI and physical review. Each
fixture name names its executable test class; a source observation is a hypothesis for scenarios
that have not been executed. Branch and exact verification head are recorded in Handoff.

| ID | Severity | Affected path and root cause or observation | Reproduction and expected result | Regression / evidence | Phone |
| --- | --- | --- | --- | --- | --- |
| C01 | P0 | `LibraryViewModel.online`, `PlaybackSources.requested`, `SavedSong.online`: weak UI projection carried an ID and could reconstruct provider timing from the physical file. | Tap before and after catalogue publication; both commands keep exact provider intent and the resolver chooses the valid local source. | 202000 ms metadata leak reproduced red and fixed in `PlaybackSourcesTest`; Search projection source change, Android end-to-end still open. | Pending |
| C02 | P0 | `Card.song`, `searchAll`, `songEvidence`: duration absent in cards, and top-only song excluded from ordinary browse results. | Known/unknown exact video plus equally titled different video. Only confirmed evidence selects the FLAC. | `YouTubeMusicTest` parser-to-policy and wrong-ID watch fixture. Signed-in/public device capture pending. | Pending |
| C03 | P0 | `SourceQueueUpdates`: healthy current occurrence remains protected whenever the index refreshes. | Distinguish a previously owned file discovered after the tap from a new mid-song download. Keep occurrence, timeline and history. | Pure eligibility tests for exact video, timeline, MediaStore precision and new imports. Android current-occurrence integration and physical acceptance pending. | Pending |
| C04 | P0 | `playSelectionMix` previously awaited radio before installing playable audio. | Delay provider indefinitely; the selected seed starts and late results only append to its accepted queue. | New service-owned refill path, `PlaybackMixTest` delayed reply and stale context fixture; full controller-to-audio case pending. | Pending |
| C05 | P0 | `PlaybackService` async custom enqueue and refill could commit after a replacement started. | Clear or replace while resolution runs, then verify no stale append. Deliberate edits of the surviving queue remain. | Generation guard and cancellation added. Full command matrix, pause while resolving and Android regression pending (M2). | Pending |
| C06 | P1 | `UserRepository.sourcePolicy` reads all evidence and aliases each call; the old `YouTubeMusic.indexOf` retained one index and evicted the other on every visible/service alternation. | Alternate visible/service snapshots and count Room reads, allocation and CPU at 1k/10k/50k. | Alternation reproduced red and passed with two bounded entries; local-only batches no longer build a provider credit index. Room queries and on-device profiling remain open. | Pending |
| C07 | P0 | `PlaybackSources` previously filtered availability across the full snapshot; `MusicApp.available` still lets Media3 discover actual local read failure on open. | Remove or corrupt top-ranked FLAC. Check only ranked compatible copies, try the eligible reserve, then keep the exact video if online. | Shortlist regression failed on the old sweep, then passed. A missing duration now needs an exact verified link; unknown unlinked proposals stay online. Real URI permission and corrupt-file fault injection pending. | Pending |
| C08 | P1 | `Prefetch` marks jobs before completion; upcoming artwork can run where optional network work should be constrained. | Constrained network, app/account change and failure followed by retry. | Source observation, M5. | Pending |
| C09 | P1 | `AppGraph.network` marks offline after any default callback loss. | Late Wi-Fi loss while cellular remains validated; captive portal stays offline. | Source observation, M5. | Pending |
| C10 | P0 | `recoverSource` rejects the failing source key; stream/cache resolver has its own failure path. | Expired URL, corrupt local file, manual skip/pause and Cast during recovery. | Existing bounded recovery tests; matrix and live transport evidence pending (M3/M5). | Pending |
| C11 | P1 | `LibraryRepository` already has generation deltas and a full live-ID check. | Provider reset, mutation during scan and folder policy invalidation. | Existing scan implementation and tests; race and large-library measurements pending (M4). | Pending |
| C12 | P1 | `PlayerFactory` has float output, 30s/600s buffers, 24 MiB target and 2.5s preload. | Compare PCM, effective offload, routes, buffering and power with fixtures and device. | Existing implementations and tests, actual signal/energy gates open (M6/M7). | Pending |
| C13 | P1 | `recordPlayback` issues an account report independently from local history. | Locally substituted video, account transition, skipped/fallback occurrences and duplicate reports. | Source observation, M5. Local history gate is occurrence keyed. | Pending |
| C14 | P0 release | `build.yml` publishes owner-signed main builds; Robolectric is at API 35. | Branch candidate must build and release job must skip; verify signer and phone before stable. | Baseline CI checked; branch and physical acceptance pending. | Pending |

## Debug diagnostics

`adb logcat -s MusicCore` on a debuggable build shows snapshot readiness, batch sizes, online
request/selection counts, unavailable count and monotonic selection latency. The service logs
generation and elapsed time from media-item transition to its first `isPlaying` event. This is a
player event proxy, distinct from tap-to-audible. Logs use the bounded Android log buffer, carry
no raw recording IDs, files, URLs or headers, and are disabled in release builds. Exact per-item
match rationale, revision correlation and DAC/audio onset instrumentation remain open.
