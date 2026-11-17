# Continuation of the owner's 80-point audit

Baseline: origin/main 4c3da63. The owner's pasted report is the source for the remaining list;
older items marked done in that report have not all been independently reverified here.

## Current batch (Codex, 2026-11-17)

- 50 (partial), 51 [код, CI 44114015206]: a shared catalogue of files and actively saved online songs.
  Saved metadata comes from user.db, including history older than the 200-event UI window and
  rated songs. Home, browsing, statistics and smart playlists use it. Saved songs participate in
  local prefix search before network results arrive; network deduplication updates when saved data
  changes, without repeating HTTP requests. Local matches suppress their online duplicate; stored
  YouTube links remain intact. Filesystem folders retain only actual local files.
- 60 [код, CI 44114015206]: complete account playlist pagination; playlist track occurrences
  survive parsing and continuation boundaries. In-flight continuation requests are shared. Failed
  pages retain their token and partial account lists are never cached. Token cycles stop the load.
- Regression found while composing the catalogue: LibraryIndex must not split an already clipped
  CUE track a second time. Covered by SavedCatalogTest.

## Expanded package requested by the owner (code bc99d27, CI 44114015206 passed)

- 8: Coil, embedded covers and artist photos now divide the image share instead of each keeping
  a separate unlimited allowance. A changed limit rebuilds Coil and trims images/motion; audio
  eviction is scheduled off main immediately, without waiting for the next write.
- 21: the playback service requests radio/autoplay continuation from an application-owned mix
  cursor. UI disconnection no longer stops refill. The queue must still match the requesting
  timeline and occurrence before a loaded page can commit. A new manual queue or shuffle clears
  the old mix label and cursor, rejecting an old continuation still in flight. Recent snapshots are bounded to 100
  preceding songs when preparing the service request. Process-death persistence of the radio
  continuation remains open under 74.
- 32/33: app and lock-screen lyrics use one resolver for edited, sidecar, downloaded, embedded
  and online text. The service reads the same saved offset. Online timing controls now save too.
  Owner's opt-in lyric-as-artist display (73) is retained.
- 34: word timing uses the frame clock. The clock is read in the text drawing phase through a
  lambda; only line transitions change composition. Word paths/layout are cached for the active
  line. Physical frame tracing is still required.
- 53/54: offline playback changes the starting index without deleting unavailable occurrences.
  The service skips forward to an available item after an offline failure. Cache add/remove/clear
  events invalidate per-row offline marks; reading a mark still never opens the cache.
- 66: system browse and voice searches receive the saved online catalogue as well as local files.
- 74 (partial): startup and system resumption share one journal read. The current queue wins over
  disk, and shuffle traversal/repeat are restored after system replacement, guarded by occurrence
  keys. Playing context is checkpointed with each media item and returned to the UI after restart.
- 45 (partial): artist photos, biographies and Apple motion lookups share two auxiliary permits;
  audio resolution does not wait for these permits. Bandwidth/QoS on the phone is not measured.

Software evidence: 406 tests passed, debug/release lint and APK builds passed, owner signature
verified. Only component renders were reviewed. Phone measurements and live account pagination
are not verified. Source-only statuses do not stand in for physical acceptance.

## Recording/source continuation (music-v1116, 45be07f)

- 47/48/63 foundation [код, CI 44172917731]: durable recording IDs, retained local/YouTube
  sources, permanent merge aliases, shared statistics, favourites and lyric text/timing. Migration
  preserves existing IDs, playlist occurrences and history. Ambiguous takes are not merged by name.
- Downloaded music-v1116 APK matches its published SHA256 and existing owner signer. 441 distinct
  tests passed with zero failures/errors/skips; both lints and both APK builds passed. No device claim.
- 52 [код, implementation 6cc00d7, CI 44191397033]: one bulk full-evidence source policy for
  app/browser/enqueue/refill/startup/resumption and bounded recovery. It keeps unavailable occurrences,
  requested identity, physical source and queue key separate, protects a healthy current stream and
  avoids counting a source replacement as another play. 480 tests, both lints/builds and signer
  passed. Final observer follow-up reacts to recording/source metadata changes; delivery is gated
  on the final branch head before integration.
- 41 refinement on the same branch: unchanged MediaStore generations skip refresh scans; manual
  Rescan remains forced. Playback uses the cached snapshot while the observer refreshes independently.
  This does not implement a complete incremental scanner or establish a phone performance number.

## Still open after this batch

- Model and sync: 49, 56, 58, 59, 64. Durable online pages,
  stable album/artist links and bidirectional account sync remain open. Recording/source foundation
  47/48/63 is delivered with the central policy block; physical cross-surface acceptance remains open.
- Partial performance/system work: 45, 50, 74; the remaining limitations are described above.
- Navigation, system and UI: 15, 18, 70, 76, 77, 78, 79, 80.
- Physical phone gates: 10, 11, 12, 19, 23, 46, 75. No Pixel or Sony measurements in this session.
- 73 remains an intentional owner option: lock-screen lyric metadata. Do not remove it unasked.
- The previous report calls six other points partial, explicitly including 72 (buffering rings).
  It does not identify all the other partial point numbers; no completed status is inferred for them.

This file preserves the backlog that the previous chat said was missing from HANDOFF.md.
