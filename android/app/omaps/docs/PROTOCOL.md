# Organic Maps — Wear OS Companion Protocol

This document defines the wire protocol between the **phone** app (`android/app`) and the **watch**
app (`android/app/omaps`). It is **transport-agnostic**: the same logical messages flow over either the
Google Play Services (GMS) Wearable API or a raw Bluetooth RFCOMM/TCP stream.

Authoritative source of truth: [`WearProtocol.java`](../../../sdk/src/main/java/app/organicmaps/sdk/sync/WearProtocol.java)
(in the shared `sdk` module, package `app.organicmaps.sdk.sync`). The constants below mirror that file —
**when you add a message, update both that file and this table.**

See also: [ARCHITECTURE.md](ARCHITECTURE.md) for how the components fit together.

---

## 1. Layers

| Layer | Responsibility | Phone | Watch |
| :--- | :--- | :--- | :--- |
| **Transport** | Move bytes | `ISyncLayer` → `GmsSyncLayer` / `BluetoothSyncLayer` | `IWearSyncBackend` → `GmsWearSyncBackend` / `BluetoothWearSyncBackend` |
| **Framing** | Frame/deframe messages | GMS path-based / BT type-based | same |
| **Routing** | Dispatch to handlers | `WearMessageRouter.java` | `WearMessageRouter.kt` → `WearMessageDispatcher` → `message/*Handler` |
| **Application** | Sync logic | `WearSyncService`, `HeadlessRouteInteractor`, … | `WatchBookmarkSyncManager`, `VirtualMwmManager`, … |

Every logical message has **both** a string **path** (e.g. `/navigation/status`) and a numeric **type**
(e.g. `1`). GMS routes by path; Bluetooth routes by type. `WearProtocol` keeps a bijective
`path ↔ type` registry so the watch's Bluetooth receiver can convert a received type back to a path and
feed the **same** `WearMessageRouter` entry point as GMS — keeping control-plane and gating logic
identical across transports.

---

## 2. Framing

### 2.1 GMS (path-based)

GMS messages carry their routing in the named path (`MessageClient.sendMessage(nodeId, path, bytes)`).
Large/streamed payloads use `ChannelClient`/`DataClient`. The payload is the raw message body; some
bodies are GZIP-compressed (see §6.3).

### 2.2 Bluetooth (type-based)

Bluetooth is a raw stream, so each message is length-prefixed with a fixed 6-byte header:

| Bytes | Field | Notes |
| :---: | :--- | :--- |
| 1 | `PROTOCOL_VERSION` | currently `1` |
| 1 | `MESSAGE_TYPE` | numeric id (see §3) |
| 4 | `PAYLOAD_LENGTH` | big-endian int, `0 … 20 MB` |
| N | `PAYLOAD` | message body |

> **⚠ Header sanity bound.** Both sides reject a frame whose `MESSAGE_TYPE > MAX_MESSAGE_TYPE`
> (currently **25**) or whose length is out of range, treating it as stream desync. `MAX_MESSAGE_TYPE`
> **must** stay `≥` the largest `TYPE_*`, or new message types silently break Bluetooth.
> *(Regression history: a stale bound of `20` rejected `TYPE_HANDSHAKE=21` and killed every BT session in
> a reconnect loop.)*

> **Emulator transport.** On emulators, Bluetooth falls back to **TCP `10.0.2.2:5610`**; the phone binds a
> TCP `ServerSocket` on `5610` only while its selected backend is `BLUETOOTH`. Bridge the two emulators
> with `adb -s <phone-serial> forward tcp:5610 tcp:5610`.

> **Single-owner socket (watch).** The Bluetooth **listener service** (`BluetoothWearDataListenerService`)
> is the *sole* owner of the connection — it runs the read loop. The **sender**
> (`BluetoothWearSyncBackend`) never opens its own socket; it waits for the shared connection and closes
> it via `dropConnection()` on a write error. *(A sender-created socket has no reader, so replies on it are
> lost and the phone ends up juggling two client sockets — the historical startup EOF/reconnect churn.)*

---

## 3. Message catalogue

Paths and types are defined in `WearProtocol`. Direction is the **typical** flow (most are situational).

| Type | Path | Dir | Purpose |
| :---: | :--- | :---: | :--- |
| 1 `NAV_STATUS` | `/navigation/status` | P→W | Live navigation/location: position, bearing, next turn, ETA |
| 2 `SEARCH_RESULTS` | `/search/results` | P→W | Search results (incl. `isSearching` streaming flag) |
| 3 `SEARCH_HISTORY` | `/search/history`, `/search/history/sync` | P↔W | Recent search history |
| 4 `PREFERENCES` | `/preferences/phone`, `/preferences/watch` | P↔W | Full settings snapshot |
| 5 `MAP_DOWNLOAD_REQUEST` | `/map/download/request` | W→P | Request full-file copy of a region |
| 6 `MAP_TILE_RESPONSE` | `/map/tile/response` | P→W | (legacy tile response) |
| 7 `MAP_DOWNLOAD_PROGRESS` | `/map/download/progress` | P↔W | Copy/download progress |
| 8 `TRACK_RECORDING` | `/track/recording`, `/track/recording/toggle` | P↔W | Track-recording state/toggle |
| 9 `BOOKMARKS` | `/bookmarks`, `/bookmarks/request` | P↔W | Bookmark category list |
| 10 `COMMAND` | `/ping`,`/pong`,`/launch`,`/poi/show`, … | P↔W | Generic control commands (default type) |
| 11 `MAP_CHUNK` | (full-copy stream) | P→W | Chunk of a full `.mwm` being copied to the watch |
| 12 `BOOKMARK_FILE` | `/bookmark/file` | P↔W | KMZ/KML category file (chunked) |
| 13 `VIRTUAL_MWM_REQUEST` | `/virtual_mwm/request`, `/virtual_mwm/metadata_request` | W→P | On-demand byte range / header+footer of a streamed map |
| 14 `VIRTUAL_MWM_DATA` | `/virtual_mwm/data` | P→W | Requested map bytes |
| 15 `VIRTUAL_MWM_MOUNT` | `/virtual_mwm/mount` | P→W | Map metadata (size, header/footer) to "mount" a sparse map |
| 16 `ROUTE_BUILD_PROGRESS` | `/navigation/route_build_progress` | P→W | Route-calculation progress (0–100, `-1` = error) |
| 17 `BOOKMARK_RENAME` | `/bookmark/rename` | P↔W | Category rename |
| 18 `BOOKMARK_DELETE` | `/bookmark/delete` | P↔W | Category delete |
| 19 `PREFERENCES_UPDATES` | `/preferences/updates` | P↔W | Partial (dirty-only) settings updates |
| 20 `MAP_PHONE_DOWNLOADED` | `/map/phone/downloaded` | P→W | List of regions present on the phone |
| 21 `HANDSHAKE` | `/handshake` | P↔W | App-to-app capability/version handshake |
| 22 `BOOKMARKS_METADATA` | `/bookmarks/metadata` | P↔W | Per-category counts + `last_local_edit` / `last_synced` timestamps |
| 23 `BOOKMARK_TOMBSTONE` | `/bookmark/tombstone` | P↔W | Record of an individually-deleted bookmark |

Control-only paths without a dedicated type (routed as `COMMAND`/handled in the router):
`/navigation/start`, `/navigation/stop`, `/search/query`, `/search/select`, `/preferences/request`,
`/preferences/trigger`, `/backend/switch`, `/map/download/cancel`, `/map/download/not_found`,
`/bookmark/sync/request`, `/bookmark/visible/toggle`, `/bookmark/show`, `/bookmark/update`.

**Priorities** (`getPriority`): control + nav are HIGH; settings/bookmarks/progress are MEDIUM;
bulk transfers (map chunks, bookmark files, virtual-MWM data) are LOW.

---

## 4. Liveness & handshake

A **physical link** (BT pairing / GMS reachability) is *not* the same as an **app-to-app connection**.

- **Ping/Pong** (`/ping` ↔ `/pong`): sent if no other traffic has occurred; any valid frame resets the
  activity timer. The watch marks itself "connected" only when a gated message from the **selected**
  backend arrives.
- **Timeout**: no message for ~45 s ⇒ app connection considered lost even if the transport is "up".
- **On reconnect**: the side that connects pushes `/preferences/request`, `/bookmarks/request`,
  `/search/history/request` (and a `World` metadata request if needed) to resync state.

---

## 5. Sync algorithms

### 5.1 Settings (LWW)

Settings map between **canonical keys** (`SETTING_*`, protocol names) and **local keys** (Android
`SharedPreferences` keys) via `SyncSettingsRegistry`. Each side stamps a user change with a 64-bit
timestamp + version counter and marks it dirty (`BaseSettingsSyncManager`). The receiver applies an
update only if `remote.version > local.version || (== && remote.ts > local.ts)`. Dirty updates are
pushed as the compact `PREFERENCES_UPDATES` (partial) rather than a full snapshot, breaking sync loops.

### 5.2 Bookmarks (non-destructive union merge)

Background bookmark sync is **always a de-duplicating union-merge** — never a category-level overwrite.

1. **Handshake** (`BOOKMARKS_METADATA`): each side sends, per category, the bookmark/track counts plus
   `last_local_edit` and `last_synced` timestamps.
2. **Decision**: `localChanged = last_local_edit > last_synced`; `remoteChanged` from the peer's
   timestamps. `remoteChanged` ⇒ pull the peer's file; `localChanged` ⇒ push ours; **both** ⇒ push *and*
   pull (each side computes the same union; nothing is lost).
3. **Transfer format (critical).** A category is exported with `prepareCategoriesForSharing(...)`, which
   for **every** `KmlFileType` (including `Text`) wraps the `.kml` in a **KMZ (ZIP)** archive. OM's loader
   picks the parser **by file extension**, so the receiver must sniff the magic
   (`50 4B 03 04` = ZIP ⇒ save as `.kmz`, else `.kml`). Saving ZIP bytes as `.kml` makes import silently
   fail; the category never persists, so the handshake keeps reporting it "missing" and re-requests it
   forever — a **re-sync storm** (symptom: a "Bookmarks synchronized" toast ~once per second).
4. **Merge** (`nativeMergeCategories(src, dst)`): identity = preferred name + position (Mercator,
   quantized to ~1 m). Duplicates already in `dst` are not re-added (idempotent); on a same-identity
   collision the **newer `GetTimeStamp()` wins** (LWW); tracks are de-duplicated by name.
5. **Termination**: after import the receiver sets `last_synced`; because the merge runs under
   `isApplyingRemoteUpdate` it does **not** bump `last_local_edit`, so the next handshake sees
   `last_local_edit ≤ last_synced` and stops — the bidirectional resolution cannot ping-pong.

### 5.3 Bookmark deletions (tombstones)

A union-merge can never *remove*, so a bookmark deleted on one device would be resurrected from the
other. Deletions are propagated as **tombstones** (`BOOKMARK_TOMBSTONE`, shared
[`BookmarkTombstoneStore`](../../../sdk/src/main/java/app/organicmaps/sdk/sync/BookmarkTombstoneStore.java)):

- **Identity / wire**: `lower(category)|lower(name)|round(lat·1e5)|round(lon·1e5)` (same identity as the
  native merge). Payload: `[ts:8][catLen:4][cat][nameLen:4][name][lat:8][lon:8]`.
- **Detect**: each side keeps a per-category identity snapshot; an identity that disappears on a genuine
  user edit becomes a tombstone (recorded + sent). Re-creating a bookmark with the same identity clears
  its tombstone. (Watch: `WatchBookmarkSyncManager.detectBookmarkDeletions`; phone:
  `WearSyncService.detectBookmarkDeletions`.)
- **Apply**: an incoming tombstone deletes the matching local bookmark. After **every** file merge both
  sides re-apply all stored tombstones (`BookmarksLoadingListener`) to purge bookmarks the union just
  resurrected. All applies run under `isApplyingRemoteUpdate` to avoid echo loops. Tombstones are
  flushed on each sync (covers deletions made while disconnected) and GC'd after **30 days**.

### 5.4 Search routing

Decided on the watch: `STANDALONE`, `watchLocalMode` (Local Maps), or phone-unreachable ⇒ query the
**on-watch** `SearchEngine`; otherwise forward to the phone. The watch arms a **12 s watchdog** per
search and clears the spinner immediately if the local engine declines the query, so the spinner can
never hang.

### 5.5 Map acquisition — two distinct mechanisms

- **Viewport streaming (Virtual MWM)**: genuinely range-based. The watch mounts a **sparse** `.mwm`
  pre-sized to the full logical length; the native `VirtualModelReader` gates every read through
  `WaitForData`, faulting 64 KB blocks on demand (`VIRTUAL_MWM_*`). A bounded LRU cache caps physical disk
  use to a budget auto-derived from free space; cold non-pinned blocks are evicted via
  `fallocate(PUNCH_HOLE)`. mmap'd succinct regions (offsets table) are **pinned before fetch** and never
  evicted.
- **Copy-to-Watch (full file)**: `MAP_DOWNLOAD_REQUEST` + `MAP_CHUNK` stream a whole region to watch
  storage. Phone-first; on `/map/download/not_found` with internet available, fall back to a direct
  download.

### 5.6 Backend selection & switching

The active transport is user-selected (`pref_wear_os_backend`: `GMS` / `BLUETOOTH` / `STANDALONE`) and is
the single source of truth — never changed silently. `/backend/switch` is sent over the **current**
transport before tear-down; the watch router processes **control-plane** paths from any transport but
**drops data-plane** messages from the non-selected backend.

### 5.7 Navigation initiation

The watch sends `/search/select` (lat/lon + router type); the phone's `WearMessageRouter` calls
`HeadlessRouteInteractor.planRoute(...)`. The interactor registers as a **`RoutingController.RouteEventListener`
observer** — *not* as the single UI `Container` — so it forwards route progress / `/navigation/start` to
the watch without stealing route callbacks from the phone's `MwmActivity`. *(Registering as the Container
previously broke navigation on both devices.)*

---

## 6. Performance & resource management

- **Heartbeat/backoff**: ~15 s ping while active; exponential backoff (to several minutes) while
  disconnected; any app message resets the timer.
- **Dedup**: `WearMessageRouter` drops a payload whose hash matches the last one on the same path within
  a short window (≈200 ms search / 500 ms default), preventing storms.
- **Compression**: large bodies (map data, search results, MWM chunks) are GZIP-compressed; a 1-byte flag
  signals it to the receiver.

---

## 7. Known weaknesses / future work

- **Clock skew**: LWW relies on wall-clock `System.currentTimeMillis()`; a device with a skewed clock
  always wins. Consider per-key logical version counters (partially in place for settings).
- **No app-level ACKs over Bluetooth**: critical non-streaming commands have no retransmit; consider
  sequence numbers + retry.
- **Same-name categories created offline** on both devices union correctly by name but keep divergent KML
  GUIDs; a canonical-id reconciliation is still open.
- **Search pagination**: results are capped (~15–20); a `limit`/`offset` protocol extension would allow
  browsing larger sets.
