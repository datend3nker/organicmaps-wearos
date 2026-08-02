# Wear OS Companion — Upstreaming Roadmap

Status: draft, 2026-08-02. Owner: Ludwig Ettner (this fork). Not an official Organic Maps
document — keep it in this fork until the first PRs land, then track remaining work as
GitHub issues per upstream convention (`docs/ROADMAP.md` explains why upstream avoids
long-lived ROADMAP files).

## 0. Why this shape

Upstream context, pulled from the maintainers' own words before writing this plan:

- Discussion [#3273](https://github.com/orgs/organicmaps/discussions/3273) — this is the
  thread you (Ludwig) already posted the POC into (Mar 2026 initial announcement, Jun
  2026 "v0.2, all features I personally would wish for", Komoot-inspired UI direction).
  Maintainer `biodranik`'s reply (Jun 30, 2026) is the concrete brief to build against:
  a **"simple, polished/easy-to-use companion app (relying on the smartphone) that will
  show/announce the next directions during walking/cycling navigation."** Car
  turn-by-turn is explicitly out (safety). His recommended sequencing, verbatim in
  intent: *the initial contribution should be an empty watch app with backend
  communication established in the phone app, features added incrementally after* —
  which is exactly phase 1 → phase 2 below. "Announce" implies audio/haptic cues belong
  in the turn-indicator scope, not just a visual arrow — see phase 2.
- Discussion [#12029](https://github.com/orgs/organicmaps/discussions/12029) — the
  requested shape is exactly what this fork already does: search + routing stay on the
  phone, the watch only renders the next-turn prompt. Maintainer response: *"any Android
  volunteer with Wear OS device is welcome to contribute"* → tracking issue
  [#12030](https://github.com/organicmaps/organicmaps/issues/12030) (currently empty,
  no checklist — this document is written so it can become that checklist).
- Issue [#12200](https://github.com/organicmaps/organicmaps/issues/12200) — people are
  already sideloading the *phone* APK onto watches today and hitting round-display
  clipping, dead compass sync, and one-way zoom. Any watch-native UI must be built
  round-safe from screen one (Wear Compose `ScreenScaffold`, curved text, no
  corner-anchored controls), and must fix compass/GPS sensor fusion properly rather than
  inheriting the phone's flat-screen assumptions.
- Issue [#11969](https://github.com/organicmaps/organicmaps/issues/11969) — device
  coverage expectation includes non-Google watches (Huawei/HarmonyOS-adjacent), which is
  a data point in favor of keeping the sync transport abstracted (this fork already does
  this — see §2) rather than hard-wiring Google Play Services.

Net effect on sequencing: **turn indicators, pedestrian/cyclist framing, round-safe UI,
and battery discipline come before anything else** — including before full map
rendering, search, or bookmarks. That matches what you already asked for, and it's also
what will actually get merged.

This fork (`android/app/omaps`, ~12.5k lines) is a working proof of concept that already
proves out a hard problem — a transport-agnostic phone↔watch sync protocol with GMS and
Bluetooth backends, virtual-MWM map streaming with sparse-file caching, LWW settings
sync, and union-merge bookmark/track sync (all documented in
`android/.artifacts/021fa60f-9376-4093-bd38-ecbc80da72f0/WearOS_Protocol.artifact.md`).
None of that ships as-is: upstream's own PR guide (`docs/PR_GUIDE.md`) treats ~100 lines
as a normal PR and ~1000 as "big," requires tests alongside the feature they cover, and
expects each PR to be one idea. The rewrite's job is to **re-derive this POC as a
sequence of small, independently-reviewable, tested PRs**, in the order the maintainers
already told us they want.

## 1. Fork & branch how-to

1. Add the real upstream and keep your fork as the push target:
   ```bash
   git remote add upstream https://github.com/organicmaps/organicmaps.git
   git fetch upstream
   ```
   `origin` stays `datend3nker/organicmaps-wearos-vibe` (or rename the GitHub fork to
   `organicmaps` if you want the PR button to point at it cleanly — either works, `gh pr
   create` handles both).
2. Freeze the current branch as reference material, don't build PRs on top of it:
   ```bash
   git branch poc/wear-reference   # current master, kept as read-only lookup
   ```
   Every clean PR branch below starts from `upstream/master`, not from this branch. You
   *port* logic out of `poc/wear-reference` into small commits — you don't rebase or
   cherry-pick the POC's actual commits, because they're bundled (one commit = five
   features) and pre-tests.
3. One branch per roadmap phase, stacked only when a phase genuinely depends on an
   unmerged earlier one:
   ```bash
   git checkout upstream/master -b wear/01-skeleton
   # ...port code, add tests, commit in small reviewable chunks...
   git push origin wear/01-skeleton
   # open PR: base=organicmaps:master, head=<your-fork>:wear/01-skeleton
   ```
   Naming: `wear/<phase-number>-<slug>`, matching the phases in §3.
4. Once phase N merges upstream, rebase phase N+1's branch onto the new
   `upstream/master` (not onto your local unmerged stack) before opening it, so the diff
   reviewers see is minimal and accurate.
5. Delete `poc/wear-reference` locally once you've ported everything you need from it —
   don't push it to `origin` under a name that could be mistaken for a real PR branch.

## 2. Non-negotiables carried over from the POC

The protocol doc already got these architectural calls right; preserve them even in the
minimal PR#1 rather than re-discovering them later:

- **Transport-agnostic sync layer from day one.** Define `ISyncLayer`/message framing so
  a second backend (Bluetooth) is an additive implementation, not a rewrite. Land GMS
  first for velocity; Bluetooth is still required before this is FOSS-complete (OM ships
  a GMS-free `oss` flavor — a watch feature that only works on `gms` isn't upstream-able
  as the sole path).
- **`MAX_MESSAGE_TYPE` sanity bound.** The POC already hit the regression where a stale
  bound silently rejected a new message type and killed every BT session. Turn this into
  a compile-time check (e.g. a `static_assert`-equivalent unit test that fails the build
  when a `TYPE_*` constant exceeds the bound) instead of relying on someone remembering
  to bump it.
- **Round-display-safe UI from the first screen**, per issue #12200.

## 3. PR sequence

Each phase = one PR (or a tight 2-3 PR sub-stack where noted). "Port from POC" points at
the existing file so you're translating working logic, not designing from scratch.
Every phase's acceptance criteria includes tests — PR_GUIDE.md requires functionality
and its tests to land together, and the wear module currently has **zero tests**, so
this isn't optional per-phase polish.

| # | Phase | Goal |
|---|-------|------|
| 1 | Watch app skeleton + liveness | Prove a watch app exists and can talk to the phone |
| 2 | Turn indicator screen | The MVP you asked for: next-turn icon + distance, walking/cycling only |
| 3 | Connection reliability | Heartbeat, timeout, reconnect backoff |
| 4 | Minimal settings sync | Routing profile + units, so the right icon set is used |
| 5 | Live bearing/compass screen | Non-map orientation screen (issue #12200's broken compass, fixed properly) |
| 6 | Complication + tile | Glanceable next-turn without opening the app |
| 7a–c | Map streaming core | Native virtual-MWM reader, sparse cache, bounded LRU eviction |
| 8 | Map panel UI | Pan/zoom viewport backed by phase 7 |
| 9 | Search | Headless phone search, results rendered on watch |
| 10 | Bookmarks sync | Union-merge + de-dup, with tombstones (POC explicitly lacks these) |
| 11 | Track recording sync | Watch-recorded tracks flow back to phone |
| 12 | Map download management | Copy-to-watch, phone-unavailable fallback to direct download |
| 13 | Bluetooth transport | Second backend for GMS-free builds |
| 14 | Standalone mode | Watch works with zero phone, own maps + own routing |
| 15 | Notifications + settings parity | Nav notifications, full settings screens |

### Phase 1 — Watch app skeleton + liveness
**Branch:** `wear/01-skeleton`
**Port from POC:** `WearApplication.kt`, `Omaps.kt` (trim to launcher activity + theme
only), `WearCommandService.kt` (trim to init + ping/pong), `WearProtocol.java` (version
byte + `TYPE_PING`/`TYPE_PONG`/`TYPE_HANDSHAKE` only — drop the other ~28 message types
for now), phone side `WearMessageRouter.java` (routing skeleton only).
**Scope:** new Gradle module (rename `android/app/omaps` → `android/wear`; the
`omaps`/`com.example.omaps` naming is a leftover Android Studio Wear-template default,
not an intentional name — clean it up now so reviewers aren't confused with the
unrelated "OMaps" community fork). Minimal manifest, standalone=false, launcher activity
showing "connected"/"not connected". Phone: capability detection only, surfaced in
existing settings UI (`WearOsSettingsFragment.kt`).
**Explicitly out:** Bluetooth backend, any nav/map/bookmark/search code, complications.
**Tests:** protocol framing round-trip (encode/decode header + version), handshake state
machine.
**Size:** ~250-400 lines. This is the PR that proves the module structure to reviewers.

### Phase 2 — Turn indicator screen
**Branch:** `wear/02-turn-indicators`
**Port from POC:** `NavigationIcons.kt` (as-is, it's already a clean pure function),
`NavigationStateHolder.kt` (trim to turn icon + distance + street name fields),
`message/NavStatusHandler.kt`, phone side `HeadlessRouteInteractor.java` (hook into the
existing `RoutingController` callback to publish nav state — don't reimplement routing).
A trimmed `NavigationScreen.kt` with no map, just an arrow + distance, laid out with Wear
Compose `ScreenScaffold` so it doesn't clip on round bezels.
**Scope decision per maintainer feedback:** default the demoed/tested mode to
pedestrian/cyclist. Car `CarDirection` icons can stay in the shared enum (phone already
has them) but don't lead the PR description with car navigation — that's the thing
`biodranik` explicitly pushed back on.
**"Show/announce" — don't drop the announce half:** biodranik's brief pairs visual with
audible. A minimal haptic tick (`Vibrator`) or TTS/tone on turn approach is small to add
and directly matches the brief; worth including in this PR or as a fast phase 2b rather
than leaving it implicit.
**Tests:** `NavigationIcons` icon-selection unit tests (pedestrian vs car precedence,
exit-number variants), nav-state message parsing.
**Size:** ~300-500 lines + drawables. This is the milestone you asked to lead with.

### Phase 3 — Connection reliability
**Branch:** `wear/03-heartbeat`
**Port from POC:** `HeartbeatManager.kt` as documented in the protocol doc (§4): 15s
ping when idle, 45s timeout → disconnected, exponential backoff to 5 min while
disconnected.
**Why now, not later:** every phase after this assumes "connected" means something.
Landing it right after the MVP means phases 4+ build on a trustworthy signal instead of
needing a reliability patch retrofitted later.
**Tests:** timeout transition, backoff schedule, activity-reset-on-any-message.

### Phase 4 — Minimal settings sync
**Branch:** `wear/04-settings-sync`
**Port from POC:** `SettingsSyncManager.kt`/`SyncStateManager.kt`, trimmed to routing
profile + distance units only (not the full settings surface).
**Tests:** LWW merge (newer timestamp wins), including the documented clock-skew caveat
as a known limitation in the PR description (see §4).

### Phase 5 — Live bearing/compass screen
**Branch:** `wear/05-compass`
**Port from POC:** `presentation/navigation/SensorViewModel.kt` (bearing pointer already
built in commit `57acca4e5c`), fixing the exact sensor-fusion gap issue #12200
complains about (compass not syncing with device sensors).
**Tests:** bearing smoothing/filter math (this is the kind of pure-function logic that's
cheap to unit test and easy to regress silently).

### Phase 6 — Complication + tile
**Branch:** `wear/06-complication`
**Port from POC:** `complication/NavigationComplicationService.kt`.
**Why here:** cheap, high visibility "glanceable" win the maintainers' battery framing
favors — showing next-turn on the watch face means the user doesn't even open the app.

### Phase 7 — Map streaming core (7a/7b/7c sub-stack)
**Port from POC:** `VirtualMwmManager.cpp` + `VirtualMwmManager.kt`.
- 7a: native reader + sparse-file cache, no eviction, single-region test case.
- 7b: bounded LRU eviction with the free-space-derived budget (24-192MB clamp).
- 7c: the pinned-mmap-region fix for the succinct features-offsets table (protocol doc
  §3.2 — without this, eviction can punch an in-progress fetch and abort with
  `CHECK(!def.empty())`).
**Tests:** this is the highest-risk native code in the whole rewrite — needs unit tests
for the eviction budget math and an integration test that streams a known MWM and
verifies byte-for-byte reads against the source file, both before and after forced
eviction.
**Note:** this is the first phase that's genuinely "big" (upstream's ~1000-line
threshold) — expect it to need the 3-way split above rather than fitting in one PR.

### Phase 8 — Map panel UI
**Branch:** `wear/08-map-panel`
**Port from POC:** `presentation/MapPanel.kt`. Depends on phase 7.

### Phase 9 — Search
**Branch:** `wear/09-search`
**Port from POC:** phone `HeadlessSearchInteractor.java`, watch
`presentation/search/SearchScreen.kt`. Note the protocol doc's open gap: results capped
at 15-20 items, no pagination — either fix it in this PR or state the cap explicitly in
the PR description so it's a conscious tradeoff, not a silent limitation.

### Phase 10 — Bookmarks sync
**Branch:** `wear/10-bookmarks`
**Port from POC:** `WatchBookmarkSyncManager.kt`, `presentation/bookmarks/
BookmarksScreen.kt`, the KMZ-sniffing receiver logic (protocol doc §3.4 — payloads must
be sniffed for the ZIP magic number and saved as `.kmz`, not trusted by path/type, or
imports silently fail and the sync storms).
**Close the known gap, don't defer it:** the POC has no tombstone mechanism, so a
bookmark deleted on one device reappears from the other on next sync. Add a
tombstone list (same identity: name + quantized position, plus deletion timestamp)
in this PR rather than shipping known data-resurrection behavior upstream.
**Tests:** merge idempotency (repeated sync doesn't duplicate), LWW-on-collision,
tombstone suppresses resurrection.

### Phase 11 — Track recording sync
**Branch:** `wear/11-tracks`
**Port from POC:** `WatchTrackSyncManager.kt`, `presentation/track/TrackScreen.kt`,
`presentation/navigation/StatsScreen.kt` (elevation/distance/time/battery — directly
matches what issue #12200 asked for).

### Phase 12 — Map download management
**Branch:** `wear/12-map-downloads`
**Port from POC:** `WearMapDownloader.kt` (both `gms`/`oss` variants),
`presentation/downloads/MapManagerScreen.kt`, phone `WearMapStreamingHelper.java`.
Phone-first pull, fallback to direct internet download when phone lacks the map and
watch has connectivity (protocol doc §3.7).

### Phase 13 — Bluetooth transport
**Branch:** `wear/13-bluetooth`
**Port from POC:** `BluetoothWearSyncBackend.kt`, `BluetoothWearDataListenerService.kt`,
phone `BluetoothMessageListenerService.java`, `oss/WearSyncService.java`.
**Close the known gap:** add sequence numbers + retry/ACK for non-streaming messages
(protocol doc §5.3 flags this as missing) — Bluetooth is the path GMS-free builds
depend on, so it can't be flakier than the primary transport.

### Phase 14 — Standalone mode
**Branch:** `wear/14-standalone`
Watch operates with its own downloaded maps, own search, own routing, zero phone
required. Biggest independence milestone; depends on phases 7-9, 12-13 all being merged.

### Phase 15 — Notifications + settings parity
**Branch:** `wear/15-notifications-settings`
**Port from POC:** `WearNotificationManager.kt`/`WearCompanionNotificationManager.java`,
remaining settings screens (`LayerSettingsScreen.kt`, `PoiSettingsScreen.kt`,
`RoutingOptionsScreen.kt`).

## 4. Hardening backlog (from the POC's own "Known Weaknesses" section)

Attach each to the phase it blocks, don't let them pile up as a vague tail-end phase:

- **Clock-skew LWW** (protocol doc §5.1): wall-clock timestamps break if either device
  has manual clock drift. Fine to ship in phase 4 with the limitation documented; worth
  a follow-up PR moving to a monotonic version counter per key before standalone mode
  (phase 14) makes conflicts more frequent.
- **Bookmark tombstones** — fold into phase 10, not deferred (see above).
- **Bluetooth ACK/retry** — fold into phase 13, not deferred (see above).
- **Search pagination** — decide explicitly in phase 9 (see above).
- **Capability discovery before heavy sync** — never assume the watch app is installed;
  needed by the time phase 7/12 start pushing large payloads.
- **Batched preference updates** — nice-to-have, fold into phase 4 or 15 if the naive
  one-message-per-setting approach shows up as a review comment.

## 5. Repo cleanup before the first PR goes out

- `android/.artifacts/**` (7 files, agent-generated planning docs including the protocol
  doc this roadmap leans on) is currently committed to git. Move the protocol doc
  content into `docs/` if you want to keep it versioned, then delete the `.artifacts`
  directory and add it to `.gitignore` — none of this should appear in an upstream PR.
- Zero tests currently exist under `android/app/omaps`. Every phase above lists what to
  add; don't let phase 1-2 ship without establishing the test scaffolding (module-level
  test source set + a couple of trivial tests) so later phases have a pattern to follow.
- Module rename `android/app/omaps` → `android/wear` (see phase 1) — do this as the
  first commit of phase 1, separately from behavioral changes, so the diff is a clean
  rename reviewers can skim past.

## 6. After phase 2 ships

Post progress against issue [#12030](https://github.com/organicmaps/organicmaps/issues/12030)
— it currently has no checklist, and a maintainer already said contribution is welcome.
Turning phases 1-6 into that issue's checklist (or a linked project board) gives
reviewers visibility before phase 7's map-streaming work, which is the first genuinely
large/risky chunk, lands.
