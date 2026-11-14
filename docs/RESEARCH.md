# Research: other players and what to take from them

verified: ні (reading and search, November 2026; nothing here was run). Re-check before relying on
a detail. Sources are linked; unlinked lines are `[аналіз]`.

## The two the owner named

**LastWave** (Clash-Projects/LastWave-native), Android. A YouTube Music client in Material 3
Expressive with a "Liquid Glass" option, bit-perfect playback, lossless add-ons, word-by-word lyrics
(since 3.3.1; 3.4.0 adds the Kugou KRC engine), downloads that carry LRCLIB `.lrc` files. `[docs]`
https://newreleases.io/project/github/Clash-Projects/LastWave-native/release/3.4.0
Take: word-timed lyrics as the default experience, Kugou as the word-timed source, lyrics saved
beside the file.

**Sonora** (sonorahq/sonora, ex nolight132/sonora), desktop (macOS, Linux, Windows). Rust with the
gpui native UI stack. Streams from Spotify (librespot), YouTube Music, Deezer, Apple Music,
Navidrome/Subsonic and plays local files in one library; adaptive themes. States it will not export
decrypted streams or bypass subscriptions. `[docs]` https://github.com/nolight132/sonora
Take: one library over many sources, and the same policy on DRM.

**Liner**: `[не знайдено]`. The owner gave Sonora as the closest known app.

## Android players worth reading before building a feature

| App | What it is best at | Read it for |
|---|---|---|
| Metrolist, OuterTune, InnerTune (YT Music clients) | InnerTube client, radio, sync with an account, caching | `:source:youtube`, queue + radio, stream cache |
| Gramophone | local playback done carefully with Media3: word-synced lyrics, SYLT parsing, audio details | lyrics formats, Media3 corner cases |
| Auxio | library indexing from tags instead of MediaStore, multi-value artists, ReplayGain, clean architecture | M1 scanner and library model |
| Booming Music, Local Player | TTML/LRC word lyrics, in-app lyric editor | lyrics editor and offset tools |
| Lotus | tag editing with TagLib, Material You | `:core:tags` |
| PixelPlayer, Rhythm | expressive player pages, artwork-driven color, widgets | M2 design ideas |
| Namida | very rich library views, video backgrounds, statistics | library screens, stats |
| Symfonium, Poweramp (closed) | reference for audio options, signal path display, DSP | AUDIO.md options to consider |
| Seeker (Android, C#/Xamarin over Soulseek.NET) | Soulseek on a phone: search UX, queues, sharing | `:core:soulseek` behaviour on mobile networks |

`[docs]` F-Droid and GitHub pages found this session: https://f-droid.org/en/packages/org.akanework.gramophone/ ,
https://f-droid.org/en/packages/com.mardous.booming , https://f-droid.org/en/packages/com.cvc953.localplayer/ ,
https://github.com/jpdillingham/Soulseek.NET , https://nicotine-plus.org/doc/SLSKPROTOCOL.html

Desktop references: foobar2000 and MusicBee (tagging, file organizing by pattern, the model for the
tag editor and the organizer), Nicotine+ and slskd (Soulseek clients and protocol notes), Apple
Music (lyrics presentation, motion artwork), Plexamp (mixes from a library).

## Feature by feature

**Word-timed lyrics.** Formats to read: LRC, enhanced LRC, TTML (Apple style, with backing vocals
and duet agents); later Kugou KRC, QQ QRC, ID3 SYLT, Lyricify Syllable. Sources in order: sidecar
file, embedded tag, LRCLIB (free, no key, line-timed), Kugou (word-timed). The parsers for the first
three exist with tests in `:core:lyrics`. Presentation to match: current line large, words fill as
they are sung, distant lines blurred, springy scroll, tap a line to seek, backing vocals small under
their line, duet parts on opposite sides, a row of dots in long gaps.

**Mix from any track.** YouTube Music has a radio for every song (the watch playlist `RDAMVM` +
videoId through the InnerTube `next` endpoint). Find the local track there by `SongKey` and length,
take the radio, then replace every item the owner has with the local file. `[аналіз]`

**Soulseek.** Search results come as files per user; show them as albums (user + folder), ranked by
format (FLAC before 320), completeness against the expected track list, free upload slot, speed and
queue length. One tap takes an album. After download: read tags, move into the library tree by
template (default `Album artist/Year - Album/NN Title.ext`), fetch cover and lyrics, rescan.
Mobile networks are behind NAT: rely on the server's indirect connection messages; a foreground
service of type dataSync runs only while transfers run. What the owner downloads and shares is his
responsibility; the client is a general Soulseek client like Nicotine+ or Seeker.

**Tag editor.** One track or a whole album at once; fields the scanner reads are the fields the
editor writes; cover pick from file or search; lookups on MusicBrainz and Discogs; rename and move
by the same template as the organizer; undo by keeping the previous values until confirmed.

**Animated covers.** Apple Music albums can carry "motion artwork": square and 3:4 looping video,
H.264/HEVC, no audio. `[docs]` https://help.apple.com/itc/albummotionguide/en.lproj/static.html
The catalog API exposes it as `editorialVideo` HLS playlists when asked with `extend=editorialVideo`
`[аналіз]`, not in the public docs found this session; unofficial, needs the web player's
token, can break. Plan: look the album up, download the square variant once, keep it as a sidecar
`cover.mp4` in the album folder, loop it muted on the player page only while visible. A sidecar file
the owner drops in works the same way without any network.

**Bluetooth codec.** See AUDIO.md: not readable by an app; comes from the Earbuds app.

## What nobody above does, and this app should

- One song, many sources, local first, by a tested identity rule (`SongKey`).
- An honest signal path: file format, codec and output, only what is known.
- Downloads that land already organized, tagged, with cover and lyrics.
- Built for one phone and one pair of earbuds, measured on them.
