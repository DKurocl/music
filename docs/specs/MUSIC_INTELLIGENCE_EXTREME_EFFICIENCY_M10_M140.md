# Music Intelligence & Extreme Efficiency
## M10-M140 | Complete implementation and engineering audit assignment for the pre-design phase

Repository: https://github.com/DKurocl/music
Target: Pixel 10 Pro XL, Tensor G5, Android 17 (SDK 37), arm64, Sony WF-1000XM5.
Companion: MUSIC_CORE_PERFECTION_AGENT_PLAN.md (M0-M9). This is a continuation, not a replacement for that program or the original repository roadmap. The numbering here is a separate program namespace; use `IE-M10` through `IE-M140` in the repository task ledger to avoid collisions.


**Final extension:** M101-M140 is included below. M100 is a feature-scope freeze, not the final design gate. M139 consolidates final physical acceptance and M140 establishes design readiness. Where the earlier text says final acceptance at M99/M100, use the final extension scheduling rules. This does not waive verification or release gates.

### Mission and execution rules

Build a personal, local-first music system that remains excellent during prolonged power outages and unreliable connectivity. Battery efficiency and recommendation relevance are first-class product requirements, not optional cleanup. A screen-off local session must need no provider traffic for playback; an offline session must produce meaningful radio from available music using knowledge acquired during ordinary online usage. Finish functional foundations before redesign.

1. Read AGENTS.md, HANDOFF.md, CORE_PERFECTION.md, CORE_ACCEPTANCE.md, ROADMAP.md, ARCHITECTURE.md, AUDIO.md, PRODUCT_SPEC.md, PHONE_CHECK.md, all relevant specifications, and the Pixel APK skill. Reconcile the exact candidate head with the latest main. On 2026-11-25 the accessible handoff said implementation reached M9, but consolidated Android CI/device evidence remained outstanding. Do not assume that a documented local commit is published or that M0-M9 are fully verified. Do not overwrite another agent's branch or start incompatible changes during its tests.
2. First complete or preserve the existing M0-M9 integration and record its real evidence. Then claim tasks in this program. If a task is already implemented, review and refine or mark verified with evidence rather than building another subsystem. The owner currently wants implementation planning, not an immediate repository mutation or manual device-test marathon. When commissioned to implement, continue independent code work and automated verification; collect a single compact phone-acceptance bundle at the end. Never claim a physical result without a physical run.
3. Preserve current layouts, colours, typography, icons, motion, and navigation. Invisible behavior, domain APIs, test seams, and existing interaction correctness are in scope. New visible controls belong in `docs/design/INTELLIGENCE_HANDOFF.md`; implement the underlying APIs only where they have a real consumer or a clearly gated later integration. Do not ship dead services or enable incomplete features. No new account, analytics, own server, location access, microphone collection, or DRM bypass. No automatic acquisition of audio without the owner's explicit authorization and a lawful source. Respect prior backup/export and network-library exclusions; propose scope changes separately.
4. Use existing recording/source/occurrence identity, Media3 session, Room repositories, mix coordinator, audio path, tag scanner and cache owners. Do not create parallel authority or rewrite the player. Keep pure logic in pure Kotlin modules, production SDK 37, arm64, owner signer, pinned dependencies and English code/docs/UI. Communicate with the owner in Ukrainian. No em/en dashes in repository docs or source. Any new dependency or schema must justify cost and include migration/rollback compatibility. Never log tokens, URLs, filenames, raw account identifiers, or listening history by default.
5. No universal Android compatibility branches, Snapdragon tuning, CPU affinity, forced big cores, permanent high performance mode, hidden APIs, root, battery exemption request, always-on inference or blanket offload. Use public capability checks and release-mode evidence. Prefer platform codecs and normal scheduling. Tensor G5 specificity is the chosen device, actual route capabilities, measurement and resource policy, not chipset-name conditionals.
6. Do not equate a green build with sound quality or battery proof. Every task needs acceptance evidence or a precise blocker. Keep changes in small coherent batches with tests; do not relax assertions. No auto-stable release from an unverified branch. Follow repository publication rules and explicitly authorized transfers; consolidate phone-only tests into one candidate workflow. Maintain an evidence matrix distinguishing code, host JVM, Android CI, and actual Pixel results.
7. Priority P0: correctness, survival/idle drain, offline operation, source integrity. P1: recommendation relevance and normal playback efficiency. P2: optional features, only after their cost is justified. For optional enhancements, a measured no-benefit decision with rationale is a valid outcome; never implement artificial complexity just to tick 91 boxes. Numerical budgets below are provisional engineering gates, not measured gains.

## Architecture contracts

### One resource policy

Create or evolve a single event-driven resource policy (pure decision function plus Android adapter), not a second player or an always-on daemon. Inputs: user play/pause/stop intent, service ownership, foreground visibility, screen state, route and audio configuration, validated default network, metering, battery saver, charging, battery level, thermal status and storage pressure. Unknown inputs are conservative for optional work. Output: allowed work classes, budgets, cancellation reasons and generation. Inputs are subscribed only while a legitimate owner exists. State changes are conflated and hysteresis prevents thrashing. Never poll battery/network continually. Never confuse process cached in RAM with battery activity. A power outage is not directly observable; use user-selected policy and real Android signals, not location or power-grid guesses.

| State | Required work | Optional work |
|---|---|---|
| Dormant | Nothing periodic, no playback or transfer | None; no new jobs or alarms to wake the process |
| Foreground browsing | Explicit UI and user requests | Bounded visible data, no eager full library refresh |
| Screen-off local | Audio, necessary audio focus/session/checkpoint, queued next source | No speculative network, animations, lyrics fetch, scans or training |
| Screen-off streaming | Audio, necessary resolver/recovery and bounded next item | Only work needed for smooth playback; defer other provider requests |
| Blackout / Saver | Requested audio, local search and cached ranking | No autonomous prefetch, sync, training, artwork or retries; explicit user requests still work |
| Learning opportunity | Existing user-approved active session or explicitly opted-in charging/unmetered work | Finite cancellable learning and enrichment batch; never wake repeatedly to check conditions |

The above are policy modes and capability flags, not services. Explicit actions take priority; optional work must yield before audio. Severe thermal/storage pressure reduces optional work first. Do not silently reduce audio quality, reset playback, change Bluetooth codec, or disable owner-selected effects. If Android legitimately requires an audio foreground service/wake lock, retain it during real playback only. If owner wants extreme saving during playback, surface future trade-offs in design backlog rather than silently changing settings.

### Online teacher / offline student

The provider is an imperfect teacher, not ground truth. Learn from normally requested provider radio/playlist/recommendation responses and local interaction events; no crawling the whole catalogue or collecting other users' data. Use canonical recording IDs and separate provider video ID, edition and source availability. Store sparse weighted edges (co-recommendation, explicit artist/album/genre, observed sequence), timestamps, provenance, confidence and account scope. Keep local behavior separate from account-specific provider evidence. Provider order is evidence, not a reward signal. Account logout deletes/isolates its private evidence; local listening preferences persist per owner policy. Never persist cookies or playback URL in model artifacts.

Candidate generation unions eligible local recordings, known teacher neighbors, similar artists/albums, local taste clusters, forgotten favourites and exploration. Offline filter BEFORE scoring: readable local or complete permitted cache; hidden/excluded tracks obey product policy; no unavailable entries or duplicate recording unless user requested repeats. Exact manual queue always wins. If sparse evidence, use transparent artist/album/genre/recency fallback, not invented genres or random junk. Respect versions and negative feedback.

Suggested initial ranking (weights tuned against held-out events):

`score = wT*taste + wG*graph + wS*seed + wC*context + wN*novelty + wF*forgotten - wR*recentRepeat - wK*skipPenalty - wU*uncertainty`

All components normalized and bounded. Hard exclusions and offline availability are filters, not weights. Confidence shrinkage prevents one skip or one listen dominating. Diversify final list by artist and album without breaking explicit album/playlist order. Track separate short-term and long-term taste. Use time decay and event reason, do not treat focus loss, manual seeks, Bluetooth disconnect, errors, or sleep timer expiry as dislike. A completed song is not automatically a favourite. Persist incremental sufficient statistics, not endlessly replaying all history. Learn only on finite allowed events, with fixed CPU/memory/write budgets; publish model atomically and retain last known good model. Offline inference is a few indexed lookups and bounded scoring; no LLM, background GPU/TPU, or mandatory model download. Optional acoustic descriptors only for user-owned files with permission and charging, and only if an ablation demonstrates better recommendations.

## A. Energy architecture and outage survival (IE-M10-M29)

### M10 | Energy ownership inventory [P0]
Map every process-lifetime scope, observer, callback, executor, alarm, worker, wake lock, FGS, network client, disk write, animation and timer to its owner. Include PlaybackService, AppGraph, Prefetch, downloads, lyrics, artwork, sync, mix and Compose. Acceptance: a machine-readable ownership table with start/stop conditions, resource class, cancellation path, and test; every orphan fixed or explicitly justified.

### M11 | Resource policy kernel [P0]
Implement immutable policy input/output, pure deterministic transition reducer, feature work classes and one policy revision. Connect existing components through narrow adapters; no polling loop. Acceptance: exhaustive transition matrix including charging loss, network loss, pause, screen-off, rapid changes and unknown state; stale work cannot commit.

### M12 | True dormant state [P0]
After playback and explicit transfer end, release audio focus, wake locks, foreground ownership, controller-bound observers and optional coroutine jobs when safe. Save checkpoint before release; retain only cheap data needed for future resume. Acceptance: zero app-owned periodic work or unnecessary wake locks after settling; normal resume, widgets and system controls still correct.

### M13 | Screen-off isolation [P0]
Stop frame clocks, artwork motion, lyric layout/ticks, image decoding, visible page flows and periodic UI publications when not visible. Keep system notification/media metadata truthful. Acceptance: no UI work during background audio except event-required system surfaces; screenshot regression unchanged on return.

### M14 | Blackout policy [P0]
Use Battery Saver and explicit future user policy to disable autonomous remote requests, expensive ranking rebuilds, background scanning, optional downloads and retries while preserving selected playback. Add pure policy hooks for future settings, not a new screen. Acceptance: airplane mode, unvalidated network, charging loss and low battery do not trigger a retry storm; local functions remain available.

### M15 | Charge-aware learning windows [P0]
Allow a finite learning batch during active permitted use or explicit opt-in charging + validated unmetered connectivity. Cancel at charging/network/thermal loss, checkpoint progress, no periodic job by default. If scheduling is approved, use Android constraints and unique one-shot work; no wake-up just to inspect eligibility. Acceptance: zero charging-only work when unplugged, repeated triggers coalesce, no infinite worker retry.

### M16 | Battery signal policy [P1]
Use existing Android power saver/status broadcasts and current snapshot. Initial configurable tiers: normal, <=25%, <=10%, system saver; hysteresis prevents repeated transitions. Thresholds are proposals, not fixed user settings. Acceptance: no active polling, no quality downgrade without consent; critical user action can still run.

### M17 | Thermal pressure [P1]
Use public thermal status callbacks while relevant work is active; reduce parallel optional IO/CPU and stop training at severe pressure. If thermal headroom used, obey sampling limits and handle unsupported/NaN. Acceptance: simulated hot/cool transitions cancel optional work; audio remains protected.

### M18 | Work admission and budgets [P0]
Centralize priority and bounded concurrency across real executor/network paths: audio/recovery > explicit action > actual next item > sync/learning > imagery. Never block an audio thread on optional permit. Acceptance: saturated fake auxiliary workloads cannot starve audio; cancellation releases permits, no unbounded queue.

### M19 | Wake-lock accounting [P0]
Audit Media3 wake mode and all explicit locks, including transfer/error paths. No additional lock for discovery, training or idle. Track duration by purpose in debug reports. Acceptance: active playback is uninterrupted; all app-owned locks released on stop, error, task removal and service destruction.

### M20 | Android power lifecycle [P0]
Verify SDK37 audio FGS and session lifecycle, resumption, Doze, cached process, standby and user stop semantics; no exemption requests or boot auto-launch. Acceptance: play when requested, preserve paused state, no unsolicited service restart. Device checks deferred to M99 if unavailable.

### M21 | CPU wakeup consolidation [P1]
Replace redundant independent timers with event-driven or deadline-based logic under existing owners. Coalesce widget, notification and checkpoint updates where safe; preserve sleep timer accuracy. Acceptance: count wakeup-causing events in repeatable screen-off trace and reduce measured unnecessary ones, not just thread count.

### M22 | Checkpoint write amplification [P1]
Measure history, position, listening and queue writes; batch noncritical progress updates without losing required recovery points. Keep atomic durable intent and final flush. Acceptance: bounded writes/hour, abrupt-kill fixture recovers within documented position-loss window, no lost likes or playlist edits.

### M23 | Local file IO efficiency [P1]
Profile buffered read sizes, extractor reads, repeated open/stat and seek cost for FLAC/MP3/ALAC and CUE. Reuse metadata, avoid duplicate file handles and cache scans. Acceptance: no additional file pass for healthy play, no increased underruns, compare bytes and system calls on Pixel.

### M24 | Local cache policy [P1]
Do not duplicate owned audio into stream cache. Identify repeated image/metadata copies and bound cache sizes by actual utility; avoid eager eviction scans. Acceptance: no owned audio copied, one bounded cleanup path, disk-full and interrupted eviction fixtures pass.

### M25 | Radio tail energy [P1]
Batch necessary remote metadata and stream reads when safe; compare connection reuse and streaming burst shape on Wi-Fi vs cellular. Never keep a radio awake with speculative tiny requests, and do not pre-download unrelated tracks. Acceptance: measured request count/bytes and radio-active time; no worse startup or stalls.

### M26 | Metered and weak network [P0]
Use validated default network, metering and observed request outcomes; distinguish weak network from no service. Suppress optional traffic and back off failures with bounded retry and cancellation. Acceptance: rapid handovers, captive portal, 429, timeout and reconnect fixtures; offline local play initiates no provider calls.

### M27 | Memory pressure and cached process [P1]
Trim only rebuildable caches in response to lifecycle/platform pressure; retain durable state. No manual GC loops, no huge retained singleton index variants. Acceptance: repeated active/idle transitions plateau in PSS and heap; process reclaim works without correctness regression.

### M28 | Storage pressure [P1]
Use cached storage budget, check space before explicit heavy operations, avoid background directory walks. Abort optional writes early, preserve user DB and files. Acceptance: full storage, failed write and process death tests; no destructive cleanup of music.

### M29 | Energy policy acceptance [P0]
Automated policy tests plus instrumentation counters for forbidden work, active jobs, callbacks, requests and writes by state. Gate later optional features on the same contract. Acceptance: zero autonomous requests in blackout and dormant modes; screen-off local play uses no audio network traffic; no regression in first tap or audio continuity. Actual energy numbers remain device-pending until measured.

## B. Pixel audio and performance (IE-M30-M39)

### M30 | Device-specific capability matrix [P1]
Record actual Pixel firmware, route, decoder, PCM encoding, offload support, effects, sample rate and available public diagnostics. No chipset-based assumptions. Acceptance: unsupported capabilities are explicitly unknown/disabled, actual route changes invalidate capability snapshot.

### M31 | Audio offload experiment [P1]
Compare regular vs eligible offload for local FLAC/AAC/MP3 and streaming with screen off. Use Media3 capability checks, effective mode, wake behavior, gapless and DSP requirements. Acceptance: enable only proven compatible cases, no EQ/ReplayGain bypass or audible transition regression; report savings or reject optimization.

### M32 | Minimal PCM path [P1]
Measure float vs integer path where fidelity permits, pass-through/bypass allocations and format transitions. Keep high resolution precision and user-selected effects. Acceptance: golden PCM fixtures, clipping/gain checks and unchanged output semantics; no unnecessary conversion inside app.

### M33 | DSP hot paths [P1]
Profile EQ, gain, fade, channel mix and DSD conversion. Cache coefficients and reuse scratch buffers; optimize with Kotlin first, native SIMD only if demonstrated win. Acceptance: numeric equivalence tolerances, allocations and CPU/audio-hour improvement; no added JNI complexity without benefit.

### M34 | Buffer strategy experiments [P1]
Measure local, cached and remote buffering separately, using existing Media3 controls. Compare current 30s/600s and 24 MiB settings with alternatives by route/bitrate/network. Acceptance: chosen config based on startup, underruns, radio/IO energy and PSS; do not reconfigure active player mid-song unnecessarily.

### M35 | Next-track preparation economics [P1]
Combine current Media3 preload and URL warmup; only actual next occurrence and only if useful, never fetch audio for a verified local source. Cancel on queue generation change. Acceptance: hit/waste ratio, skipped bytes, latency and cost relative to M9; zero speculative blackout requests.

### M36 | Startup work graph [P1]
Trace app and service cold/warm paths separately. Defer provider and recommendation graph initialization until needed, lazy-load only required shards and share existing snapshot. Acceptance: no extra critical-path DB/network requests from this program; Macrobenchmark p50/p95 baseline vs candidate.

### M37 | Baseline Profiles and R8 [P1]
Use real supported benchmark journeys and inspect generated profiles, APK size and startup; only dependency changes proven needed. Acceptance: release-equivalent performance evidence, signing and R8 regressions pass; no fabricated profile.

### M38 | Render cost without redesign [P1]
Profile 120Hz frame timeline, lyrics and artwork update fan-out, offscreen composition and image decode. Preserve visuals. Acceptance: unchanged reviewed goldens and frame timing no worse; no work when screen off. 120Hz means 8.33 ms nominal frame interval, not a guaranteed CPU budget.

### M39 | Pixel energy measurement protocol [P0]
Prepare reproducible paired release-equivalent tests: same track, volume, LDAC settings, signal strength, firmware, temperature, screen, starting state and duration. Use Android Studio Power Profiler/ODPM where supported, Perfetto, batterystats and battery charge counter where available. Separate total phone consumption from app attribution; include airplane-mode idle control. Acceptance: repeat >=3 per configuration when feasible and report variance, no invented hardware numbers; automated reporting ready without needing owner to run each milestone separately.

## C. Learning from online recommendations (IE-M40-M59)

### M40 | Recommendation baseline and contract [P0]
Audit current Mix, PlaybackMix, HomePicks, smart playlists, exclusions, user history and provider order. Define existing baseline, seed/context inputs, candidate eligibility and explainable score contract. Acceptance: replay baseline on deterministic fixtures; no new authority over explicit playlist order.

### M41 | Listening event semantics [P0]
Define play start, actual listened duration, natural completion, deliberate skip, repeat, seek, like, dislike and explicit exclusion. Use existing occurrence/history gates and monotonic time; focus loss, error and sleep expiry are not negative preference. Acceptance: source switch and reconnect do not create extra training events; reason-aware fixtures pass.

### M42 | Local interaction summary [P1]
Maintain bounded sufficient statistics per recording/artist/album rather than an expensive full history scan. Weight actual listened fraction, confidence and sample size; preserve original history. Acceptance: incremental result matches batch replay within tolerance; migration and reset safe.

### M43 | Provider teacher ingestion [P1]
Extract ordered, deduplicated video/recording neighbor evidence from naturally requested radio, Home and mixes. Capture seed, rank, provenance and timestamp; distinguish personalized and public evidence. No extra requests by default. Acceptance: fixtures with partial, duplicate and wrong-ID responses do not poison graph.

### M44 | Recording normalization [P0]
Use canonical recording IDs for preference evidence and verified source mapping; unknown video stays unresolved, no name-only merge. Redirect aliases and preserve distinct edits/live versions. Acceptance: merge aliases updates scores once and wrong recording variants stay independent.

### M45 | Sparse relationship graph [P1]
Persist bounded weighted seed-to-neighbor, artist and album edges with confidence, observation count, age and origin. Incrementally upsert rather than rebuild whole graph on each response. Initial cap: 32-64 strongest recording neighbors per seed, tune against evaluation. Acceptance: bounded disk, lookup and merge cost at 50k recordings.

### M46 | Teacher confidence [P1]
Apply rank decay and source reliability; repeated exposure to same cached response does not count as independent evidence. Avoid assuming recommendation equals user like. Acceptance: duplicate response idempotent; partial response not negative; explicit owner exclusion always wins.

### M47 | Account and provenance boundaries [P0]
Isolate personalized provider graphs per account, public evidence separately, local owner preferences per product contract. Cancel old account jobs and invalidate account-specific snapshots on logout/switch. Acceptance: no cross-account personalized result or pending mutation, no credential data in training store.

### M48 | Short-term taste [P1]
Build a compact current-session vector from recently chosen and genuinely listened music. Decay rapidly with session changes and avoid learning from automatic queue insertion alone. Acceptance: a new seed can change direction without erasing long-term taste.

### M49 | Long-term taste [P1]
Build stable genre/artist/album/recording affinity with shrinkage for small samples and bounded recency decay. Use explicit ratings and likes with stronger confidence than inferred behavior. Acceptance: one anomalous listen/skip cannot dominate; controlled replay yields expected rankings.

### M50 | Negative preference [P1]
Distinguish permanent explicit exclusion, session skip, repeated intentional early skips, and neutral interruption. Avoid penalizing an album or artist because a single track was skipped. Acceptance: focus loss/disconnect/error never create a negative signal; explicit exclude never leaks into radio.

### M51 | Seed-specific relevance [P1]
Combine teacher edges, verified recording metadata and session taste with the selected seed. Preserve album continuity and selected track as first item. Acceptance: seed changes alter results appropriately; no same-title variant confusion or stale old-seed completion.

### M52 | Context without surveillance [P2]
Optional coarse time-of-day and audio-route affinities from existing session events; use only if evaluation shows benefit. No GPS, sensors, microphone, extra callbacks or inference of location. Acceptance: context absent or disabled yields normal ranking; held-out evaluation demonstrates gain or defer.

### M53 | Candidate generation [P1]
Use bounded indexed neighbor union, artist/album/genre candidates and forgotten favourites; avoid scanning all tracks per transition. Distinguish local candidates from provider discoveries. Acceptance: deterministic candidates, hard source filters, no duplicate recording IDs and bounded latency for 50k library.

### M54 | Explainable scoring [P1]
Implement pure normalized score components with stable tie-break and reason codes. Use a simple interpretable weighted ranker first. Acceptance: component ablations, deterministic unit tests and no NaN/overflow; scores explain which evidence influenced selection.

### M55 | Diversity and repetition [P1]
Re-rank automatic radio for artist/album diversity, recent play suppression and appropriate variety while preserving explicit album and curated playlist order. Acceptance: no repeated recording in generated window unless library exhausted and policy explicitly allows; small-library fallback does not deadlock.

### M56 | Exploration with restraint [P1]
Introduce a small bounded exploration fraction among compatible eligible candidates, adapt only from real response. Seeded randomness for replay tests. Acceptance: no unrelated-track flooding, no permanent exploration feedback loop; compare skip rate and diversity offline.

### M57 | Forgotten favourites [P1]
Rank genuinely liked but long-unplayed songs by relevance and time since listen, not just least played. Respect exclusions and recent manual choice. Acceptance: rediscovery is relevant to seed/session and not repeatedly surfaced on every launch.

### M58 | Incremental learning engine [P1]
Fold new events and teacher edges into versioned sufficient statistics in small finite batches. No continuously running trainer, no work during blackout, no full history replay in playback path. Acceptance: cancellation/resume idempotence, bounded work budget, last valid model always usable.

### M59 | Model store and recovery [P0]
Version model schema and feature definitions; atomically publish immutable ranking snapshot, preserve last good snapshot. Corrupt model is rebuildable from permitted local evidence, never corrupts user DB. Acceptance: process kill mid-update, schema upgrade, account switch and empty model fixtures pass.

## D. Offline-first intelligence and evaluation (IE-M60-M74)

### M60 | Offline availability index [P0]
Separate source eligibility from similarity. Build revisioned local/readable and complete permitted-cache availability using existing source resolver; do not stat entire library per recommendation. Acceptance: offline recommendation requires eligible source, removed files invalidate, zero audio request for verified local.

### M61 | Offline radio generator [P0]
Generate queue from learned graph and local affinity, filtered before ranking. Never require provider URL or account login to start a local seed. Acceptance: deterministic airplane-mode fixture with warmed model generates relevant playable queue; no network or unavailable placeholder for auto-recommendations.

### M62 | Cold-start fallback [P0]
If no teacher evidence or sparse library, fall back to verified artist/album/genre/likes and existing local mix rules; make confidence explicit. Do not invent acoustic tags. Acceptance: empty, tiny, single-artist and unknown-tag library all terminate gracefully.

### M63 | Offline graph projection [P1]
When local files change, map stored teacher neighbors to newly available verified recordings via existing aliases and exact evidence. Keep unavailable neighbors for later discovery without recommending them offline. Acceptance: new file appears in next radio without retraining all history; no false version merge.

### M64 | Precomputed ready-to-use candidates [P1]
Build small candidate pools for common seeds and favourite clusters during allowed windows; store revision and expiry, revalidate availability on use. No background timer just to refresh. Acceptance: blackout recommendation uses cached or cheap indexed ranking within budget; stale file cannot enter queue.

### M65 | Online/offline continuity [P0]
If internet disappears, preserve current eligible track and manual queue, refill automatic tail with local model when needed. On reconnect do not replace healthy current item or manual entries; provider candidates may supplement future tail. Acceptance: generation guards and source identity maintained through handover.

### M66 | Offline search usefulness [P1]
Expose locally available records, saved metadata and cached catalogue under existing search modes without pretending stale data is live. Keep raw provider intent and existing visual design. Acceptance: no network calls during offline search; exact and fuzzy local matches remain distinct from confirmation of recording identity.

### M67 | Offline metadata essentials [P1]
Retain useful permitted artist/album/track metadata, lyrics already retrieved and local artwork under a bounded budget. Prefer existing caches; no automatic media downloads. Acceptance: offline pages render meaningful cached/local details, clear-cache does not delete user data.

### M68 | Offline coverage planning [P2]
Compute coverage of favourites, recent plays and preferred mixes by verified local availability. Produce suggested acquisition backlog using value per storage byte, not automatic downloading. Acceptance: accurate coverage and no duplicate recommendations for already eligible copies; UI deferred.

### M69 | Learning when connected [P1]
Use naturally encountered provider metadata and feedback immediately in cheap bounded updates while foreground/connected; optional larger reconciliation only in allowed charging windows. Do not equate online with permission to run heavy jobs. Acceptance: unplug/network loss cancels expensive batch, completed snapshot retained and no playback delay.

### M70 | Chronological evaluation dataset [P1]
Use locally generated synthetic fixture plus consented owner-only history replay. Train on past, test on later intentional plays; avoid future leakage and counting recommendation exposure as a positive label. Record coverage of available files at evaluation time where possible; unknown historical availability is a limitation. Acceptance: deterministic split and repeatable metrics; no private data export.

### M71 | Ranking metrics [P1]
Compare current local mix, popularity/recency baseline, teacher-only and personalized hybrid. Report Recall@10, NDCG@10, eligible coverage, diversity, repeat rate and offline availability rate; label historical proxies. Acceptance: improve relevance against current baseline without worse hard exclusions or energy; do not promise percentage until data exists.

### M72 | Model selection and ablation [P1]
Start with weighted graph and simple affinity. Compare lightweight co-occurrence and small learned linear weights only if enough evidence. Evaluate incremental cost and sparse-data behavior; prefer simplest winning model. Acceptance: documented quality/cost trade-off, regression tests, reject overfit or heavyweight options.

### M73 | Optional acoustic descriptors [P2]
Only investigate short-window local-file BPM/energy/timbre descriptors if metadata graph quality is insufficient. Work on explicit permission or allowed charging windows with resumable bounded CPU/IO; no full-library decode at startup. No neural accelerator assumptions or automatic remote audio extraction. Acceptance: measured ranking gain and acceptable storage/energy or reject; never fabricate tags or classify personal traits.

### M74 | Offline quality gate [P0]
End-to-end fixture: learn from online teacher responses, stop network, choose known and new seeds, generate playable local radio, change library and model, restart. Acceptance: zero unavailable automatic recommendations where eligible options exist, zero remote calls, no duplicate recording until exhausted, bounded latency, documented ranking lift or remaining data limitations.

## E. Playback and library intelligence (IE-M75-M89)

### M75 | Queue session semantics [P1]
Extend existing queue owner with explicit manual entries, automatic tail and origin. Recommendations may append only to accepted matching generation. Acceptance: user reorder/clear/skip/seek and late results never overwrite manual intent; occurrence keys stable.

### M76 | Resume previous listening context [P2]
Evaluate bounded suspended queue context for switching to a single song then returning to album/mix. Store position, repeat/shuffle, occurrence order; do not start automatically without user intent. Acceptance: save/restore domain tests and design handoff; avoid large checkpoint duplication.

### M77 | Safe queue undo [P2]
Design bounded undo for user-initiated destructive queue edits, excluding automatic source changes. Keep stable occurrence keys and no resurrection after unrelated generation. Acceptance: reversible commands and conflict tests; future UI integration, no new control now.

### M78 | Intelligent shuffle [P1]
Use actual traversal order and recent distinct recording history to avoid annoying repeats in automatic shuffle where compatible with chosen semantics. Preserve full album/playlist traversal and explicit repeat. Acceptance: seeded statistical tests, complete traversal without starvation; no replacing Media3 shuffle authority.

### M79 | Album continuity [P1]
Recognize verified disc/track order and contiguous album playback; avoid radio insertion, smart skip or crossfade into uninterrupted album boundaries unless explicitly requested. Acceptance: CUE/live album fixtures preserve gapless intent and sequence.

### M80 | Quality preference semantics [P1]
Separate encoding fidelity, source availability, recording/version preference and mastering evidence. Higher sample rate/bit depth alone is not proof of better master. Acceptance: manual selection wins; lossless preferred only among verified equivalent eligible sources; no blind replacement.

### M81 | Local file integrity [P1]
Validate headers/metadata for newly acquired user-owned audio using existing tag readers, catch corruption at open and safely fall back. Optional full checksum/decode only on explicit request or justified allowed window. Acceptance: truncated/malformed files never silently become preferred; no repeated deep scan per play.

### M82 | Quality analysis evidence [P2]
If useful, implement offline diagnostic for clipping, loudness/dynamic range and suspicious bandwidth limits; describe uncertainty, never certify lossless provenance from spectrum. Save per-content revision only. Acceptance: fixtures with known signals, no automatic deletion or audio modification, heavy work gated by policy.

### M83 | Recording/edition graph [P1]
Evolve existing recording links into explicit artist, release, edition and source relationships using verified IDs where available. Keep graph lean and relational; no new graph database. Acceptance: distinct editions, collaborations and same-name artists preserved; migrations and alias resolution tested.

### M84 | Metadata enrichment [P2]
Use already requested provider metadata and optionally user-authorized MusicBrainz lookups within service limits, coalesced and cached. Do not overwrite local tags without user approval. Acceptance: provenance/conflict precedence, cancellation, offline behavior, no bulk crawling.

### M85 | Smart acquisition backlog [P2]
Rank missing or potentially upgradeable recordings by actual use, preference, confidence, availability and storage. User decides whether to acquire a lawful copy. Acceptance: no automatic downloads, no same-title false positives, no existing files removed; design backlog only for new UI.

### M86 | Safe import and publication [P1]
Audit existing download, file scan and media publication pipeline; validate metadata and completion before preferred-source eligibility. Feed committed source revision to offline graph, not repeated scan. Acceptance: interrupted import, duplicate, wrong version, low storage and cancel tests.

### M87 | Audio profile intelligence [P1]
Review existing per-output profile and Sony integration, preserve explicit EQ, ReplayGain, speed and codec choices. Route changes apply correct profile without volume jumps. Acceptance: exact route lifecycle tests; device-specific capability fallback; no added polling.

### M88 | Loudness and headroom [P2]
Evaluate cached loudness for untagged local tracks on explicit request/allowed window, use correct album vs track gain and clipping safety; never change defaults silently. Acceptance: known fixtures and bypass equivalence, no scan during playback or blackout.

### M89 | Transition intelligence [P2]
Evaluate optional smart crossfade based on known track boundary/album context and cached metadata, not constant audio analysis. Default gapless and current user settings win. Acceptance: continuous albums, seek, short tracks and errors tested; reject if audio quality or energy regresses.

## F. Completion, maintenance and release readiness (IE-M90-M100)

### M90 | Lyrics offline continuity [P1]
Use exact recording/timing identity and existing resolver; no fetch when valid local lyric is present, no frame clock screen off. Acceptance: offline lyrics, seek, track changes, source switch and manual offsets preserved.

### M91 | Lyrics relevance and search [P2]
Evaluate opt-in local indexing of already stored lyrics for search and alternative-source quality, with bounded size and no remote bulk downloads. Distinguish instrumental/unknown/no result; no invented text. Acceptance: fixtures and no added idle work; new UI deferred.

### M92 | Cache usefulness accounting [P1]
Track bounded aggregate hit/waste counters in opt-in diagnostics, not personal telemetry. Use measured utility to tune existing eviction, next-item warmup and page caching. Acceptance: reduced wasted work without latency or offline regression; reset counters safely.

### M93 | Prediction economics [P2]
Compare actual-next-only prefetch against simple predictive approaches using recorded anonymous aggregate/fixture patterns. Cost includes wasted bytes, wakeups and memory. Acceptance: use predictive work only if demonstrably beneficial under allowed policy; disabled in blackout/dormant.

### M94 | Autonomous maintenance without daemon [P1]
Validate cache/index invariants on relevant open/change events or allowed explicit maintenance, repair rebuildable data only. Bound time and batch size, yield to playback. Acceptance: corrupt cache, stale aliases and interrupted cleanup recover; no permanent job, no deletion of personal data.

### M95 | Data consistency and recovery [P0]
Review transactional migrations for new learning/model data, alias merge, offline sync, account switching, crashes and low storage. Keep model rebuildable, user DB durable. Acceptance: migration fixtures from actual previous versions, no loss of playlists/ratings/lyrics/history; prior owner backup policy unchanged.

### M96 | Privacy and dependency audit [P0]
Audit new storage, provider terms/allowed use, account boundaries, dependencies and logging. Delete or isolate provider-personalized evidence on logout as contracted; allow future model reset separate from personal library. Acceptance: no secrets in logs/artifacts; no additional permissions without necessity; no heavy dependency without evidence.

### M97 | Automated integration and fault injection [P0]
Combine policy, playback, offline graph, fake provider and Room integration. Simulate fast taps, process death, connectivity flapping, account changes, failed writes, corruption, low battery, thermal events and concurrent imports. Acceptance: invariant checks over seeded randomized sequences and existing full suite; report failures without hiding them.

### M98 | Compact owner diagnostics [P1]
Prepare one candidate-only or opt-in bounded diagnostic action/report using existing diagnostic architecture, or a documented phone-only procedure if visible changes are forbidden. Include build, policy state, chosen source rationale, local/online request counts, queue errors, timing proxies, battery/thermal signals and recommendation reasons. No raw track paths, cookies, tokens or remote upload. Acceptance: owner can send one redacted report without PC; no mandatory diagnostic foreground service or permanent polling.

### M99 | Consolidated physical acceptance [P0]
Run one agreed candidate workflow after code/CI: idle baseline vs candidate, local screen-off FLAC/LDAC, cached/online audio, blackout, network return, recommendation quality, audio route/gapless and lifecycle. Repeat measurements with same conditions where possible. Separate subjective listening, event proxies, real acoustic onset and energy instrumentation. Acceptance: actual results or clear blocked status; never demand 91 separate manual tests.

### M100 | Feature freeze and design handoff [P0]
Reconcile every task with commit, evidence, existing implementation or reasoned deferral. Produce stable functional contracts, state catalogue, error/loading/offline cases, future controls and design-only backlog. Keep unverified physical items explicit; release candidate and stable according to existing signing/approval policy. Acceptance: no unresolved known P0 correctness issue, automated gates pass, no new idle work, model and resource policies coherent; owner approves remaining device gates before stable. Do not start redesign in this assignment.

## Acceptance budgets and measurement interpretation

These are provisional ceilings or stretch targets, not claims about the Pixel's current consumption or guaranteed gains. Establish M9 baseline first. No optimization should be accepted solely because one synthetic metric improved.

| Metric | Proposed target | How to establish |
|---|---|---|
| Dormant app-owned periodic tasks/wake locks | 0 after legitimate work settles | Instrumentation counters plus system trace; OS activity distinguished |
| Autonomous traffic in blackout | 0 requests; 0 audio bytes for local playback | Fake transport and device counters, explicit user action excluded |
| Local screen-off optional training/scan/render | 0 | Policy counters and trace |
| Offline auto-radio unavailable picks | 0 when playable candidates exist | Availability fixtures and physical runs |
| Offline rank warm 10k/50k | p95 <=50/100 ms initial target | Release-equivalent benchmark, no full library scan per transition |
| Learned graph storage | initial 16-64 MiB cap; tune | Account scope, DB file + indexes, exclude existing music |
| Ranking candidates | initial <=512 per decision, bounded edge lookup | Benchmark, compare relevance before reducing |
| Learning work | small finite batches, initial <=1s CPU per incremental batch with cooperative cancellation; no unplugged heavy training | Release profile, CPU time not wall time, adjust to evidence |
| Idle energy | indistinguishable from matched phone control within measurement noise | Repeated 6-8h screen-off tests; total phone vs attributed app |
| Local FLAC/LDAC screen-off | aim 10-20% less app-attributable energy than M9 if avoidable work exists | Repeated 2h+ paired tests, unchanged output and codec; report no gain if baseline already lean |
| Blackout background optional drain | remove 100% of prohibited app work; no claim of 100% battery savings | Counters and paired total-phone traces |
| Online audio traffic | no duplicate audio fetch; minimize avoidable metadata/prefetch bytes | Same track/queue/network, cache state controlled |
| Recommendation relevance | aim meaningful positive NDCG@10 and Recall@10 delta vs current local mix, e.g. +10% relative as stretch only | Chronological holdout, compare coverage and confidence; no fabricated improvement |
| Recommendation responsiveness | no extra first-tap critical path delay | Playback timing and source-selection regression |
| Audio correctness | no new clipping, underruns, wrong source or unexpected effect changes in controlled tests | Existing audio fixtures and real route check |

Battery percentage is a coarse device-wide measure affected by signal, modem, screen, Bluetooth, firmware, battery health, ambient temperature and other apps. A reduction in app CPU or app-attributed energy is not an equal reduction in whole-phone consumption. Report raw results, repeat count, measurement error, and percent/energy units. A system audio wake lock may be necessary during playback; do not eliminate it at the cost of audible dropouts. If a phone-only run cannot access power rails or attribution, record battery percent/charge-counter and conditions, label it total-device evidence, not app-specific proof.

## Execution sequence

- A (M10-M29) first: resource admission and blackout invariants protect all later additions. B (M30-M39) may prepare independent measurement/fixture work but cannot claim physical benefit yet.
- C (M40-M59) then D (M60-M74): event semantics, teacher graph and offline availability before complex ranking. Implement vertical slices that generate actual offline radio early. M70-M74 compare with existing algorithm before enabling richer model by default.
- E and F: only implement optional items with justified benefit and existing usable surfaces; otherwise create a documented design proposal or reasoned defer. No accumulating dormant services/features. Integrate tests with each slice, consolidate costly Android/device verification at checkpoints as owner permits.
- Handoff for each batch: `docs/INTELLIGENCE_EFFICIENCY.md` task ID, status, affected files, contract, tests, exact commit, CI, device state, cost trade-off. Update architecture and phone checks, not duplicate roadmaps. Rebase and test final candidate head. Any blocker should be recorded; continue independent safe work, do not fabricate results.

## Official implementation references (verify pinned SDK and Media3)

- Android Media3 battery consumption: https://developer.android.com/media/media3/exoplayer/battery-consumption
- Android wake-lock identification: https://developer.android.com/develop/background-work/background-tasks/awake/wakelock/identify-wls
- Android scheduling energy constraints: https://developer.android.com/develop/background-work/background-tasks/optimize-battery
- Work request constraints: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
- Connectivity update energy: https://developer.android.com/develop/connectivity/minimize-effect-regular-updates
- Pixel Power Profiler / ODPM: https://developer.android.com/studio/profile/power-profiler
- PowerManager public capabilities: https://developer.android.com/reference/android/os/PowerManager
- Media3 background playback: https://developer.android.com/media/media3/session/background-playback
- Macrobenchmark: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview

## First implementation instruction

Once the owner authorizes implementation, start by reconciling M0-M9 candidate and branch status. Then execute M10-M14 with tests and a resource ownership ledger, followed by M15-M29. In parallel only where safe, implement the pure recommendation event/teacher/graph contracts and produce a minimal offline radio vertical slice. Never make local playback wait for learning. Do not treat the 91 milestones as permission to implement 91 new features or force 91 device tests. The core objective is measurable efficiency and genuinely useful offline recommendations, then a clean feature freeze for design.

---

# G. Deep Engineering and Design Readiness (IE-M101-M140)

This mandatory final engineering extension supersedes only the scheduling of the earlier M99/M100 final gate. M99 remains the acceptance protocol, M100 freezes new feature scope and prepares the first design contract, but the consolidated physical acceptance and final engineering freeze occur at M139/M140 on the final candidate. Do not request separate manual testing for every milestone. Independent automated checks remain mandatory throughout. The previous M0-M9 stable/candidate release obligations are not waived. Do not start this extension against an unfinished or unpublished candidate without reconciling the actual work tree and ownership.

## Execution and evidence rules

- This is an audit and improvement assignment, not a request for 40 additional runtime features. Review all first-party production code, tests, build/release configuration, schema migrations, packaged native code, and resources that affect behavior or performance. Generated and third-party code is inventoried and assessed at its integration boundary; do not edit it line by line. Inspect source even if existing tests are green. Reuse the M0-M100 ledgers; cite an existing completed task rather than duplicate it.
- Inventory from `git ls-files` at a pinned commit. Record path, owner/module, behavior, execution frequency, lifecycle, inputs/outputs, possible failure, resource cost, evidence, action and reviewer status. Audit coverage is reviewed files / applicable tracked files, not lines rewritten. If a file changes after review, review the delta again. Include test helpers, CI and release tooling in their own categories. Every excluded path needs a reason. Never use percentage estimates as a substitute for a real ledger.
- Before changing a hot path, establish a reproducible baseline, identify cause, state the hypothesis and compare equivalent output on the same workload. Measure p50/p95/p99 where sample size supports it, allocation rate, CPU time, wakeups, bytes and energy as appropriate. Control compilation mode, input, cache state, route, volume, firmware, network, temperature and background load. Report raw samples and noise; do not claim small deltas within noise as wins. Preserve behavior and sound. Prefer deleting unnecessary work to making unnecessary work faster.
- Each finding has severity P0/P1/P2, evidence, reproduction, affected contract, proposed fix and verification. P0 includes wrong music, lost personal data, privacy leak, unauthorized network work in blackout, unsafe unexpected audio, crash loops and unbounded resource activity. An optional improvement may be rejected with evidence. An audit cannot pass with known unresolved P0 defects or silently unverified requirements.
- Use a single authoritative state for playback intent, source identity, queue occurrence, resource permission and recommendation generation. Do not introduce a second player, scheduler, DB authority or periodic background monitoring to audit the first one. No manual core affinity, hidden APIs, forced TPU/GPU, root or unsupported OEM controls. Kotlin/Java remains default. Native code is exceptional and must beat the existing path on actual Pixel with correctness, size and maintenance costs included.
- Keep stable signed releases gated; build candidate separately according to repo rules. Never publish a public branch or secrets without authorization. Candidate instrumentation must be finite, local, opt-in, and excluded from normal runtime where possible. A phone-only owner can provide a single compact report. Advanced profiling requires a capable workstation/device lab and may remain blocked. Do not label phone percentage data as app-specific power rail attribution.

## G1. Whole-repository correctness and simplification (M101-M110)

### M101 | Exhaustive source census [P0]
Scope: all tracked first-party files including app, core, source modules, manifest, Gradle, workflows and tests. Generate a reproducible coverage ledger with commit SHA and file counts per category. Classify entry points and cold/hot paths. Review every file for responsibility, invariants, dead code, unsafe assumptions and cost. Acceptance: 100% applicable files reviewed or explicitly blocked with reason; changed files re-reviewed; every finding linked to a regression or rationale. Do not confuse inspection coverage with test coverage.

### M102 | Reachability and dead-code elimination [P1]
Use call sites, R8 usage/mapping, manifest/components, reflection, serialization, resources, MediaSession external entry points and tests to distinguish unreachable code from externally invoked behavior. Delete obsolete paths and redundant adapters only after proof; keep schema compatibility and release metadata. Acceptance: smaller or simpler reachable implementation without lost capabilities; old APK upgrade and external entry point fixtures pass. No blanket resource stripping.

### M103 | Dependency and transitive-cost review [P1]
Inventory direct/transitive dependencies, licenses, native ABIs, initializer providers, duplicate networking/JSON/image stacks, security status, DEX and packaged size. Justify every runtime dependency; remove only truly redundant ones. Keep deliberate pins and check release R8 reflection behavior. Acceptance: dependency graph and decision record; no unnecessary startup initializer or ABI; unchanged supported behavior. Do not change versions merely to be current.

### M104 | Domain invariants and illegal-state removal [P0]
Review recording IDs, video IDs, file/source IDs, occurrence keys, timestamps, durations, account and model revisions, availability and playback intents. Strengthen type boundaries or validation where ambiguity causes errors; do not blanket-wrap primitives in hot loops without measurement. Acceptance: fixtures cover missing/invalid/stale values, duplicate occurrences, version ambiguity and overflow; illegal transitions cannot silently play another song.

### M105 | State ownership and synchronization [P0]
Map all mutable state and writer sites. Replace competing caches or duplicated authorities with clear owners, immutable snapshots or transaction boundaries. Inspect lifecycle of StateFlow, SharedFlow, callback, listener and mutable collection usage. Acceptance: each mutable authority has owner and consistency contract; no feedback loop or stale write after account/queue/resource generation changes.

### M106 | Exception and cancellation audit [P0]
Review every broad catch, runCatching, finally, timeout, callback bridge and coroutine cancellation. Do not swallow CancellationException, convert corruption into empty data or hide repeated errors. Distinguish transient, permanent, permission, user cancellation and unavailable provider cases. Acceptance: cancellation injection and exception matrix preserves last-good state; retries are bounded and user-meaningful; no misleading success.

### M107 | Arithmetic, clocks and limits [P0]
Use elapsed monotonic time for intervals/deadlines and wall clock only where calendar semantics are intended. Audit duration conversion, array indices, untrusted sizes, rate/sample counts, path/URI encoding, score normalization, zero/NaN/infinity, integer overflow, timezone and clock changes. Acceptance: adversarial boundary tests and fuzz fixtures; no unbounded allocation or wrong timer/recommendation due to invalid input.

### M108 | Coroutine and thread ownership [P0]
Inventory scopes, dispatchers, executor creation, jobs and blocking calls, including provider extractors and IO bridges. Ensure structured cancellation, bounded parallelism and application-thread ownership of Media3; distinguish IO from CPU work. Audit nested dispatcher hops and main-thread disk/network. Acceptance: leak/lifetime tests, cancellation and latency traces; no orphan jobs, unbounded queues or unnecessary permanent executor.

### M109 | Locking, backpressure and stale commits [P0]
Audit mutexes, synchronized blocks, locks spanning suspensions/IO, reentrant callbacks, channels and high-rate flows. Define queue/account/model/policy generation receipts; validate after every asynchronous boundary before commit. Preserve intentional enqueue and edit ordering; avoid replacing real commands with lossy conflation. Acceptance: deterministic interleavings, randomized race tests, bounded backlog and no deadlock; latest valid intent wins.

### M110 | Cross-module contract and complexity review [P1]
Review module edges, API surface, service/repository boundaries and abstractions added in M0-M100. Consolidate equivalent logic only when semantics agree. Remove obsolete feature flags and unused code paths, not required safety gates. Record why remaining complexity exists. Acceptance: no duplicated source resolver, recommendation engine or resource authority; maintainable dependency DAG and clear contracts for design.

## G2. Hot-path and storage engineering (M111-M120)

### M111 | Algorithmic complexity and adversarial scaling [P1]
Measure complete journeys at 1k/10k/50k recordings, large duplicate groups, long playlists, dense teacher graphs, CUE and Unicode. Identify repeated sorting, full scans, quadratic matching, collection copies and graph walks. Prefer indexed candidate retrieval, bounded top-k and incremental invalidation. Acceptance: complexity documented and measured at multiple sizes; no catalogue-wide work per audio tick or ordinary track transition; unchanged ranking/source correctness.

### M112 | Data representation and allocation review [P1]
Inspect Track/recording/source objects, maps/sets, boxed primitives, temporary lists, regex and string normalization. Use compact IDs, reusable immutable indexes, primitive arrays where measured, without changing durable identity. Acceptance: allocation profiles before/after critical journeys; fewer avoidable allocations with equal output and no unbounded interning/pools.

### M113 | Garbage collection and retained memory [P1]
Capture heap and allocation profiles across repeated browsing, 100+ transitions, restore, account switch and screen-off. Identify retained activity, bitmap, page, player, coroutine and model references. Separate Java, native, graphics and process caches. Acceptance: bounded memory after steady state, no monotonic retained-growth trend beyond workload; no manual `System.gc()` or arbitrary cache thrashing.

### M114 | SQL plans and index cost [P1]
Inventory DAO statements, transactions and Flow invalidations. Run EXPLAIN QUERY PLAN on representative databases, inspect N+1, giant IN clauses, full materialization and index write cost. Prefer projections, indexed joins and bounded transactions. Acceptance: query plans and measured latency/write amplification, migration and alias fixtures pass; no personal DB destructive fallback.

### M115 | Filesystem and database write amplification [P1]
Review checkpoint, statistics, teacher edge updates, cache and download publications. Batch safe derived writes but flush durable user actions appropriately. Bound WAL/temporary data; no aggressive checkpoint/vacuum schedule while playing. Inspect interrupted writes, volume removal, full storage, rename and MediaStore publication. Acceptance: fault injection shows atomic recoverability; measured bytes/write count improves where warranted; no history or playlist loss.

### M116 | Parsing and serialization hot paths [P1]
Audit provider JSON, tags, lyrics, model snapshots and MediaItem extras. Limit malformed/deep/huge input; avoid full reparse and redundant conversions when measurable. Preserve unknown/partial provider data and encoding. Acceptance: adversarial fixtures, bounded memory, no source/account regression; compare actual parser throughput and allocations.

### M117 | IPC and MediaSession payload economics [P0]
Measure large queue payload size, notification/widget updates, media browser pagination and Binder transaction failures. Prefer supported paged/compact commands where safe; retain occurrence and order. Avoid transferring bitmaps or entire model graphs via extras. Acceptance: large queues and duplicates work without TransactionTooLargeException; UI/session remain consistent on process death and reconnect; no unsupported Media3 API shortcuts.

### M118 | Network transport and request lifecycle [P1]
Audit all clients, sockets, DNS/TLS reuse, request deduplication, cache keys, timeout/backoff, cancellation, redirect and URL expiry handling. Prioritize active audio; prevent old account response writes and optional requests in forbidden modes. Use fake transport counts plus controlled network measurements. Acceptance: no repeated same-key requests without cause, cancellation propagates, blackout network contract holds; no TLS/DRM weakening or provider limit evasion.

### M119 | ARM64 and native/SIMD opportunity review [P2]
Inspect actual DSP, decoding and ranking profiles before considering vectorization. Prefer platform codecs, straightforward ART-friendly loops and existing optimized libraries. Only prototype native/NEON if measured hotspot is significant, with numerical reference tests, JNI overhead, memory safety, ABI/page-size compatibility and power measurement. Acceptance: ship only demonstrated net benefit on target route/device; otherwise document rejection. No mandatory NDK rewrite or chipset-name special case.

### M120 | Whole-path latency budget [P1]
Map tap-to-audio, search-to-row, skip-to-audio, launch-to-usable and offline ranking into stages. Report p50/p95/p99 for supported sample sizes, cold/warm and local/online separately. Remove synchronous work that does not belong on critical path. Acceptance: no learning/optional scan/network delay on confirmed local playback; improvement or documented baseline limits without trading correctness for a synthetic metric.

## G3. Energy engineering beyond generic tuning (M121-M130)

### M121 | Formal energy-mode transition contract [P0]
Extend the existing resource policy with explicit invariants and transition tables for dormant, browsing, local playback, streaming, paused, explicit transfer, charging, battery saver and blackout. Define allowed owners, clocks, wake locks, network, writes and CPU work. Test unknown inputs conservatively. Acceptance: exhaustive pure transition combinations and Android lifecycle mapping; no new service or timer just to check policy.

### M122 | End-to-end zero-work proof [P0]
Trace every route into optional work, including Flow subscriptions, process initialization, artwork/motion, lyrics, widget, downloads, learning, cache trim, callbacks and delayed retry. When closed/paused after settling, no app-owned periodic wakeup or autonomous request; during blackout none of the prohibited work. System process retention, legitimate active user work and platform callbacks are distinguished. Acceptance: adversarial fake scheduler/transport assertions and real idle trace when available; counters stop when owner ends.

### M123 | Race-free resource revocation [P0]
A new power/thermal/network policy must cancel queued and in-flight optional work, not merely deny future jobs. Guard commits with policy generation, cancel transport and release handles/semaphores; preserve required writes and user commands. Acceptance: policy changes during all work classes, including charge disconnect, display off and network loss; no runaway retries or late optional work publication.

### M124 | Audio-path power frontier [P1]
Compare eligible PCM and offload configurations by actual format, route, effects, playback speed, gapless requirements and firmware. Measure screen-off CPU residency, underruns, audio track lifecycle and energy. Use public Media3/device capabilities; actual hardware path may differ from requested path. Acceptance: select lowest measured cost that preserves owner's chosen quality and features; no automatic codec downgrade, DSP bypass or claimed Bluetooth lossless. Keep conservative fallback when offload is unsupported.

### M125 | Local playback minimum-work profile [P0]
Inspect local FLAC screen-off execution: media session, decoder, AudioTrack, route/focus, next-track prep, checkpoints. Suppress all provider metadata, artwork, graph training, UI ticks, scans and optional sync unless explicitly requested and permitted. Acceptance: no network audio bytes and no prohibited optional work; trace shows only legitimate playback operations; no gapless, seek, pause or headset regression.

### M126 | Streaming and modem energy frontier [P1]
Compare bounded buffer/preload policies on strong Wi-Fi, weak Wi-Fi and cellular, with realistic skipping patterns. Account for duplicate bytes, radio-active tails, buffer waste, stalls, rebuffer time and memory. Avoid extra reachability polling. Acceptance: choose measured policy by actual conditions; current audio wins over auxiliary traffic; no aggressive speculative warmup or excessive reconnect loop.

### M127 | Charging and thermal learning economics [P1]
Give each training/enrichment batch a budget in CPU time, IO bytes, network bytes and cancellable wall time, backed by actual usefulness. Gate expensive work on explicit consent and allowed charging/unmetered/thermal state. Coalesce events, checkpoint incremental stats, defer optional work if resources insufficient. Acceptance: no heavy unplugged training, no work on charging solely to fill arbitrary quota, no starvation of playback; compare learning quality per unit cost.

### M128 | Pixel platform scheduling and idle behavior [P1]
Use system traces to examine scheduler residency, wakeup chains, CPU time, IO waits, audio scheduling, display and radio behavior. Do not set CPU affinity, force frequency, manipulate system battery optimization or infer energy solely from CPU percent. Review release-mode ART/Baseline Profiles and task priorities. Acceptance: measured or documented unavailable device evidence; remove avoidable wakeups first, not platform-required audio execution.

### M129 | Matched energy comparisons and uncertainty [P0]
Define baselines M9 and current candidate, same files, codec, volume, route, network, screen state, firmware and battery temperature. Use repeatable 2h+ playback and 6-8h idle runs when practical, alternating runs to reduce bias; report raw charge/energy, elapsed time, variability and power rail categories where available. Compare against matched no-playback phone control. Acceptance: no claim of app-attributed energy from simple battery percentage; no regression beyond noise without documented correctness tradeoff. Stretch improvement remains 10-20% app-related energy only if avoidable work exists.

### M130 | Energy regression guard and release contract [P0]
Automate deterministic counts for work admission, tasks, retries, bytes, checkpoints and idle listeners. Maintain benchmark thresholds relative to stable baseline with tolerances; avoid flaky absolute host wall-time assertions. Physical evidence is a separate gate, not a mocked pass. Acceptance: unauthorized network/wakeup regression fails tests; no new performance budget exception without explicit rationale and owner review; no release claim of measured savings without measurements.

## G4. Recommendation intelligence verification and convergence (M131-M136)

### M131 | Teacher evidence quality and feedback bias [P0]
Audit provider response origin (radio, shelves, playlist, artist), order and explicit seed; treat co-recommendation as weak evidence rather than user approval. Distinguish exposed-but-unplayed, user skip, intentional queue choice, completion and failure. Deduplicate retries and repeated identical provider payloads; apply decay and confidence. Acceptance: tests prevent one long playlist, repeated response or provider error from dominating taste; account boundaries preserved.

### M132 | Offline ranking under sparse and biased data [P1]
Evaluate cold start, narrow artist library, multilingual credits, sparse tags, changed taste, missing teacher neighbors, unavailable candidates and explicit dislikes. Score only verified available candidates and fall back in ordered stages: strong graph, metadata/behavior, diversified safe local candidates. If no relevant match exists, do not fabricate one; handle small/empty library honestly. Acceptance: no unavailable auto picks, no endless repeat loops, stable deterministic tie-breaking, sensible coverage without online calls.

### M133 | Model comparison and energy/relevance Pareto frontier [P1]
Compare existing Mix, metadata/taste baseline, sparse teacher graph, and only justified richer variants. Use chronological held-out listening events and candidate availability as known at evaluation time; prevent future leakage and distinguish observed choice from unknown negatives. Measure NDCG@10, Recall@10 where labels allow, artist/track diversity, catalogue coverage, repeat rate, CPU, allocations, storage and offline latency. Include ablations and sample size; no claims of YTM-level relevance based on synthetic data. Acceptance: select simplest variant with credible value; report uncertainty for sparse single-user data.

### M134 | Model lifetime, stale data and recovery [P0]
Audit model schema versions, source aliases, account logout, newly added/removed files, exclusions, graph pruning and checkpoint corruption. Store bounded sufficient statistics and atomic last-good snapshot; avoid full rebuild at playback. If model missing or invalid, fall back immediately and rebuild only under allowed conditions. Acceptance: replay and migration fixtures, identical logical results after restart, no cross-account leakage or playback blocking.

### M135 | Recommendation safety and user agency [P1]
Ensure manual queue, album sequence, explicit repeat and user exclusions dominate recommendation ranking. Separate learned negative preference from hard exclusion; do not treat disconnection, phone call, sleep timer or buffering as dislike. Preserve newly chosen context and never overwrite accepted manual queue with late model output. Acceptance: stateful tests of online/offline switches, fast taps and feedback; understandable reason codes and model reset contract for design, no new UI required now.

### M136 | Long-run learning cost and actual usefulness [P1]
Simulate months of events, changing accounts, provider outages and library churn. Bound graph edges, tombstones, event retention and writes; quantify model growth and inference cost. Verify ranking works from stored model without network and optional acoustic analysis is absent unless proven worthwhile. Acceptance: stable storage/CPU ceiling and no ongoing activity in dormant/blackout; document limits of learning from a single listener and offline catalogue coverage.

## G5. Final integration, independent review and design gate (M137-M140)

### M137 | Independent defect review and scenario model [P0]
Review the entire final diff against M0-M100 contracts and new audit ledger; use an independent reviewer if available, otherwise a separate review pass and record lack of independent reviewer. Reproduce high-risk combinations with deterministic scheduling, fake transport, injected read/write faults and seeded sequences: rapid taps, process death, source removal, download completion, route switch, bad clock, low storage, weak network, logout, blackout during training, sleep timer and queue edits. Inspect audio/PCM golden fixtures, migrations and external session paths. Acceptance: all known P0 fixed and regressions green; review findings closed or explicitly deferred with evidence. No broad catch or skipped test used to hide failures.

### M138 | Complexity rollback, final build and reproducibility [P0]
Review every optional optimization added since M9: keep, simplify, or revert against correctness, measurable cost and real feature usefulness. Check no unused user-facing capabilities waiting for design have active runtime owners. Reconcile audit ledger at exact final SHA; run pure tests, pinned Android CI, debug/release lint, R8 release assembly, schema checks, APK signer and artifact digest. Verify candidate upgrade preserves data and Obtainium stable channel is not advanced prematurely. Acceptance: signed candidate and reproducible build evidence, or explicit blocker. No silent pin changes, public upload without authority, or downgrade/data rollback assumption.

### M139 | One consolidated Pixel acceptance session [P0]
Apply M99 protocol to the final candidate, not an older branch. Owner is phone-only by default: provide one clearly structured built-in candidate diagnostic or small test checklist/report with no mandatory PC. Collect first-tap source choice, local/online transitions, offline learned radio, pause/restore, Bluetooth controls/route changes, idle and screen-off battery under matched conditions. Use a capable device lab/workstation separately for Perfetto, Macrobenchmark, ODPM/power rail, audio onset and signal evidence. Do not claim such evidence from phone-only logs. Diagnostics are opt-in and bounded, redact secrets and file paths, and do not run constantly. Acceptance: automated gates plus actual device results, or individually listed blocked physical checks. A blocker does not equal pass; no stable promotion if mandatory release gate unmet. Avoid dozens of manual milestone tests.

### M140 | Final functional freeze and design-ready contract [P0]
Produce a single final report with M0-M140 reconciliation, exact commit, coverage ledger, open issues, measurement tables, recommendation evaluation, energy policy guarantees, capability matrix, release artifacts and limitations. Freeze domain APIs and state semantics for design: loading/empty/partial/offline/error, explicit source and playback control, queue edits, recommendation reasons, Blackout/Learning controls, diagnostics, accessibility semantics. Identify which optional features are deferred or awaiting user controls, and guarantee no active unused services. No additional internal redesign without an identified defect, measured regression, or new owner requirement. Acceptance: all mandatory software gates green, no unresolved known P0, design handoff complete; physical validation explicitly complete or blocked. Report `DESIGN_READY_SOFTWARE` if software gate is complete but device gates remain, `DESIGN_READY_VERIFIED` only after required physical acceptance. Owner can proceed to design on software-ready candidate while carrying explicit physical debt; stable remains subject to release gates. No claim that every line is mathematically optimal.

## Final engineering exit matrix

| Area | Software gate | Physical or real-data gate |
|---|---|---|
| Audit | Every applicable tracked file classified/reviewed; exact SHA | Independent review where available |
| Playback | Zero known P0; source, queue, cancellation and fault regressions | Actual Pixel first tap, route, headset, screen-off, seek and gapless |
| Energy | Zero prohibited app-owned work in dormant/blackout fixtures | Matched total-phone idle/playback runs and rail/trace evidence when accessible |
| Quality | DSP and source identity fixtures preserve requested output | Real route capabilities, decoder/output behavior, acoustic tests if available |
| Recommendations | Offline availability, deterministic ranking, bounded model and no training on critical path | Held-out owner data and subjective evaluation; no claim beyond available evidence |
| Data | Migration, fault recovery and durable user state | Candidate upgrade with owner data without exposing data |
| Performance | Reproducible latency and resource benchmarks with uncertainty | Release-mode target-device runs; no host-as-Pixel substitution |
| Delivery | Candidate artifact, CI, signature and release gate record | Installation/upgrade and owner approval under existing policy |
| Design | Stable functional APIs and state catalogue, no redesign | Remaining device debt explicit; new controls must not restart architectural churn |

### Proposed resource and quality acceptance budgets (extend earlier table, do not replace measured baseline)

- No app-created repeating job, alarm, polling loop, held wake lock or autonomous request after dormant settling. No speculative provider requests in Blackout; explicit user requests and legitimate ongoing audio are distinguished. Do not count system behavior as app behavior.
- Local screen-off playback: no audio network fetch; no recommendation training, motion decode or optional scans. Correct audio and user-selected effects take precedence over energy gains. Test speaker and XM5 independently; a supported offload path on one does not prove support on the other.
- Offline automatic radio: 100% eligible playable occurrences at selection time, bounded retries when files disappear later, no empty-loop retry. Warm rank p95 target <=50 ms at 10k, <=100 ms at 50k; 16-64 MiB initial graph cap including indexes. Revisit cap only with evidence. Learning should not add a synchronous dependency to first tap; measure whether latency is equivalent within noise rather than assert literal 0 ms CPU.
- Local playback first-tap and transition latency: use earlier provisional targets (200 ms and 100 ms respectively) only with clear event boundaries and actual route; distinguish player READY/isPlaying from audible onset. Report tail latency and underruns. Do not shave milliseconds by changing intended source or audio quality.
- Memory: bounded after warm-up, no retained growth over repeated journeys; report Java/native/graphics and PSS separately. No arbitrary minimum RAM target that creates unnecessary cache misses or storage/network work.
- Energy: zero prohibited work is a hard software invariant, not a promise of zero total-phone drain. 10-20% app-related saving vs M9 is a stretch if actual opportunity exists; do not extrapolate to battery hours without device measurements. Report absolute mWh/h or charge units where valid, relative delta and uncertainty. No acceptance pressure to fabricate improvements if baseline is already efficient.
- Recommendation relevance: aim meaningful positive held-out rank improvement (earlier +10% relative target is provisional), without sacrificing coverage, diversity or power. A sparse dataset may not support confident numerical conclusions. Keep simpler model if richer one is not demonstrably better.
- No mandatory design change before freeze, no new heavy infrastructure, no unsupported native path, no automatic audio quality degradation, no unapproved acquisition. Keep known limitations visible.

### Additional official implementation references

Verify the pinned Media3, Android SDK and target firmware before implementation. Current public documentation consulted for this extension:

- Android performance measurement: https://developer.android.com/topic/performance/measuring-performance
- System tracing: https://developer.android.com/topic/performance/tracing
- Memory profiling: https://developer.android.com/studio/profile
- Native memory profiling: https://developer.android.com/topic/performance/memory/guide/native-memory
- Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/overview
- Macrobenchmark metrics and supported Pixel power measurement: https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics
- Power Profiler / ODPM: https://developer.android.com/studio/profile/power-profiler
- ExoPlayer battery and offload: https://developer.android.com/media/media3/exoplayer/battery-consumption
- AudioSink configuration: https://developer.android.com/reference/androidx/media3/exoplayer/audio/AudioSink

These references support methods and capability investigation, not a prediction of Tensor G5 savings or proof that a specific Bluetooth route supports offload. The target phone and real builds remain the source of device evidence.
