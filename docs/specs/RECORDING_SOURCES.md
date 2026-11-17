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

## Implementation sequence

1. Pure RecordingSource, conservative RecordingMatcher and bounded versioned queue codec. Added
   ten tests; 175 pure tests passed before the later SavedSong/reference changes.
2. user.db v4: sources, aliases, saved createdAt and offset updatedAt. Genuine KSP-generated 4.json
   already exists from the standalone user harness. DAO/registrar/repository draft compiles there.
3. Complete repository tests using durable references instead of provisional snapshot IDs. Add
   migration fixtures for all personal relations, linked source fallback, merges and refusal of
   ambiguous takes. Alias reconciliation must not multiply history or discard playlist duplicates.
4. Add efficient local-source binding to SavedCatalog and source reconciliation on library changes.
   Do not rewrite unchanged rows or trigger a read/write loop. Keep duplicate-file management based
   on the scanned source index. Use recording IDs for catalogue rows/stats and both source IDs for likes.
5. Preserve links through local-first substitution, PlayerConnection metadata and PlaybackStore.
   New queue metadata remains optional for old checkpoints. Do not replace a playing source mid-song.
6. Lyric files use recording IDs. Read old SongKey files and merged alias IDs as fallbacks. Prefer
   the most recent explicit edit, including an empty reset marker, without deleting another alias's
   only text. Ensure timing edits resolve the durable identity before writing.
7. Central source selection in PlaybackService for app, queue commands, browser and recovery. Keep
   requested identity and occurrence keys, select available file first, else the YouTube reserve.
   Expose actual selected source URI separately so lyrics, format and cache marks describe reality.
8. Full tests, lints, R8/signature and artifact checks before main/release. No phone claims.

## Local verification environment

`/workspace/toolchains/music-user-check` is an external JVM harness, not an APK build. It includes
real model/user sources and Room KSP. Same pinned Kotlin/KSP/Room/Robolectric as the app. Android SDK
is absent. Set only the test worker's Java user.home into its writable test-home for the Robolectric
lock/download cache; shell HOME is untouched. Force the actual SQLite Android 2.6.2 variant in the
harness, excluding its incompatible JVM variant. No production dependency change or assertion bypass.
A real native SQLite UserRepository reopen baseline passed. Need asset configuration for migration
fixtures; no migration gate has passed yet. Use CI if a genuine local gate cannot be reproduced.

## Current caveats to finish before delivery

- New schema and repository are drafts, not a completed migration. Existing tests still assume
  path-derived IDs and need assertions against registered identities and source metadata instead.
- RecordingRegistrar must avoid stale secondary-source snapshots overwriting fresher primary data,
  skip no-op alias writes, and chunk large IN lists. Read-only reference lookups must be bounded too.
- Shared favourites must include all currently resolved local source IDs and linked YouTube IDs.
- Source substitution must carry the original YouTube reference even if the first play uses a file.
- New persistent recording IDs must be used throughout lyrics and stats; do not leave old per-path
  lookups mixed with the new model. Saved-page persistence and full album/artist identities are later
  blocks (49/64), followed by account-scoped bidirectional sync (56/58/59).
