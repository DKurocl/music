# Continuation of the owner's 80-point audit

Baseline: origin/main 4c3da63. The owner's pasted report is the source for the remaining list;
older items marked done in that report have not all been independently reverified here.

## Current batch (Codex, 2026-11-17)

- 50/51 [code, verification pending]: a shared catalogue of files and actively saved online songs.
  Saved metadata comes from user.db, including history older than the 200-event UI window and
  rated songs. Home, browsing, statistics and smart playlists use it. Saved songs participate in
  local prefix search before network results arrive; network deduplication updates when saved data
  changes, without repeating HTTP requests. Local matches suppress their online duplicate; stored
  YouTube links remain intact. Filesystem folders retain only actual local files.
- 60 [code, verification pending]: complete account playlist pagination; playlist track occurrences
  survive parsing and continuation boundaries. In-flight continuation requests are shared. Failed
  pages retain their token and partial account lists are never cached. Token cycles stop the load.
- Regression found while composing the catalogue: LibraryIndex must not split an already clipped
  CUE track a second time. Covered by SavedCatalogTest.

## Still open after this batch

- Model and sync: 47, 48, 49, 52, 56, 58, 59, 63, 64. Saved song snapshots are projected here;
  stable recording/source IDs, full offline page storage and bidirectional sync remain open.
- Performance and cache: 8, 21, 34, 45, 53, 54.
- Navigation, lyrics, system and UI: 15, 18, 33, 70, 74, 76, 77, 78, 79, 80.
- Physical phone gates: 10, 11, 12, 19, 23, 46, 75. No Pixel or Sony measurements in this session.
- 73 remains an intentional owner option: lock-screen lyric metadata. Do not remove it unasked.
- The previous report calls six other points partial, explicitly including 72 (buffering rings).
  It does not identify all the other partial point numbers; no completed status is inferred for them.

This file preserves the backlog that the previous chat said was missing from HANDOFF.md.
