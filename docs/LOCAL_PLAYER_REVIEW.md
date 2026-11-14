# Local player behavior and implementation review

Owner scope, 2026-11-14: Pixel 10 Pro XL / Tensor G5, Android 17 and newer, Sony WF-1000XM5,
Bluetooth speakers and phone speakers. Local listening before other sources. All three entry points
matter: albums/folders, shuffled library and playlists. Owner wants Sony correction and an equalizer.
Crossfade and decorative track transitions are excluded; true gapless remains required. Visualizers
are excluded. Finish functional behavior before a complete UI finish pass. Wallpaper colors only.

## One owner for each state

| State | Owner | Persistence / consumer |
|---|---|---|
| Files and fallback metadata | MediaStore enumeration | Rebuildable library.db |
| File-derived tags | core:tags, invoked by FileTagScanner on IO | Cache fingerprint and reader version |
| Browse ordering | Pure LibraryIndex | Immutable snapshot shared by UI and queue creation |
| Favourites, playlists, history, lyric offsets | UserRepository | Transactional user.db, never destructive migrations |
| Queue, cursor, playback intent | PlaybackService / ExoPlayer | Generation-matched atomic checkpoints |
| Audible decoding and output | PlayerFactory / Media3 | One session and audio sink |
| Route selection and device volume | Android system media routing | Output picker and hardware media-volume keys |
| UI state | ViewModel and Compose | Lifecycle-bound subscriptions; never a second player |
| Colors | Android wallpaper palette | AppTheme; album artwork cannot override it |

## What happens when the owner presses Play

1. Album/folder/playlist/list entry produces a specific ordered list and starting index. Metadata
   construction runs off the UI thread. A later explicit selection cancels an earlier pending build.
2. MediaController addresses the service. The service resolves the playable URI and owns the queue.
   Unique occurrence keys keep two copies of the same song distinct. Play next uses the live cursor
   and shuffle traversal. A dialog never edits a stale numeric queue index.
3. Media3 decodes the file, handles trimming/focus and hands PCM to its audio sink. Existing float
   output preserves high-resolution PCM where supported. Ordinary AudioProcessor chains are skipped
   by the pinned Media3 sink on its float path; attaching EQ there would silently omit it for hi-res.
4. Android mixes/routes output and negotiates Bluetooth. AudioTrack configuration is observable;
   the app cannot infer actual LDAC bitrate or final mixer format from the source file's tags.
5. The service records an audible playback start, not just a click or pause/resume. Playback checkpoint
   writes run on one IO journal; reopening restores paused, and a new selection wins over disk restore.

## Next audio block: implementation constraints

- EQ is opt-in and bypass must preserve original samples. Sony correction is a measured starting
  point, not an individual ear measurement. Its provenance and preamp must be visible.
- Keep per-output configurations. Sony correction must never silently carry over to a phone speaker
  or unrelated Bluetooth speaker. Unknown routes use a neutral default; do not guess a headphone model.
- Do not reduce high-resolution PCM to 16 bits to make a standard processor chain run. Evaluate the
  Media3 AudioOutputProvider boundary, after its format conversion and gapless handling. A wrapper
  must obey direct/native-order buffers, read-only input, partial writes, timestamps and flush/reset.
- Allocate reusable output buffers at buffer boundaries, never per sample. Filter state is independent
  per channel. Use double-precision biquad coefficients/state, with float PCM preserved at the output.
- A configuration change needs a short parameter transition, not a discontinuous state reset/click.
  This is unrelated to crossfading songs. Flush/seek clears buffered samples and filter state.
- Compute gain headroom from the actual combined filter response and retain an explicit final output
  bound. The initial pure DSP scaffold conservatively sums positive band gains; refine this before
  wiring a usable Sony profile, because excessive attenuation is not acceptable daily behavior.
- ReplayGain must be associated with the decoded item's samples, including prebuffered transitions.
  A listener on audible media-item changes alone can apply gain to the wrong buffered samples.
  Do not ship such an approximation. Track/album modes and clipping guard require their own tests.
- Audio focus, calls and route loss continue through Media3/Android. Do not force SCO, codec settings,
  volume boosts, or a guessed Tensor-specific tuning. Actual quality/battery needs target measurements.

## Functional delivery order

1. Complete EQ plus optional measured WF-1000XM5 correction, with format-preserving buffer tests,
   analytic and sine-response tests, route isolation and neutral bypass.
2. ReplayGain/R128 with correct per-item/album behavior and peak handling; verify gapless with real
   FLAC/MP3/Opus albums. Extend codecs only when a real library file requires them.
3. Durable personal-data export/restore, playlist m3u8 interoperability, sorting/multiselect and
   efficient album/folder/library playback. Preserve missing-file entries and report unresolved imports.
4. Offline sidecar lyrics/artwork, then queue drag editing and user-requested useful home-screen control.
5. Review every visible state for fit: empty/loading/error, permissions, long titles, large fonts,
   unavailable files, all modal dialogs, all outputs, light/dark, split view and background return.
6. Measure actual route handover, Sony controls, calls, screen-off stability, battery, scan/start times,
   UI frame times and mixer/codec behavior on the owner's phone. A parity claim requires evidence.

## Evidence and current limits

[code] Main `9c7dd00` contains verified file-tag integration, new player surface and embedded lyrics.
Code head `85d7748` passed CI 43638096372; earlier artifact XML contained 132 distinct passing tests.
Player light/dark/font2 component previews were inspected; physical quality gates remain pending.
Main release run 43638695765 is in progress at this checkpoint (expected music-v1025).

[code] New core:dsp scaffolding on `agent/m3-equalizer` is not connected to audio yet. Never describe
its presence as a working equalizer. Pure tests cover analytic frequency response, measured sine
attenuation, channel isolation, exact bypass and finite output over several sample rates.

[docs] Media3 1.11.1 sources: DefaultAudioSink.configure, AudioOutput.write, AudioOutputProvider.
[docs] AutoEq candidate Sony profile: results/Rtings/Bruel & Kjaer 5128 in-ear/Sony WF-1000XM5/
Sony WF-1000XM5 ParametricEQ.txt in https://github.com/jaakkopasanen/AutoEq (MIT).
The candidate profile was retrieved for review; no runtime profile is wired in this checkpoint.
