# Recording identity and source links

Status: in progress on `agent/recording-sources`, after verified release music-v1109.
Owner asked to continue while asleep. Nothing in this block is delivered yet.

## Contract

- A durable SavedSong row is the recording. Keep all old primary keys during migration. New rows
  receive independent `recording:<UUID>` IDs. A `source:` ID is only an unsaved lookup snapshot.
- Track keeps its source Media3 ID and gains recordingId plus source evidence. Queue occurrence
  keys remain separate and do not change when a source changes. Statistics use recordingId.
- `song_sources` is a child table keyed by recording and source key. Local keys use exact paths
  (or URI without a path) and CUE clip boundaries. YouTube keys use video IDs. Unavailable sources
  stay stored. SavedSong.online() can fall back even when its preferred path is a local file.
- Source evidence includes key, duration, known recording MusicBrainz ID, quality, URI/path, clip
  and its own artwork. Do not attach a file's MusicBrainz/gain/clip/artwork to its YouTube reserve.
- An exact unresolved M3U path can acquire actual metadata. Otherwise known durations must agree
  within the existing tolerance; missing duration requires exact source evidence. Live/remix and
  distinct CUE occurrences remain separate. Known conflicting MusicBrainz recordings or duration
  bridges do not merge. Explicit linked credits can differ in artist but retain the same title.
- A confirmed merge keeps the oldest durable identity; all superseded IDs become permanent aliases.
  Rewrite playlist references in place, preserving every occurrence and position. Keep all history
  event IDs and timestamps. Favourite is OR, rating takes latest ratedAt, offset takes latest updatedAt
  (prefer canonical on ties, since old offsets had no timestamp). No destructive migration.

## Implemented, final CI pending

- RecordingSource, conservative matcher and bounded versioned queue codec. Source ID remains
  separate from recording ID and queue occurrence. Unknown-length automatic substitutions do not link.
- user.db v4 and genuinely generated schema: sources, aliases, createdAt and offset updatedAt.
  Registrar merges relations transactionally, chunks large IN lists, ignores stale secondary snapshots
  and performs no writes for an unchanged registration. An explicit zero rating retains its timestamp.
- Indexed catalogue binding, local reconciliation and best-copy projection. Physical folder rows
  and duplicate-file management remain available; every linked source shares favourite state/stats.
  Unresolved imported paths acquire real tags only on unambiguous resolution; that weak placeholder
  is then promoted, preventing a later different take from claiming its old import automatically.
- Ambiguous physical sources get a stable unresolved row, preserving conflicting linked takes.
  Explicit valid recording IDs remain authoritative when source metadata alone is ambiguous.
- Local-first substitutions, PlayerConnection and v1 checkpoints retain identity and reserves.
  Current playback is not re-prepared on download. Source references are bounded in queue metadata.
- Recording-keyed lyric writes, legacy SongKey/merged-ID reads, latest explicit edits and reset
  markers. Online timing controls are visible. Offset/statistics use the same durable identity.
- Linked local hearts queue outbound YouTube likes too. Sending is serialized and an old response
  cannot clear a newer pending intent. Inbound/account-scoped sync remains a later block.

First complete Android CI 44166893313 (`648bd85`): 431 tests, both lints, debug/release APKs and
owner signing verification passed, including migration 3 -> 4. Final follow-up CI is required.

## Next blocks

1. Central source selection in PlaybackService for app, enqueue, browser and recovery (52).
   Use the entire known recording/source evidence to guard automatic local substitutions, including
   ambiguous physical copies shared by conflicting takes. Raw local-index matching alone is insufficient.
   Select an available file first, else the retained YouTube source, preserving occurrence identity.
2. Persistent online pages and stable album/artist links (49/64).
3. Account-scoped bidirectional likes, unified playlists and conflict-aware operation journals
   (56/58/59). Current outbound like preferences still need account scoping.
4. Phone acceptance remains separate from source/CI evidence.

## Local verification environment

`/workspace/toolchains/music-user-check` is an external JVM harness, not an APK build. It includes
real model/user sources and Room KSP. Same pinned Kotlin/KSP/Room/Robolectric as the app. Android SDK
is absent. Set only the test worker's Java user.home into its writable test-home for the Robolectric
lock/download cache; shell HOME is untouched. Force the actual SQLite Android 2.6.2 variant in the
harness, excluding its incompatible JVM variant. No production dependency change or assertion bypass.
Native SQLite repository tests pass. The standalone harness cannot package schema assets for
MigrationTestHelper, so those tests are verified in the real Android CI instead. Host SQLite separately
executed the exact migration SQL and matched the exported v4 columns, indexes and foreign keys.
