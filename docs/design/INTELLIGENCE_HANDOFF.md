# Intelligence and playback design handoff

Working contract for the existing screens. The [execution ledger](../INTELLIGENCE_EFFICIENCY.md)
records software evidence and open gates. The [design system](../DESIGN_SYSTEM.md) owns visuals.

## Existing state contract

| Surface | State and user action | Source of truth |
| --- | --- | --- |
| Local library and search | An unopened index is loading. An empty successful query can show no matches. Local search works without provider access. Permission denial and scan errors retain their existing retry actions. | `AppGraph.index`, `LibrarySearchResult`, MediaStore permission and `LibraryViewModel.scanError`. |
| Provider search | Show results for the active query only. A displayed local match retains the original video intent. Suggestions can be absent when optional work is denied. | `OnlineSearch.query`, `OnlineSearch.requestAt`, visible scope and validated network. |
| Online album and playlist | A confirmed prefix stays playable while pages arrive. An incomplete or failed continuation can leave a partial list. Offline can reopen a disk snapshot, including a marked prefix. | `OnlineAlbum.partial`, the account revision's cursor and the account-scoped page store. |
| Play and pause | Connection, buffering, requested playback and audible playback are separate. A tap can stay pending; a later explicit pause takes precedence. A restored queue starts paused. | `PlayerState.connecting`, `buffering`, `playWhenReady`, `isPlaying`, and `queueRestored`. |
| Queue and source recovery | An unavailable occurrence retains position and intended identity. A missing file, offline source and ambiguous match have distinct reasons. Actions on duplicate songs target their occurrence keys. | `SourceIssue`, requested and playing sources, queue occurrence key, and the Media3 timeline. |
| Shuffle and repeat | Next and Previous refer to the actual player traversal. An explicit repeat mode protects the selected queue from automatic extension. | `PlayerState.nextIndex`, `previousIndex`, `shuffle` and `repeat`. |
| Audio path | Keep source format, decoder and actual route properties separate. Display unknown output properties as unavailable. Preserve the owner's EQ and ReplayGain choices. | `PlayerState.inputFormat`, `audio`, `equalizer`, and `replayGain`. |
| Offline radio | Offer the selected seed and a finite tail of eligible local recordings. Preserve manual order, explicit exclusions and hidden folders. A sparse library can legitimately yield a short tail. | `PlaybackMix`, current source policy, eligible catalogue, and verified teacher links when present. |
| Automatic work | Cached reads and explicit commands remain usable while automatic providers, scans, artwork and learning can be denied. Use individual reasons if an owner control eventually exposes them. | `ResourcePolicy`, live lease generation and last valid local snapshots. |
| Diagnostics | Existing audio details and a bounded, redacted phone-only report can capture behavior. Physical timing and energy require separate measurements. | [Physical acceptance procedure](../PHONE_CHECK.md). |

Actions should expose their state and availability to accessibility services. Playback intent,
actual playback and disabled commands need separate labels. Focus stays with the current control
when an asynchronous result changes a source or extends a list. Keep the current traversal,
geometry, colours, motion and approved artwork. Screenshot and TalkBack acceptance remain open.

## Design-only scope

| Topic | Contract |
| --- | --- |
| Resume an earlier queue and undo a queue edit | Require explicit selection and bind a bounded receipt to the queue generation. Preserve occurrence identity and cursor. No dormant runtime or public command is supplied for these optional controls. |
| Acquisition and coverage | Use verified eligible recordings with preference and storage evidence. Let the owner choose the source and any acquisition. Existing local files remain under owner control. |
| Blackout, learning and recommendation explanations | A future control can expose explicit consent, denial reasons or a clear/reset action. Use bounded evidence and explain uncertainty. Never disclose account headers, provider tokens or local paths. |
| Owner diagnostics | A future diagnostic control has a single, finite action and a redacted export. The current candidate procedure stays phone-only with no diagnostics background owner. |

Automated release gates, the full source reconciliation and actual Pixel acceptance are open.
This working contract is available for review while those checks remain outstanding.
