# Architecture

## Modules

```
:core:model     pure Kotlin   Track, TrackId, AudioFormat, SongKey (one song across sources)
:core:lyrics    pure Kotlin   Lyrics model; LRC, word-timed LRC and TTML parsers
:core:user      Android lib   Durable playlists, favourites, history and lyric offsets
:core:library   Android lib   Room cache of local tracks, scanner, LibraryRepository
:core:playback  Android lib   Media3: PlaybackService, PlayerFactory, PlayerConnection, OutputRoute
:app            Android app   Compose UI, AppGraph (manual DI), MainActivity
```

Planned, each its own module so agents can work side by side:

```
:core:tags       pure Kotlin   reads tags and audio properties from the files themselves (exists); writers (M7)
:core:soulseek   pure Kotlin   Soulseek protocol: server and peer messages, search, transfers
:source:youtube  pure Kotlin   YouTube Music: InnerTube for search/browse/radio, stream resolver
:source:soundcloud pure Kotlin SoundCloud search and streams
:core:lyrics-net pure Kotlin   LRCLIB and Kugou clients (keeps :core:lyrics free of network)
:core:motion     Android lib   animated artwork: lookup, download, cache, looping video
:core:organizer  pure Kotlin   path templates, moving downloads into the library hierarchy
```

Dependency rule: `:app` -> `:core:*`, `:source:*` -> `:core:model`. Nothing depends on `:app`.
`:core:model` depends on nothing. Pure Kotlin modules must not gain Android dependencies: they are
the fast tests.

## The idea that ties it together

Every track, from any source, is a `Track` with a `TrackId` of the form `<origin>:<id>`
(`local:1432`, `yt:dQw4w9WgXcQ`, `sc:...`). The same string is the Media3 `mediaId`, so the queue,
the session, the notification and Android Auto speak about the same thing.

`SongKey` is the song itself, wherever it is: artist and title with case, accents, punctuation,
"feat." credits and edition notes ("Remastered 2011", "Official Video") removed, while notes that
mean another recording ("Live", "Remix", "Acoustic") stay. Two tracks are the same song when their
keys are equal and their lengths agree within 3 s (`SongKey.sameSong`). On this rest:

- a mix from a local track: find the song on YouTube Music by key and length, ask for its radio;
- local first: every streamed item of a queue is looked up with `LibraryRepository.owned()` and the
  owned file plays instead (best `AudioFormat.rank`);
- "already in the library" marks in search and Soulseek results;
- "get this one" from a mix: a Soulseek search prefilled with the song.

## Playback

`PlaybackService` (a Media3 `MediaLibraryService`) owns the only ExoPlayer. The UI holds a
`PlayerConnection` (a `MediaController` wrapped into a `StateFlow<PlayerState>` and commands).
Screens never see ExoPlayer. Items cross the process boundary without their URI; the sender puts it
into `RequestMetadata.mediaUri` and `onAddMediaItems` restores it. Streams will be resolved to a
playable URL at that same point, so a queue can hold thousands of unresolved items.

`PlayerFactory` is the one place that decides how audio leaves the app (see `docs/AUDIO.md`).

## Library

Two databases, on purpose:

- `library.db` (exists): a cache of what is on the phone. Destructive migrations; a scan rebuilds it.
  A scan compares a complete source snapshot inside one transaction, writes changed rows and deletes
  missing IDs in batches. Unchanged rows keep their old scanId and do not fire FTS/Room invalidations;
  scanId records the last content change, not membership in the latest snapshot.
- `user.db` (version 1): playlists, favourites, play history, per-track lyric offsets. Real
  migrations with exported schemas. It refers to songs by `SongKey` and path, never by MediaStore id,
  so it survives a rescan and resolves moved files. Uninstall still removes app data; backup/export is not implemented.

The first scanner reads MediaStore (instant, no native code). M1 replaces it behind
`LibraryRepository` with a file walker and TagLib, because MediaStore knows one artist per track and
no ReplayGain, lyrics, MusicBrainz ids or multi-value tags.

## UI

Compose, Material 3 Expressive, wallpaper colors on every screen, including the player. Album art never changes the palette. Manual DI:
`AppGraph` makes one of each long-lived object on first use. The design system (Google Sans Flex,
the Material Symbols variable font, motion, haptics, splash hand-off) comes from the owner's Earbuds
app (repository `DKurocl/xm5`) and its `docs/DESIGN.md`; reuse it rather than invent a second one.

## Decisions

1. Media3 ExoPlayer, not a custom engine. It has gapless, offload, a session, Android Auto and a
   team behind it. Quality comes from configuring it right and measuring (AUDIO.md), not from
   replacing it. `[аналіз]`
2. Float output on. Without it ExoPlayer reduces 24-bit and float PCM to 16 bits. `[docs]`
   https://developer.android.com/reference/androidx/media3/exoplayer/DefaultRenderersFactory#setEnableAudioFloatOutput(boolean)
   Still to measure on the phone that the platform FLAC decoder hands over more than 16 bits.
3. Manual DI, no Hilt. One of each object, a graph that fits on a screen, faster builds. `[аналіз]`
4. Pure Kotlin modules for all logic that needs no Android. Tests in seconds, on any machine.
5. `minSdk = 37`, arm64-v8a only. One phone. `[owner]`
6. Kotlin 2.2.21 (compiler plugin), KSP 2.3.13, AGP 9.5.1, Gradle 9.9.1, Compose BOM 2026.09.00,
   Material 3 1.5.0-rc01: the owner's pins, proven by the Earbuds build. Coil stays at 3.4.0: 3.6 is
   built with Kotlin 2.4 and the 2.2 compiler cannot read it (`[код]` build error, this session).
   Raising Kotlin is one commit that also raises Coil.
7. The library is found by scanning files (M1), not by trusting MediaStore. "All files access"
   (`MANAGE_EXTERNAL_STORAGE`) is acceptable: a sideloaded personal app that must also write tags,
   sidecar lyrics and downloads into the owner's folder tree. `[аналіз]`
8. Tags are read by our own pure Kotlin reader (`:core:tags`), not TagLib over JNI. Reasons: no NDK
   in the build, no 16 KB page question, tests in seconds from byte fixtures, and a scan reads only
   headers (a few small reads per file; pictures stay byte ranges). It covers what the owner has and
   what Android plays: FLAC, MP3 (ID3v2.2-2.4, ID3v1, Xing/LAME), Ogg Vorbis, Opus, M4A (AAC, ALAC),
   WAV. Legacy text is decoded as Windows-1251 when it looks Cyrillic (old Ukrainian and Russian
   MP3s). Not covered yet: APE, WavPack, DSF, AIFF, Ogg FLAC, pictures inside Ogg comments, ID3
   compressed frames. A file the reader does not know falls back to MediaStore values. Tag writing
   (M7) is added per format in the same module; if a format proves too risky to write by hand,
   TagLib may come in for writing only. `[код]` 14 tests; real-file checks on the phone pending.
9. Streaming: YouTube Music first (InnerTube for search, browse and radio; NewPipeExtractor for
   stream URLs, because that is the part YouTube breaks and a large community fixes within days),
   SoundCloud through the same extractor. Spotify is not a playback source: it needs Premium and
   librespot, and breaks often; it may be used to import playlists by name. `[аналіз]`
10. Soulseek: our own protocol implementation in pure Kotlin from the Nicotine+ documentation
    (`[docs]` https://nicotine-plus.org/doc/SLSKPROTOCOL.html), with Soulseek.NET and aioslsk as
    reference code. No Kotlin or Java library exists (`[не знайдено]`, search this session). The
    client shares the library folder: users who share nothing get banned by peers.
11. Lyrics: sidecar file, then embedded tags, then LRCLIB (line-timed), then Kugou KRC
    (word-timed, what LastWave uses). Fetched lyrics are saved next to the file as `.lrc`/`.ttml`.
12. Bluetooth codec: no public API reads or sets it (`[код]` SDK 37 `android.jar`:
    `BluetoothA2dp` has no `getCodecStatus`/`setCodecConfigPreference`). The codec comes from the
    owner's Earbuds app, which asks the WF-1000XM5 over Sony's protocol, through a
    signature-protected ContentProvider (both apps are signed with the owner key).
13. Releases: tag and title `music-v<versionCode>`, APK `music-v<versionCode>.apk`, only when the
    build was signed with the owner key. Obtainium: title filter `^music-`.
14. The UI controller binds while the activity is started and releases its binding on stop.
    Playback continues in the service. A pending connection owns at most the latest requested
    queue, so tapping a song during startup is not silently lost. Position polling and output
    monitoring follow the visible page's lifecycle, including screen-off. `[код]`
15. Library scans replace the cache only after a complete successful read. A null MediaStore
    cursor is an error, not an empty library. Inject the scanner and dispatcher at the repository
    boundary for cancellation, failure and serialization tests. `[код]`
16. Android unit tests use Robolectric 4.17 on API 35 with `-PminSdk=35`; lint and APK builds run
    separately with the normal minSdk 37. Coroutines test uses the same version as runtime.
    These tests are not API 37 device evidence. No runtime dependency pins were changed. `[код]`
17. `LibraryIndex` is a pure Kotlin snapshot shared by the library pages. Album artist groups
    compilations; disc/track numbers order queues. Sort keys are computed off the main thread,
    ignoring English articles and accent marks without depending on the phone locale. Collection
    identifiers encode complete key parts; same-named albums by different artists stay separate.
18. `library.db` version 2 adds genre and an external-content Room FTS4 table over title, artist,
    album and album artist. Room triggers keep the index transactional with track changes;
    `unicode61 remove_diacritics=2` handles accents. Queries contain only quoted token prefixes,
    never raw FTS syntax, and are debounced/cancelled in the ViewModel. The rebuildable version 1
    cache is dropped on upgrade; no user data is stored in it. `[код]`
    [docs] https://www.sqlite.org/fts3.html#external_content_fts4_tables
19. Song identity drops explicit featured-artist credits, but preserves separators inside artist
    names. Splitting on `/`, `&`, commas or `and` incorrectly merged unrelated bands. Mixed
    annotations retain the recording variant after removing edition metadata (for example,
    `Live, Remastered 2011` remains `Live`). Radio edits, censored versions and mono/stereo mixes
    are also distinct. Prefer a missed match to playing the wrong recording.
    The v2 cache rescan regenerates the keys. `[код]` regression tests in `SongKeyTest`.
20. The sleep timer is owned by PlaybackService and uses an elapsed-realtime deadline, independent
    of wall-clock changes and activity recreation. A single cancellable delayed job exists only
    while armed; expiry pauses the player once. The app sends a private session command and reads
    the deadline from session extras. Service destruction cancels it because playback also stops;
    it is not persisted or implemented with an alarm, worker, or periodic UI polling. `[код]`
21. Queue metadata is prepared on Dispatchers.Default; only MediaController commands run on the
    main thread. A newer selection or disconnected UI cancels the pending build. Progress state
    lives in its own composable so a tick does not invalidate the artwork and player controls.
    These avoid work on the UI thread; actual frame-time measurements still need the phone. `[код]`

22. user.db has an exported Room schema, foreign keys and no destructive migration fallback.
    Playlist entries have unique positions; moves/removals rewrite them in one transaction.
    Metadata uses Upsert so refreshes cannot cascade-delete favourites/history/offsets.
    Room testing uses the same pinned Room version as runtime. Missing files remain visible.
23. History is recorded by PlaybackService through an application-owned persistence interface.
    Core playback does not depend on Room or the app module. Stable per-occurrence queue IDs
    distinguish repeated copies; buffering and pause/resume do not create extra history starts.

24. Play next runs inside PlaybackService, preserving the existing shuffle traversal while placing
    the inserted occurrences immediately after the live current item. Queue edits resolve occurrence
    keys against the current controller timeline, never against stale dialog indices.
25. Playback queue and position use separate versioned AtomicFiles. A shared generation prevents an
    old position from being applied to a replacement queue after an interrupted write. Queue metadata
    is rewritten only when its generation changes; active playback checkpoints position every 15 s.
    Restore is paused and unprepared. User selection wins over an asynchronous restore.
26. Audio path diagnostics report Media3 decoder and AudioTrack initialization callbacks, not an
    inferred mixer format or Bluetooth codec. Output routing is Android's MediaRouter2 switcher.
    Passive callbacks observe route changes while the player is visible; no active discovery scan.

27. PlaybackJournal is one process-owned IO actor. Reads wait for earlier final writes from a
    destroyed service; queued write bursts coalesce before the next read barrier. It suspends while
    idle and starts no service, timer, platform job or wake lock. This prevents two service lifetimes
    from racing separate AtomicFile writers. Position polling exists only while audio is playing.

28. MediaStore remains the file enumerator. FileTagScanner enriches new/changed rows with the existing
    pure Kotlin core:tags reader, off the main thread, before atomic cache replacement. Cache reuse
    requires matching id, URI, path, mtime, size and reader version. Unsupported files are remembered;
    transient IO failures retain system metadata and retry next scan. Permission loss/cancellation
    abort the scan. library.db v3 stores lossless JSON lists and supplemental metadata; user.db is
    unchanged. Album ownership remains by album artist; multiple tagged album artists and genres
    each get their own collection. Embedded artwork is stored as a validated byte range, never
    loaded during scanning. Gain/lyrics/gapless values are data for later consumers, not yet applied.

29. Player typography reuses the owner's Google Sans Flex asset (OFL retained). Icons are generated
    from official Material Symbols Rounded with scripts/player_symbols.py; Apache license retained.
    PlayerPage has no controller/route bindings, enabling real UI rendering checks. Local embedded
    lyrics parse off the UI thread and are observed by distinct text; timing offsets use user.db.
    Lyrics poll only while visible/started and playing, update the active line at 10 Hz, stop following
    on manual drag, and subtract the saved offset when seeking. This is line highlighting; word-fill
    animation, sidecars and remote lyrics remain open.

30. core:dsp is pure Kotlin for deterministic audio math and buffer processing tests. It is not
    a second playback engine. EQ integration must preserve Media3's float path and obey partial-write
    and flush contracts; architecture and review constraints are in LOCAL_PLAYER_REVIEW.md.

31. EQ wraps Media3 AudioOutputProvider, after format conversion/trimming, retaining negotiated PCM16
    or float PCM and sample rate. Disabled processing forwards the original buffer; active processing
    writes reusable direct/native buffers and accounts exactly for partial writes. Settings are
    immutable snapshots with channel-independent double-precision biquads, combined-response headroom,
    final output bounds and 10 ms changes between configurations. No platform session AudioEffect.
32. EQ settings belong to the service's observed output and are persisted per route. Commands carry
    the expected route key and are rejected on mismatch. Sony AutoEq is available only on the
    identified WF-1000XM5 route and requires explicit selection. It uses the MIT AutoEq Rtings B&K
    5128 profile, with source coefficients and attribution retained. Unknown output is neutral.
    No physical response, subjective improvement or CPU/battery claim has been measured here.

33. Library cache replacement uses a linear complete-snapshot delta inside the Room transaction.
    Only content changes are upserted; missing IDs are deleted in batches of 500. scanId is ignored
    for content equality and retained on unchanged rows. Prefix/scan generation and duplicate IDs
    are validated before writes. Empty successful snapshots remove only their own source. This
    avoids triggering FTS and rebuilding every library view merely because the app came forward.

34. ReplayGain is applied to decoded PCM before DefaultAudioSink buffers/converts/trims it.
    Scanner-normalized gains (including R128 conversion) travel in MediaItem extras, queue checkpoints
    and per-source extractor Format.customData. MediaCodecRenderer's timestamped output-format queue
    and MediaCodecAudioRenderer's customData copy associate the tags with the corresponding PCM.
    Audible item-transition listeners do not control gain. Existing extractor customData is retained
    inside the wrapper. Encoded passthrough/offload is disabled so effects cannot be silently skipped.
35. ReplayGain is opt-in and global; EQ remains per output. Album mode uses album gain/peak together,
    falling back to the track pair only when album gain is missing. Missing peaks prevent positive
    boost with clipping protection enabled; untagged/legacy queue items remain neutral. Gain settings
    changes ramp over 10 ms within a stream. Stream boundaries use the next stream's gain immediately,
    without crossfading songs. Packed PCM8/16/24/32 and float preserve format, endianness and frame size.

36. M3U8 parsing/path resolution is pure core:model logic, with an immutable preview and a lazy
    path/suffix index per library snapshot. A match is exact or a unique multi-segment relocation;
    ambiguous/missing files retain path-based import identities. Standard SavedSong matching remains
    SongKey/path-based. No file URI or network stream is opened by the resolver.
37. M3U8 imports use a single UserRepository transaction and the existing schema. Duplicate file
    references are explicitly counted/skipped because playlist_entries currently permits one song
    identity per playlist. Adding repeated occurrences requires a proper user.db migration, not
    counterfeit song identities. Missing references are persisted and exported, never silently lost.
38. Playlist document IO uses Android's user-selected documents, bounded strict UTF-8 reading and
    UTF-8 export. The import preview supplies an editable base folder when a provider hides location.
    URI encoding must preserve filesystem Unicode exactly; URI.toASCIIString NFC normalization is
    unsuitable for NFD filenames. Compose/picker and full AGP checks remain deferred by the owner.
