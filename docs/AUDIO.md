# The sound path on this phone

Target: Pixel 10 Pro XL (Tensor G5, Android 17) and Sony WF-1000XM5. Nothing here is measured yet
unless it says so; "to measure" items are tasks in ROADMAP M3 with the command to run.

## What the hardware can do

- WF-1000XM5 codecs: SBC, AAC, LDAC, LC3 (LE Audio). No aptX of any kind. `[аналіз]`
- LDAC supports modes up to 990 kbps and source formats up to 24-bit/96 kHz. Actual negotiated
  mode and radio stability must be measured. AAC and LE Audio LC3 can also carry music; codec
  names alone do not establish audible quality. Do not force a codec or bitrate from this app. `[аналіз]`
- Tensor G5 is not a Qualcomm chip. Everything branded Snapdragon Sound, aptX Adaptive or aptX
  Lossless does not exist on this phone and must not be designed for. `[аналіз]`
- The earbuds only use LDAC when "Bluetooth quality: priority on sound quality" is set on them. The
  Earbuds app sets and keeps it (`preferQuality` in `DKurocl/xm5`). `[код]`

## What Android does between the app and the earbuds

- Bluetooth is never bit-perfect. The app's PCM goes to the system mixer, is resampled once to the
  A2DP rate, then encoded. The job of the app is to hand over the file's own sample rate and full
  bit depth and add no processing of its own. `[аналіз]`
- The LDAC rate and bitrate ("Best effort", 660, 990; 44.1 to 96 kHz) are chosen by the system and
  Developer options. No public API reads or sets them: `BluetoothA2dp.getCodecStatus` and
  `setCodecConfigPreference` are not in the SDK 37 `android.jar`. `[код]` An AOSP change once made
  them public for apps with a companion-device association; it is not in the shipped SDK. `[docs]`
  https://android.googlesource.com/platform/packages/modules/Bluetooth/ (search this session)
- Hence decision 12: the codec name shown in the player comes from the Earbuds app.

## What the app does (PlayerFactory)

| Choice | Why | Status |
|---|---|---|
| Float output on | ExoPlayer otherwise cuts 24-bit/float PCM to 16 bits | set, `[docs]`; to measure |
| Audio attributes MEDIA/MUSIC, focus handled | calls pause, prompts duck | set |
| Pause when output goes away | never play on through the speaker | set |
| Wake lock while playing | screen-off playback of local files | set |
| No speed/pitch/silence processors | nothing between decoder and mixer | set (float output skips them) |
| Gapless | ExoPlayer reads encoder delay/padding (MP3 LAME, M4A iTunSMPB, FLAC/Opus natively) | to verify with a gapless album |
| ReplayGain / R128 | album and track gain from tags, applied as one volume step, never clipping | M3 |
| Audio offload for lossy files with the screen off | lower power on long listening; FLAC decode is cheap anyway | M3, measure battery first |
| USB DAC bit-perfect | `AudioManager.setPreferredMixerAttributes` with `MIXER_BEHAVIOR_BIT_PERFECT` (API 34+) | M3, needs a DAC |
| Formats the platform lacks (ALAC, APE, WavPack, DSD) | an FFmpeg extension renderer | M3, pick a maintained prebuilt, check 16 KB pages |

## To measure on the phone (M3)

1. Bit depth reaching the mixer for a 24/96 FLAC:
   `adb shell dumpsys media.audio_flinger | grep -A12 "Output thread"` while playing; look at the
   track's format (`PCM_FLOAT` expected) and the thread's sample rate. If the track is 16-bit, the
   platform FLAC decoder was not asked for more: fix in the renderer or switch FLAC to the extension.
2. What the link carries: `adb shell dumpsys bluetooth_manager | grep -A25 "A2DP Codec"` shows the
   codec, sample rate, bits and LDAC quality in use. Record it in HANDOFF with the firmware version.
3. Resampling: play 44.1 kHz and 96 kHz files and note the A2DP rate each time. If the link stays at
   96 kHz, 44.1 kHz material is resampled by the system; decide then whether to offer "match the
   link to the file" guidance (Developer options) in the signal-path sheet.
4. Battery: one hour of FLAC and one hour of MP3 with the screen off, offload off and on
   (`adb shell dumpsys batterystats --charged ua.olex.music`).

## Signal path in the UI

The player page shows what is known, left to right: the file (`FLAC 24/96`), then the output
(`WF-1000XM5`), and between them the codec once the Earbuds provider exists (`LDAC`). It never shows
a guess: an unknown part is absent, not "probably LDAC".

## Performance on Tensor G5

- arm64-v8a only; R8 full mode; resources shrunk. `[код]` app/build.gradle.kts
- Baseline profile generated on the phone (M9); do not commit a hand-written one.
- Any native library must be 16 KB page aligned (`llvm-readelf -l lib.so`, LOAD alignment 0x4000).
- 120 Hz: animate in the draw phase (`graphicsLayer`, `drawWithCache`), not by recomposing. The
  lyrics page reads the player position per frame and draws; it must not recompose per frame.
- No idle loops: a paused player, a hidden page or a dozing phone runs nothing (the position
  poller, the motion-artwork video and the lyrics clock all stop).

## Implemented output controls and diagnostics

[код] The Output action opens Android's system media output switcher for phone speakers, paired
Bluetooth speakers and headphones. Hardware volume keys address the media stream. There is no
forced SCO/communication route, automatic volume boost, or guessed device-specific EQ. Network
casting protocols are not implemented by this action.

The Audio path sheet reports decoder initialization and the app AudioTrack's actual configured
encoding, sample rate and channel count from Media3 analytics. These are runtime callbacks, not
proof of the system mixer's output, the Bluetooth link bitrate, or bit-perfect playback. No 990
kbps/LDAC badge is shown without evidence. Physical validation remains required.

[docs] https://developer.android.com/reference/android/media/MediaRouter2#showSystemOutputSwitcher()
[docs] https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener

## Optional EQ and Sony correction (CI verified; phone measurements pending)

EQ is off by default and separate for each observed output. Sony WF-1000XM5 correction uses the
AutoEq result from Rtings B&K 5128 measurements; exact source coefficients are retained in
scripts/assets/sony-wf1000xm5-autoeq.txt. This is a starting profile, not a personal ear measurement.
Disable Sony's own EQ to avoid double correction. Fit, tips, ANC and firmware can alter response.

The audio-output wrapper retains the sink's encoding/rate: high-resolution float is not converted
to 16-bit to run the EQ. Standard PCM16 is processed internally in double precision and returned
in the negotiated PCM16 format. Processing bypass forwards original samples; active settings
reserve headroom and change over 10 ms. Buffer protocol and math are tested separately from device
measurements. ReplayGain is still not applied. Offload remains disabled; a future offload feature
must account for effects rather than silently bypass them.

Phone acceptance: compare bypass/flat/Sony with matched listening volume; confirm output changes
restore the correct profile; inspect audio_flinger/AudioTrack encoding with 24/96 files; test
seek/flush, pauses/calls, gapless albums and screen-off battery. No audible-quality or battery
improvement is claimed until those are measured.
