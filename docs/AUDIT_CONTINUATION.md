# Continuation of the owner's 80-point audit

Baseline: origin/main 4c3da63. The owner's pasted report is the source for the remaining list;
older items marked done in that report have not all been independently reverified here.

## Current batch (Codex, 2026-11-17)

- 50/51 [код, verification pending]: a shared catalogue of files and actively saved online songs.
  Saved metadata comes from user.db, including history older than the 200-event UI window and
  rated songs. Home, browsing, statistics and smart playlists use it. Saved songs participate in
  local prefix search before network results arrive; network deduplication updates when saved data
  changes, without repeating HTTP requests. Local matches suppress their online duplicate; stored
  YouTube links remain intact. Filesystem folders retain only actual local files.
- 60 [код, verification pending]: complete account playlist pagination; playlist track occurrences
  survive parsing and continuation boundaries. In-flight continuation requests are shared. Failed
  pages retain their token and partial account lists are never cached. Token cycles stop the load.
- Regression found while composing the catalogue: LibraryIndex must not split an already clipped
  CUE track a second time. Covered by SavedCatalogTest.

## Expanded package requested by the owner (verification pending)

- 8: Coil, embedded covers and artist photos now divide the image share instead of each keeping
  a separate unlimited allowance. A changed limit rebuilds Coil and trims images/motion; audio
  eviction is scheduled off main immediately, without waiting for the next write.
- 21: the playback service requests radio/autoplay continuation from an application-owned mix
  cursor. UI disconnection no longer stops refill. The queue must still match the requesting
  timeline and occurrence before a loaded page can commit. Recent snapshots are bounded to 100
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

## Still open after this batch

- Model and sync: 47, 48, 49, 52, 56, 58, 59, 63, 64. Saved song snapshots are projected here;
  stable recording/source IDs, full offline page storage and bidirectional sync remain open.
- Partial performance/system work: 45, 50, 74; the remaining limitations are described above.
- Navigation, system and UI: 15, 18, 70, 76, 77, 78, 79, 80.
- Physical phone gates: 10, 11, 12, 19, 23, 46, 75. No Pixel or Sony measurements in this session.
- 73 remains an intentional owner option: lock-screen lyric metadata. Do not remove it unasked.
- The previous report calls six other points partial, explicitly including 72 (buffering rings).
  It does not identify all the other partial point numbers; no completed status is inferred for them.

This file preserves the backlog that the previous chat said was missing from HANDOFF.md.
