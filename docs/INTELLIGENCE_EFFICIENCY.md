# Intelligence and efficiency execution ledger

Assignment: [M10-M140](specs/MUSIC_INTELLIGENCE_EXTREME_EFFICIENCY_M10_M140.md).
Physical acceptance is consolidated at the end. A source review, host test, GitHub Actions
test and physical Pixel observation are separate evidence.

## Checkpoint

| Item | Evidence |
| --- | --- |
| Stable baseline | `5655cb2c4ab76ba06b5a3696180a8c73417bb462`, music-v1307. |
| Core candidate | Local `9c4ec5113168499d8dc56932ae010444a55859d4`, GitHub `43afa2b2d905de4dec96e1070daa4a42c76a58b8`, identical source tree `c35f08731903e6da44cd74c344d3cbb897a3f9a9`. Earlier candidate `75dfb09` passed 579 tests, both lints and both APK builds. |
| Core verification | Run `45694261300` attempt 1 failed on Maven Central HTTP 429 fetching Robolectric's API 35 Android artifact. Attempt 2 passed all 579 tests, both lints and both APK builds on the same head. Signed APK artifact `15075198872`, version code 1311, APK SHA-256 `806033cbc613b4833472a0ac257868f372e0f7096692a1118704a3549bd06b86`. Downloaded archive and APK SHA-256 matched. Stable release job skipped. |
| Current implementation | Local branch `agent/intelligence-efficiency`. Resource and offline tail `d7f5b5b`, teacher `6e671fb`, observation `eb49335`, publication `da7e9ee`, resource revisions `8448c30`, artwork `f9a5134`, transfers `e29753a`, source index `a2dbd58` and lyrics `a197273`. Subsequent verified batches are detailed below and in Git. |
| Host evidence | Focused suites passed: 69 source/index/matching/session cases, 49 provider cases, 26 resource/ranking/availability cases, 25 lyrics/parser cases, 21 tag/publication cases and five loopback/wire cases. Suites overlap. Build-tools 37 independently verified the signed core candidate's owner certificate and 16 KB alignment. SDK 37 production compilation passed. |
| Android JVM evidence | Local Robolectric API 35 fixture batches passed 27 Room user-repository cases, 16 playback cases and 23 app cases across two runs. The initial app pass exposed a fixture missing the required LRCLIB ID. The corrected retry and removal cases and the remaining four downloaded-lyrics cases passed. |
| Pending verification | Full Android lifecycle matrix, physical provider and audio fault injection, combined Android CI, a signed full-scope candidate and owner Pixel acceptance before stable release. |
| Exact next action | Complete owner-key signing and hosted candidate verification when the local branch is ready for publication. Preserve the deferred optional boundaries below. |

## Phase A and offline continuation

| Milestones | State and current evidence |
| --- | --- |
| IE-M10 | Inventory started at [energy-owners.tsv](energy-owners.tsv). Whole-source and nested owner reconciliation is open. |
| IE-M11/M14/M16/M17/M123 | Pure automatic-work decision table, Battery Saver and low-power gating, 10/13 and 25/28 hysteresis, validated unmetered networking, severe/unknown fail-closed behavior. Power, screen, visibility and network revisions cancel a lease and reject pending radio/teacher commits, including an observed denial followed by fast re-admission. Android lifecycle integration and other optional commit paths remain open. |
| IE-M12/M13 | Index, catalogue and default-network monitors follow subscribers. A single foreground MediaStore observer rechecks on policy re-entry. Home loads and controller UI publications stop with the Activity. Lock-screen lyrics clear on screen-off and use a separate interactive network lease for a cache miss. Service source observers stop for an empty queue or hidden pause. Idle controller unbinding, artwork and Compose frame measurements are open. |
| IE-M15/M18-M29 | No continuous learner or new background scheduler. Periodic progress and listening share the 60-second playback checkpoint. Radio metadata obeys a bounded Retry-After with no scheduled retry, and continuation IO/timeout falls back to local tracks. Consent, charging loss, large workload admission, wake-up/IO traces and the full energy gates are open. |
| IE-M40/IE-M41/M50 | Current radio follows provider rank through curated playlists and filters/artist spacing through regular mixes. Existing listening writes measure audible wall time with monotonic timestamps and a per-occurrence gate. An exact, recent Next command and matching seek transition are required for a deliberate early skip. Two distinct early skips permit a small decaying session penalty for the recording alone. Focus, error, route loss, repeat, autoplay and the file/removal advance paths do not train a skip. Five session-affinity and three command classification tests passed. Media3/controller device sequences, durable outcomes and replay evaluation remain open. |
| IE-M43-M47/IE-M58/M59 | Natural first radio pages with confirmed seed/video addresses train a bounded rank graph. Public and currently active personal namespaces use separate versioned atomic snapshots. Captures are foreground-policy-bound, single-flight and deduplicate recent response fingerprints. Account generations reject expired work even if the original scope returns. Generic Home has no confirmed seed. Durable failure injection remains open. |
| IE-M60-M63/IE-M65 | Existing regular radio can append a deterministic local tail when provider work is denied, offline or exhausted. Existing sorted artist and genre collections contribute up to 128 candidates and the fallback samples at most 384 catalogue entries. The service filters unavailable files and preserves provider cursor on a denial. Teacher edges boost only exact persisted provider/file links on canonical recordings. Curated and manual queues retain order. Availability revision, repeatability and device coverage are open. |
| IE-M70-M72 | Synthetic chronological fixture excludes later exposure from training, ignores automatic plays as labels and compares metadata, popularity, teacher and hybrid rankers. Recall@10 / NDCG@10: metadata 0/0, popularity 0/0, teacher 1/1, hybrid 1/0.75 on the 160-track synthetic library. Every arm returns eligible tracks with zero duplicates. This deliberately constructed fixture demonstrates metric and exclusion behavior. Owner history, historic file availability and real relevance gains are open. |
| IE-M75 | Refill validation retains occurrence keys and moves large timeline inspection off the player thread. A new occurrence cancels pending refill for the old seed. Shuffle/manual-race and device acceptance remain open. |
| IE-M81/M86/M106 | Pending downloads verify bytes and recognized codecs before publication. Unknown pending state preserves the checkpoint. Invalid completed data is removed before it becomes eligible. Tag copy handles zero transfer progress and short writes. Cancellation stops later batch entries. Legacy header coverage and physical Android provider failure injection remain open. |
| IE-M18/M22/M38/M93/M118/M122/M125 | Artist photos and biographies use cancellable requests and bounded memory. Provider errors leave no month-long miss marker. Artist, motion and automatic Coil network misses share two auxiliary permits. Coil keeps ordinary memory and disk hits. System artwork shares a snapshot with the widget. Large player artwork cancels when the Activity stops. Three Android JVM cases cover hidden admission, revocation, and rejected thermal listener registration. Physical frame, I/O and workload measurements remain open. |
| IE-M12/M86/M106/M108/M118 | The last explicit transfer closes its Soulseek connection without an idle timer. One authenticated connection owns peer sockets and reply waiters; stale readers cannot replace a new connection's state. Wire and inflated result sizes are bounded and login cancellation closes a blocked socket. Five loopback/wire cases passed on the host; Android transfer lifecycle and device energy remain open. |
| IE-M40-M42/M48/M50/M111/M112/M115 | Playback binds only the selected file through the unchanged matcher and shares the existing SongKey index; a 50k warm fixture detects any repeated whole-library traversal. Catalogue projection shares its bindings and computes the transaction input off the Room writer. Cache span events affect source refresh only while streaming is unavailable. Visible-resource session evidence uses audible time, expiry and shrinkage without a new observer or durable skip write. Durable preference aggregation remains open. |
| IE-M67/M90/M95/M106/M107 | Artist and lyric lookups share cancellable bounded metadata transport. Transient errors leave future lookup available. Offline lyrics keep exact-recording timing precedence; staged writes keep the previous file until the replacement succeeds. Removal rejects a late lookup, and KRC decoding rejects incomplete or overflowing timing data. Android storage races remain pending. |
| IE-M111/M112 | The unsorted bulk Room recording snapshot returns the same row set and leaves canonical winner ordering to the identity matcher. In host SQLite 3.53.1 with the exported v5 schema and 50,000 synthetic rows, median full row-fetch time over ten alternating samples changed from 82.12 ms to 59.44 ms after removing a temporary sort. This excludes Room relation mapping, disk access and Pixel behavior. All 27 Room repository fixtures passed. |
| IE-M19/M20/M86 | First-party code sets one Media3 local wake mode and has no explicit lock acquisition, Wi-Fi lock or boot-triggered transfer. The explicit data-sync transfer service declines a restart with an empty command and retains interrupted state for the next foreground owner. Its null-command Robolectric fixture passed. Real foreground service and wake-lock accounting remain open. |
| IE-M24/M28/M94/M95 | Cache eviction stops when the provider makes no deletion progress and preserves byte accounting. A manual motion cover publishes under an owner-specific filename in a single rename. Old marker-protected covers stay readable, and denied or superseded automatic fetches cannot replace them. Five cache and two motion fixtures passed. Full storage and physical crash recovery are open. |
| IE-M25/M26/M47/M96/M107/M116/M118 | Home stops on a failed or repeated continuation and ties state to the account generation. Playback-history reporting uses the same generation for both requests and only forwards account headers to the recognized HTTPS YouTube playback endpoint. Catalogue replies stop at 8 MiB and shared JSON rejects nesting beyond 128. Forty-one provider JVM fixtures passed. Default-network handover now cancels old leases even when both networks report equal capabilities, with two network, four lease and one stream-availability fixture passing. |
| IE-M26/M47/M95/M96/M116/M123 | Personal playlists and likes reject replies and queued edits from expired account revisions, including same-account reauthentication. Personal pagination has a page limit and rejects loops; an incomplete liked playlist cannot remove local favourites. All 47 provider and six pagination fixtures passed. A live account switch and device checks remain open. |
| IE-M27/M67/M95/M106/M108/M109/M116/M118/M122/M123 | A live waiter takes over a shared page when the speculative owner cancels. Ordinary failures remain coalesced. Generic playlist pagination follows Activity visibility and the account revision, stops after 100 continuations or 20,000 occurrences, and records a partial prefix without overwriting the last complete offline snapshot. Online completion is visible to the next offline reopen. A targeted pass covered 48 provider, eight pagination and three disk-cache cases. Same-account reauthentication exposed an incorrectly complete first page before the regression passed. Physical lifecycle remains open. |
| IE-M27/M113/M116 | Raw provider text has a shared retention ceiling of 4,194,304 characters across the session cache. Large replies are usable during the active request, and text eviction preserves typed playlist continuations. Fifty provider and eight pagination cases passed, plus production SDK 37 Kotlin compilation. Android process, graphics and native heap profiles remain open. |
| IE-M47/M95/M109/M111/M114 | Remote favourite reconciliation replaces repeated canonical and timestamp lookups with bounded batches. A 430-recording removal fixture observes two alias queries, two timestamp queries and two deletes. Another fixture covers shared video ownership and locally re-added favourites. Account revocation rolls back the transaction. All 31 Room repository fixtures passed after the runtime was restored. |
| IE-M57/M58/M60/M114/M120 | Cold offline radio requests durable statistics only for its bounded local candidate sample. The indexed lookup uses a 250 ms deadline, respects recording aliases and refuses ambiguous legacy keys. Precise cold lookup, warm no-query, and candidate-budget cases passed. Counts: 32 Room repository cases and 20 offline tail, mix and replay cases. |
| IE-M92/M130 | Debug service diagnostics report aggregate cached bytes actually read and cache bypass counts, both reset on service release. A Media3 cache fixture verifies a partial read and bypass. Seven cache cases passed. Prefetch waste, hit rates by policy and physical effect remain open. |
| IE-M127 | Natural teacher capture is single-flight, examines at most 128 response rows and writes at most 128 KiB per namespace snapshot. It uses a foreground provider lease and requests no additional network data. The separate charging-learning policy requires consent and rejects unplugged, metered and thermally restricted cases. No heavy charging job is registered. Per-batch physical CPU/I/O costs and learning effectiveness remain open. |
| IE-M26/M96/M106/M111/M118/M123 | Artist browse rejects an expired account revision before publishing or making a fallback request. A full artist expansion stops after 100 pages and bounds result lists. Interrupted or repeated pages stay partial. Fifty-two provider and eight pagination fixtures passed, including same-scope reauthentication and a never-ending shelf. Host SQLite query planning also confirmed the cold taste query searches recording, alias, history, rating and favourite indexes. |
| IE-M83 | Existing durable recording and source rows preserve aliases and reject conflicting MusicBrainz recording identity, conflicting lengths and overlapping CUE boundaries. The earlier 13 recording-source fixtures include distinct takes and explicit collaboration credits. Artist/release identity is carried through existing metadata and provider credits. A separate verified artist/release relation and migration are still open. |
| IE-M38/M122/M123 | A speculative provider read follows both the account revision and the foreground resource lease. A same-scope reauthentication cancels the admitted read. Hiding the Activity likewise cancels an admitted remote cover. Three Android artwork/transport and four resource-lease cases passed. |
| IE-M26/M66/M104/M109/M118/M122 | The Library pane formerly subscribed to remote Search flows. Local and provider queries now follow the Search pane, and visible/network/account ownership cancels outdated provider work. Suggestions follow optional resource admission. The latest displayed-source projection cancels its predecessor and preserves each provider occurrence's intended source. Two search-adapter, 11 Room search and 49 provider cases passed. Physical pane transitions remain open. |
| IE-M60-M62/M97/M120 | The service previously had no local recommendation catalogue after a cold restart if the Activity had never observed it. An eligible offline refill now shares the visible Room-backed index within a bounded deadline. A native SQLite Android JVM fixture reopens the stored library, excludes hidden folders, performs no provider request and rejects the warm index after permission loss. Eleven mix and four ranking cases passed. Cold physical timing and broader integrated fault injection remain open. |
| IE-M63/M74/M97/M132 | A fake naturally seeded provider page is parsed and verified, persisted by the actual model store, then read by a new graph through production offline radio and verified source projection. The fixture changes eligible files and opens an unseen seed, retains the unconsumed provider cursor and fails on any offline provider call. Twelve mix and two store cases passed. The fixture injects the capture publication and uses synthetic eligible files. Live policy transitions, device playback and owner relevance remain open. |
| IE-M26/M106/M118 | Stream resolution accepts only HTTPS addresses with a host and without embedded credentials. Media3 retains its default same-protocol redirect policy. The local request bypasses the provider and substituted stream requests preserve byte ranges. All five playback-source cases passed; live provider behavior remains unmeasured. |
| IE-M21/M75/M78/M105/M108/M122 | Both repeat modes protect traversal from automatic appends. Pause, shuffle and repeat commands revoke a pending refill, and publication rechecks the transport state before enqueue. A duplicate fade listener registration was also accumulating callbacks. Four playback boundary and seven queue-command fixtures passed. Production SDK 37 Kotlin compilation passed with this batch. Physical shuffle, route and lifecycle traces remain open. |
| IE-M22/M94/M95/M97/M106/M115/M116 | A new queue persists its first cursor in the queue file and skips an unnecessary second write. The store omits repeated identical positions and validates shuffle order in one pass. A bounded cursor read falls back to the queue on corruption. Fifteen store and 11 service persistence cases passed, including conflicting generations, oversized input and retry after a blocked temporary file. Physical process death and writes per hour remain open. |
| IE-M80/M104/M110 | A manually selected local recording without a filesystem path retains its exact physical source even when an equivalent higher-resolution file is present. An online request can still substitute an eligible lossless copy. Twenty-two playback-source and nine saved-catalogue pure JVM cases passed, including alternate masterings and source rejection. Production SDK 37 Kotlin compilation passed. |
| IE-M79/M89 | Ten library-index, six track-order, four CUE and 13 recording-source cases passed for disc/track ordering and distinct CUE clips. The service waits for the natural end of a manually selected queue before generating a tail. The current volume ramps belong to explicit play, pause and sleep actions. Crossfade is excluded by the existing owner decision in [PRODUCT_SPEC.md](PRODUCT_SPEC.md). Physical gapless and live-album audio remain open. |
| IE-M43/M131/M134/M136 | A verified song-seeded mix playlist can teach through the same path as its direct radio. General Home shelves and unseeded playlists supply no trusted seed. Evidence expires after 180 days during reads or natural captures. Five graph tests include a simulated year of observations with existing storage caps, two store tests and three local-tail tests pass. Physical utility and owner-history ranking remain open. |
| IE-M60/M62/M65/M75/M117/M120/M135 | A new radio starts on the selected seed through the existing service command. The controller accepts the exact occurrence even if a refill has already appended to the queue. The currently playing seed retains the replace-after path. Automatic queue publication checks current exclusions against both requested and resolved sources. Nine mix and twelve queue/controller fixtures passed. Real onset, Binder, and route measurements remain open. |
| IE-M114/M115 | Small recording writes use indexed candidates within the SQLite parameter budget and keep canonical aliases and occurrence order. The live play-count/latest-time query aggregates each recording's history once. Host SQLite 3.53.1 median row-fetch times changed from 120.47 to 99.70 ms for 50k recordings/80k events, 62.48 to 48.73 ms for 50k/3k and 35.47 to 31.74 ms for 2.5k/80k. Identical row sets were verified across query variants. All 29 Room repository fixtures passed, including continued Flow invalidation on history and ratings. |
| IE-M29/M121/M123/M130 | The optional-work policy fixture exercises 31,104 combinations of signals across every automatic work class. End-to-end idle, foreground and hardware trace gates remain open. |
| IE-M32/M33/M87 | A simultaneous EQ and channel-map change now fades the outputs of the two complete signal paths. One stereo scratch frame replaces a frame-sized allocation inside the transition loop. Filter coefficients are shared with headroom setup and the response reuses trigonometry per frequency. All 34 DSP cases passed. Two alternating HotSpot runs with 600 measured ten-band setups per arm yielded 1.855/1.916 ms baseline and 0.447/0.465 ms candidate p50. p95 was 2.423/3.331 ms baseline and 0.475/0.678 ms candidate, with identical aggregate output. These are host DSP setup costs. |
| IE-M35/M125 | Media3's separate playlist preload pool is disabled. Its queue buffering remains configured. A Robolectric fixture pins the factory's disabled pool setting. Real skip latency, buffered audio bytes and gapless behavior remain open. |
| IE-M24/M36/M120/M122 | Playback-service and local data-source creation leave the streamed-audio index cold. The first actual stream, a relevant visible Home shelf, or offline source selection opens it off main. Six cache fixtures passed, including reopening a persisted complete entry and playing it with zero upstream opens after local-only playback. Startup p50/p95 and disk I/O on Pixel remain open. |
| IE-M23/M27/M106-M108/M118/M122 | Local Cast uses one route-owned listener with eight active clients, bounded request headers, constant-time publication lookup and a new path namespace after stop. Returning to the local player closes live responses. Byte seeks cover open-ended, suffix and unsatisfiable ranges and preserve HEAD semantics. Three loopback/parser fixtures and one playback-error fixture passed. Cast receiver and Pixel storage/energy measurements are open. |
| IE-M28/M84/M95/M106/M116 | Local cover loading leaves the UI thread and reads at most 8 MiB plus one byte. FLAC and MP3 front-cover replacements keep existing back covers. ID3 formats whose other frames cannot be copied safely are refused. A resized tag rewrite retains a synced, checksum-verified stage anchored to MediaStore's volume revision and row creation identity until a complete read-back confirms the result. A later explicit edit can recover a matching partial write. Four recovery host tests and one MediaStore JVM adapter test passed. Physical power-loss and interrupted in-place headers remain open. |
| IE-M55/M57/M69/M132/M133 | The offline tail remains bounded and uses recording identity for duplicates and linked exclusions. A single-artist library can use the remaining distinct recordings. Relevant long-unplayed favourites use the shared existing Room snapshot with a bounded score and no separate observer. Warm synthetic 512-trial offline rankings yielded p50/p95/p99 of 0.499/0.793/1.127 ms at 10,000 tracks and 0.618/1.055/3.823 ms at 50,000 tracks. Index construction, provider and filesystem work are outside this benchmark. Actual relevance and Pixel latency remain open. |
| IE-M53-M56/M64/M111/M132 | Offline radio caps the candidate union at 512. A full tail of at least eight already-played recordings can select one compatible unfamiliar recording, with seeded replay and the existing exclusion and artist constraints. Six tail, eight mix and one synthetic evaluation case passed. Alternating host runs for the exploration change returned all 4,096 requested picks per arm and library size. At 10k tracks, baseline/candidate p95 ranges were 0.695-0.716/0.660-0.950 ms. At 50k they were 0.739-1.464/0.625-0.675 ms. The warmed fixture does not exercise exploration or prove a device speedup. Leave durable candidate precomputation deferred until target-device evidence warrants a writer. Actual owner skip and diversity effects remain open. |
| IE-M37/M101/M103 | Production SDK 37 debug Kotlin compilation and offline release runtime dependency reporting passed. The signed core comparison APK 1311 contains one DEX (8,299,228 bytes), one arm64-v8a native library (`libandroidx.graphics.path.so`, 10,096 bytes) and packaged profile files of 14,369 and 1,774 bytes. This checkout has no first-party profile source or benchmark generator. App-specific R8, profile and physical startup evidence remain open. |
| IE-M100/M140 | The working [design handoff](design/INTELLIGENCE_HANDOFF.md) maps existing domain and service states to UI semantics and records deferred controls. It is available for review while source reconciliation, release completion, physical measurements and design acceptance remain open. |

Automatic provider work from a physically local item requires a visible, interactive application. A remote item already streaming may request its actual queue continuation on an eligible unmetered connection. This work remains cancellable and subject to blackout. Explicit actions, media transport, audio quality and durable user mutations retain their own owners. Local fallback uses existing metadata and can incorporate verified teacher evidence.

## First-party census

Tree `eb493355e667ba419881952922279ff01d098e2c` has 416 tracked paths. Run
`git ls-tree -rz --name-only eb493355e667ba419881952922279ff01d098e2c` for the exact input.
Buckets are mutually exclusive in the following order.

| Classification | Paths |
| --- | ---: |
| Kotlin under test or androidTest sources | 103 |
| Other Kotlin | 180 |
| Project docs, AGENTS.md and CLAUDE.md | 24 |
| .agents instructions and references | 25 |
| Gradle files, wrappers and GitHub workflows | 17 |
| Other app/core source resources and fixtures | 58 |
| Remaining paths | 9 |

Entry-point review in this batch covered MainActivity, MusicApp, PlaybackService, PlayerConnection,
LibraryViewModel, YouTubeMusic, PlaybackMix and the download publication path. The counts describe
the tree. Per-file behavioral review is still open.

## Release runtime review

Run `:app:dependencies --configuration releaseRuntimeClasspath` with the production SDK settings.
The offline resolution listed 212 external coordinates including BOMs and constraints, with no
unresolved rows. Coil and app transports resolve one OkHttp 4.12.0 and one Okio family at 3.16.4.
NewPipeExtractor provides YouTube stream resolution and public playlist fallback; its nanojson and
jsoup are extractor internals. The app's bounded provider JSON and Android disk JSON cover
different inputs. Media3 1.11.1 owns local and Cast playback. Room 2.8.5 owns the local and durable
stores. AppFunctions 1.0.0-rc01 is the external assistant adapter.

The comparison manifest's AndroidX Startup entries are EmojiCompat, process lifecycle, and
ProfileInstaller. Cast also contributes external routing components. They still have functional
owners, so none was removed. Cached POMs declare Apache 2.0 for the AndroidX, Coil and OkHttp
artifacts sampled, GPL-3.0-or-later for NewPipeExtractor, MIT for jsoup and the Android SDK license
for Cast. The pinned nanojson POM supplied no license field. The full license and advisory
inventory, release R8 reachability and measured initializer cost remain open.

## Status by assignment group

| Assignment group | IDs with concrete evidence or partial implementation | Acceptance |
| --- | --- | --- |
| Core Perfection | M0-M9 code published as a signed test candidate, with 579 passing CI cases | Physical Pixel matrix pending |
| Energy | M10 inventory. M11-M29 have source changes, source review or targeted fixtures | Idle, power/audio and network traces pending |
| Audio path | M32-M33, M35-M38 and M87 have source changes or targeted evidence | Physical measurements and route acceptance pending |
| Physical audio and energy | The M30-M39 and M139 protocols are available | No physical device observations recorded |
| Recommendations | M40 baseline review. M41-M51, M53-M67, M69-M72 and M74 have implementation, measured deferral or host fixture evidence | Durable model coverage, device validation of intentional next, real owner replay and end-to-end Android acceptance pending |
| Playback and data | M75, M77-M81, M83-M84, M86, M89-M90, M92 and M94-M97 have source changes, verified fixtures or a product decision. M98 has a phone-only report procedure and M100 has a working design handoff | Full Android restart and randomized integrated faults pending. Device checks skipped by request. |
| Engineering extension | M101-M103 have source or package evidence. M104-M118, M120-M123, M125, M127 and M130-M138 have source review, changes or fixtures. M140 has a working design handoff | The per-file audit, owner signing and hosted Android verification remain open. |

The source, fixture and delivered-document coverage above is 111 of 131 assignment IDs, excluding
the physical protocol-only row. It includes partial implementation. The M11 pure kernel and M16
battery policy have passing local gates. Their Android adapter and workload coverage continue under
the integration and physical gates. IDs absent from the evidence column have no separate result
recorded here. The last stable release remains music-v1307.

## Remaining IDs and disposition

The owner requested closing the software scope without actual Pixel testing or long soak
runs. The device checks below are waived for this handoff and have no measured results.
This does not establish device verification or change the signing and publication workflow.

| IDs | Current boundary |
| --- | --- |
| M30, M31, M34, M39, M99, M119, M124, M126, M128, M129, M139 | Device work skipped by owner request. Hardware capability, startup, audio, modem, scheduler, thermal and energy results remain unmeasured. The optional protocol is in [PHONE_CHECK.md](PHONE_CHECK.md). |
| M52, M73 | Deferred under their optional acceptance criteria. Existing ranking works from catalogue, verified teacher links and durable preferences. There is no chronological owner replay showing that route, time-of-day or acoustic features justify collection or decoding. |
| M68, M85 | Acquisition remains a design-only contract. An owner-facing backlog needs verified local availability, estimates for the actual available copies, and an explicit choice. There is no approved acquisition source or diagnostic surface for those estimates in this phase. |
| M76 | A previous-queue stack remains design-only. The existing single-queue checkpoint preserves active position and order. An additional saved context would need a bounded, user-owned restore action. |
| M82, M88, M91 | Deferred optional processing. Existing ReplayGain and cached lyrics remain available. There is no active opt-in owner or demonstrated accuracy, CPU or storage benefit for an additional analyser or index. |
| M93 | The bounded next-cover and provider policy stay in use. Extra predictive work is deferred because the measured waste and owner hit-rate improvement are unavailable, and the assignment requires that evidence before activation. |

## Consolidated local checks

The production source at `07484e0` passed `gradle test -PminSdk=35 --continue --no-daemon` in
2m 53s. The generated reports contain 705 cases, with zero failures, errors or skips. Android
fixtures use API 35 and the production SDK remains 37.

Compared with the archived core candidate, 100 of 121 paired host images were pixel-identical,
including Home, full player, queue and large-type player. In the representative differences
reviewed by eye, Settings captured a different scroll position and the Home start buttons
captured different animation frames. The artist fixture had depended on network availability
for its biography. Its cached-biography scenario now uses local fixture data. These are host
rendering observations. They provide no Pixel frame-time or accessibility-service measurements.

The separate scenario review checked expired queue keys, stale restore intent, corrupt and
failed checkpoint writes, source replacement, route release ordering, an expired sleep fade,
network loss, reauthentication during prefetch, and cold teacher replay. The complete suite
covered these named cases. The production manifest and exported Room schemas are unchanged
from the core candidate. This pass did not have a second reviewer, and physical upgrade and
external controller interoperability are outside the local fixtures.

The active M77 work protects moves and removals by resolving occurrence identity in the
service. The optional undo affordance remains design-only under the feature freeze.

`assembleDebug` and `assembleRelease` finished with production SDK 37. R8 retained the
application, launcher, playback service, Cast options provider and artwork provider under their
original names. The usage report records normal shrinker removals. The 306-file Kotlin reachability
scan was heuristic, so no source was removed based on name counts. The first lint run flagged
an unqualified receiver registration in a Robolectric fixture. The fixture now uses an explicit
non-exported flag. The targeted gallery and artwork run passed all four cases afterward.
The repeated production `lintDebug` and `lintRelease` tasks both passed.

The release APK is arm64-v8a with min/target SDK 37, version code 1 and valid 16 KiB ZIP alignment.
Build-tools 37 verified a local debug signature. Its SHA-256 is
`d77e1c59b23bafae9ca367d0fa799c73f678b696955b1e962223e444cb294651`. The workspace lacks
the owner key, so this artifact is unsuitable for upgrading the owner's installed application.
The branch remains local under the owner's publication instruction.
