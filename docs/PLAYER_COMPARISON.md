# Local player comparison and implementation audit

Date: 2026-11-15. Target: Pixel 10 Pro XL, Android 17, Sony WF-1000XM5.
Owner scope: local music first. Network libraries are deferred and are not a current deficit.
Crossfade, visualizers and personal-data backup/export are excluded by the owner.

## Evidence and interpretation

[code] Music columns below come from the current checkout, including this session's incremental
cache change. Existing HANDOFF CI results are historical reports, not rerun evidence.
This session executed 79 JVM tests using Kotlin 2.2.21 and JUnit 4.13.2. The actual library sources, Room-generated
DAO/database and library test sources also compiled in a standalone JVM harness against Android 17
API classes. Five repository tests plus six overlapping delta tests passed there. SQLite execution,
production AGP compilation, lint and APK builds remain unverified: the Robolectric native runtime
redirect is blocked. See HANDOFF.md for exact commands and limitations.

[analysis] Competitor columns describe established product capabilities as reference requirements,
not a fresh inspection or a measured comparison of installed versions. Poweramp Player is the
reference, not the separately sold Poweramp Equalizer. Neither competitor was installed or tested
on the owner's phone. Version-dependent details are explicitly left to verify.

Official product references for a subsequent documentation check:
https://powerampapp.com/ , https://forum.powerampapp.com/ ,
https://symfonium.app/ , https://support.symfonium.app/ .
Current requests to the two product sites and GitHub API are blocked by this environment's
network policy. Links alone are not evidence that a page was read.

Quality labels are implementation descriptions, not numerical sound-quality scores:
- Present: an end-to-end code path exists; physical acceptance can still be missing.
- Partial: a usable subset exists, or only a parser/model exists without the full feature.
- Missing: no implemented end-to-end feature was found in the inspected modules.
- Unmeasured: the behavior requires target-device evidence.

## Library and daily operation

| Capability | Poweramp reference | Symfonium reference | Music status and quality |
|---|---|---|---|
| Local offline playback | Present | Present | Present: Media3 and local content URIs; phone acceptance pending |
| Albums, artists, genres, folders | Present | Present | Present: shared immutable index and deterministic ordering |
| Multiple credited artists/genres | Present; field rules vary | Present; field rules vary | Present: file tags preserved; parser fixtures tested |
| Album artist and disc/track ordering | Present | Present | Present: model rules tested |
| Search | Present | Present | Present: SQLite FTS, literal token prefixes, accent handling; current integration suite unrun |
| Chosen filesystem roots | Present | Present | Missing: MediaStore music enumeration, no independent root walker |
| Incremental tag reading | Present | Present | Present: mtime, size, URI/path and reader-version fingerprint |
| Incremental database writes | Verify implementation | Verify implementation | New: linear snapshot delta, no unchanged-row writes; JVM tests pass, Room tests pending |
| Automatic library updates | Present | Present | Present: visible-activity observer, debounced rescans |
| Ten-thousand-track scan speed | Requires measurement | Requires measurement | Unmeasured; synthetic delta tests are not scan benchmarks |
| Embedded artwork | Present | Present | Partial: embedded picture ranges read; full fetch/decode/cache pipeline missing |
| Folder cover/folder artwork | Present | Present | Missing dedicated lookup and three-size disk cache |
| Manual playlists | Present | Present | Present: durable transactional storage, add/remove/reorder/rename |
| Missing playlist files retained | Verify current behavior | Verify current behavior | Present: durable song records survive disappearance from library |
| Duplicate playlist occurrences | Verify current behavior | Verify current behavior | Missing: playlist additions deduplicate saved song identity; queue occurrences are separate |
| M3U/M3U8 interoperability | Present | Present | Implemented on work branch: UTF-8 import/export, per-line preview, unique path resolution and missing-file retention; pure/Room tests pass, Android picker/UI pending |
| Smart playlists | Version-dependent category/rule features | Present | Missing rule engine and UI |
| Favourites | Present through ratings/library features | Present | Present |
| Star ratings | Present | Present | Missing |
| Listening history | Present | Present | Present: audible-start history; UI resolves recent distinct songs |
| Play-count statistics | Present | Present | Partial: history events exist; no complete count/statistics interface |
| Recently added | Present | Present | Missing dedicated persisted/import-date view |
| Sort selection | Present | Present | Implemented on work branch: persistent per-scope track choices, off-main sorting, visible-order queues; pure tests pass, Compose checks pending |
| Multiselect/batch actions | Present | Present | Implemented on work branch for library/search/collection tracks: play, next, queue, playlist; visible order and 20000-item bound, pure/bulk-command tests pass; Compose and rotation refinement pending |
| Tag editing | Present | Verify supported fields/write operations | Missing; tag reader is not a writer |
| CUE splitting | Present | Verify current support | Missing |

## Audio and output behavior

| Capability | Poweramp reference | Symfonium reference | Music status and quality |
|---|---|---|---|
| Common local codecs | Broad | Broad | Platform decoders; FLAC/MP3/AAC/Opus etc. real-file coverage pending |
| ALAC/APE/WavPack/DSD edge cases | Broad; output can convert | Verify formats and decoder versions | No extension decoder pack; parsing M4A tags does not prove ALAC playback |
| High-resolution output | Hardware/firmware dependent | Hardware/firmware dependent | Float output enabled; actual decoder/mixer depth and rate unmeasured |
| Equalizer | Mature audio controls | Parametric EQ/AutoEq | Present: custom float/PCM16 DSP, channel isolation and response/buffer tests |
| Parametric frequency/Q editing | Verify Player version; separate EQ app differs | Present | Implemented on work branch: frequency/Q/type editor; Compose checks pending |
| WF-1000XM5 correction | Headphone preset support varies | AutoEq support | Present: retained measured-reference AutoEq profile, opt-in; not individual-ear calibration |
| External EQ profile import | Verify current Player formats | Present | Implemented on work branch: bounded, strict AutoEq TXT import; parser tests pass, Android picker checks pending |
| Per-output EQ | Present | Present | Present with caveat: model/name-based route key, not unique hardware identity |
| Neutral processing bypass | Verify exact sample behavior | Verify exact sample behavior | Present: original buffer forwarded with EQ disabled; JVM buffer tests pass |
| EQ headroom | Present; controls differ | Present; controls differ | Present: combined-response bound plus margin and final clipping guard |
| ReplayGain track/album | Present | Present | Implemented on work branch: sample-associated PCM gain, modes/preamp/peak guard; focused tests pass, phone/UI checks pending |
| ReplayGain clipping prevention | Present | Present | Implemented on work branch: ReplayGain peak guard, conservative unknown-peak behavior; separate from EQ headroom |
| Gapless | Present | Present | Delegated to Media3; real FLAC/MP3/Opus boundary verification missing |
| DSP setting transitions | Present; implementation private | Present; implementation private | Present: 10 ms transition and partial-write/flush contracts; physical checks missing |
| USB bit-perfect mode | Verify device/path and definition | Verify device/path and definition | Missing preferred-mixer implementation and DAC validation |
| Bluetooth bit-perfect | Bluetooth codec is lossy/re-encoded | Bluetooth codec is lossy/re-encoded | Not a valid Bluetooth goal; no app can promise source-bit identity through LDAC |
| Actual LDAC codec/bitrate display | Verify reported information | Verify reported information | Missing Earbuds provider integration; no guessed LDAC/990 badge |
| Audio-path diagnostics | Present | Present | Present: source, decoder and AudioTrack configuration; not final mixer/link measurement |
| System output selection | Present | Present | Present: Android output switcher, no forced SCO |
| Focus/calls/output loss | Present; phone test required | Present; phone test required | Configured through Media3; Sony/call/route physical matrix pending |
| Resume-on-connect rules | Present | Present | Missing user-configurable rules |
| Per-output volume | Present; behavior varies | Verify current behavior | System media volume only; no app-specific remembered volume |
| Audio offload | Device/path dependent | Device/path dependent | Not enabled as an optimization; needs DSP compatibility and battery evidence |

## Queue, lyrics, interface and system

| Capability | Poweramp reference | Symfonium reference | Music status and quality |
|---|---|---|---|
| Shuffle/repeat/play next | Present | Present | Present; live queue commands and occurrence identity |
| Queue remove/reorder | Present | Present | Present via controls; drag gesture missing |
| Queue/position recovery | Present | Present | Present: atomic checkpoint generation, serialized writer, restores paused |
| Sleep timer | Present | Present | Present; service-owned deadline, no per-second timer |
| Plain embedded lyrics | Present | Present | Present |
| Line-synced lyrics | Version/source dependent | Present | Present for embedded LRC; offset, follow toggle, line seeking |
| Word-synced lyrics | Verify current Player version | Verify current format/display support | Partial: enhanced LRC/TTML parsers tested; UI highlights lines, not word fill |
| Sidecar LRC | Present | Present | Implemented on work branch: scoped folder grants, LRC/TTML/TXT lookup, source notices, embedded fallback/reload; 4 storage tests and main source compile pass, Android picker/device pending |
| KRC/QRC/SYLT | Verify each format | Verify each format | Missing parsers/integration |
| Lyric editor | Verify scope | Verify scope | Partial: per-song offset only, no line/word timing editor |
| Online lyric search | Source/plugin dependent | Source dependent | Missing; optional separate from deferred network libraries |
| Notification/lock-screen controls | Present | Present | Media3 session implemented; phone verification pending |
| Sony buttons/wear detection | Phone/earbud dependent | Phone/earbud dependent | Media session supports commands; physical checks pending |
| Android Auto browse/search | Present | Present | Missing library callbacks and DHU validation; service class alone is insufficient |
| Home-screen widgets | Present | Present | Missing |
| Wallpaper-derived UI | Customizable, theme dependent | Customizable, theme dependent | Present: Material You palette, Google Sans Flex, Material Symbols |
| Full design/accessibility coverage | Requires review | Requires review | Partial: component tests/history; complete states, goldens and accessibility gates missing |
| 120 Hz frame stability | Requires phone measurement | Requires phone measurement | Unmeasured |
| Cold start/baseline profile | Requires phone measurement | Requires phone measurement | No generated target baseline profile or measured startup |
| Screen-off battery | Requires matched measurement | Requires matched measurement | Unmeasured; no improvement percentage can be claimed |
| Idle/background behavior | Requires phone measurement | Requires phone measurement | Pollers/checkpoints lifecycle-bound in code; service/wakelock idle audit pending |
| Update delivery/data retention | Store channel | Store channel | Obtainium/owner-signed CI workflow; separate user.db with real migrations |
| Private local operation | Local-first product | Local use supported | No app accounts, backend or analytics; no measured competitor privacy ranking |

## What should be done next

1. Validate this branch's Room tests, both lints and R8 APK builds; do not merge unverified code.
2. Run target-device acceptance once: normal playback, 24/96 AudioTrack/mixer, a gapless album,
   Sony controls, calls, output loss and a long screen-off session. Record firmware and files.
3. Implement sample-associated ReplayGain/R128 with album/track modes, preamp and peak guard.
   Audible item-transition callbacks alone are unsafe with prebuffered samples.
4. Complete artwork, selected roots, M3U8 and batch/sort workflows. These are daily-use deficits.
5. Complete offline sidecar lyrics and word-timed display; add widgets/Android Auto if wanted.
6. Generate the baseline profile and measure startup, scan, 120 Hz frames and battery against
   Poweramp and Symfonium under the same files, gain, EQ, output codec, screen and radio conditions.

[analysis] Music currently has a focused foundation and tested pure logic, not demonstrated overall
parity. Specializing for one phone reduces scope; it does not by itself prove faster execution,
better sound or lower battery consumption. No Tensor-specific native loop or codec forcing should
be added without a measured bottleneck and a reproducible comparison.
