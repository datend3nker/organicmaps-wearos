# Organic Maps — Wear OS Architecture

How the Wear OS companion is put together: the modules, the major components on each device, the threading
model, and the main data flows. For the wire format see [PROTOCOL.md](PROTOCOL.md).

---

## 1. Modules & build flavors

The Wear companion spans three Gradle modules in `android/`:

| Module | Path | Role |
| :--- | :--- | :--- |
| **Watch app** | `android/app/omaps` | The Wear OS app (Jetpack Compose UI + native map engine) |
| **Phone app** | `android/app` | The phone app; also hosts the watch-companion service |
| **Shared SDK** | `android/sdk` | Shared Java/Kotlin + JNI bridge to the C++ core |
| (core) | `libs/` | The C++ map/routing engine (shared with the phone) |

**Flavors.** Both apps build the `fdroid` flavor for the watch pipeline. Source sets:

- Watch (`app/omaps`): `fdroid` + `google` use `src/gms/java`; `oss` uses `src/oss/java`; everything else
  is in `src/main`.
- Phone (`app`): `fdroid`/`google`/`web`/`huawei` use `src/gms/java`; `oss` uses `src/oss/java`.

`src/gms` provides the Google Play Services transport; `src/oss` provides Bluetooth-only stubs. Code shared
by both transports lives in `src/main`.

**Typical build:** `./gradlew :app:assembleFdroidDebug :app:omaps:assembleFdroidDebug -Px86_64`
(the `-Px86_64` restricts the native build to a single ABI for speed).

---

## 2. The two devices at a glance

```
        ┌──────────────────────────── PHONE (android/app) ────────────────────────────┐
        │                                                                              │
        │  MwmActivity ── RoutingController ──(RouteEventListener)── HeadlessRoute      │
        │       │                                                       Interactor      │
        │  WearSyncService ── ISyncLayer ──┬── GmsSyncLayer                             │
        │   (bookmark/settings/nav sync)   └── BluetoothSyncLayer (TCP server :5610)    │
        │       │                                                                       │
        │  WearMessageRouter.java  ◄── inbound frames                                   │
        └───────────────┬───────────────────────────────────────────────┬─────────────┘
                        │  GMS Wearable API (MessageClient/DataClient)    │  Bluetooth RFCOMM / TCP
        ┌───────────────┴───────────────────────────────────────────────┴─────────────┐
        │                          WATCH (android/app/omaps)                            │
        │                                                                               │
        │  inbound ►  WearDataListenerService (GMS) / BluetoothWearDataListenerService  │
        │                       │                                                       │
        │             WearMessageRouter.kt ──► WearMessageDispatcher ──► message/*Handler│
        │                       │                                                       │
        │  outbound ◄ WearCommandService ──► IWearSyncBackend ──┬── GmsWearSyncBackend   │
        │                       │                               └── BluetoothWearSyncBackend
        │  NavigationStateHolder (single UI StateFlow)                                  │
        │  VirtualMwmManager (sparse map cache) · Compose UI (Omaps/MapPanel/…)         │
        └───────────────────────────────────────────────────────────────────────────────┘
```

The map is rendered by the **same C++ engine** as the phone, via the SDK's JNI bridge — the watch is not a
re-implementation, it drives the real renderer.

---

## 3. Watch app components (`app/omaps`)

### Transport
- **`IWearSyncBackend`** — outbound transport interface; implementations `GmsWearSyncBackend` (GMS,
  `src/gms`) and `BluetoothWearSyncBackend` (RFCOMM/TCP, `src/main`).
- **`WearCommandService`** — façade the UI calls for every outbound action; resolves the selected backend
  (`getBackend`) and forwards. Also owns the search watchdog.
- **Inbound listeners** — `WearDataListenerService` (GMS, cold-startable) and
  `BluetoothWearDataListenerService` (owns the single BT socket + read loop). Both funnel frames into the
  same router.

### Routing
- **`WearMessageRouter.kt`** — single inbound entry point. Special-cases control-plane paths
  (ping/pong/handshake/backend-switch/bookmarks-metadata/tombstone), applies the selected-backend gate and
  dedup, then delegates the rest to the dispatcher.
- **`WearMessageDispatcher`** — maps message **type → handler**; handlers live in `wear/message/`
  (`NavStatusHandler`, `SearchResultsHandler`, `BookmarkFileHandler`, `VirtualMwmHandlers`, …).

### State & sync
- **`NavigationStateHolder`** — the single source of UI truth: a `StateFlow<NavigationState>` the Compose
  UI collects (connection, navigation, search, bookmarks, modes, backend). All handlers mutate it.
- **`WatchBookmarkSyncManager`** — bookmark metadata handshake, file push/merge, deletion detection,
  tombstone apply/flush. Registered against `BookmarkManager`'s sharing/categories/loading listeners in
  `WearApplication`.
- **`SettingsSyncManager`** (+ shared `BaseSettingsSyncManager`) — timestamp/version LWW settings sync.
- **`VirtualMwmManager`** — owns the sparse-map streaming cache (block tracking, LRU eviction, budget);
  Kotlin side of the JNI streaming bridge.

### UI (`wear/presentation/`)
Jetpack Compose for Wear: `Omaps` (root + tabs/indicators), `MapPanel` (the map surface, controls, and the
QuickMenu — long-press / STEM-1 — which hosts zoom, track recording, and **Add Bookmark**),
`search/SearchScreen`, `settings/SettingsScreen`, `downloads/MapManagerScreen`.

---

## 4. Phone app components (`app`)

- **`WearSyncService`** (`src/gms` / `src/oss`) — the companion hub: bookmark/settings/search/nav sync,
  bookmark change detection + tombstones, file transfer. Registers `BookmarkManager` listeners.
- **`ISyncLayer`** → `GmsSyncLayer` (`src/gms`) / `BluetoothSyncLayer` (`src/main`, TCP server on `5610`
  when BT is selected) — the phone-side transport abstraction.
- **`WearMessageRouter.java`** — inbound routing on the phone (mirror of the watch router).
- **`HeadlessRouteInteractor`** — builds a route on the watch's behalf. Registers as a
  `RoutingController.RouteEventListener` **observer** (never the UI `Container`) so watch-initiated
  navigation works without disturbing the phone's `MwmActivity`.
- **`WearCompanionNotificationManager`** — foreground/progress notifications (route calc, map serving).

---

## 5. Shared SDK (`sdk`)

- **`WearProtocol`** — the path/type registry and constants (see [PROTOCOL.md](PROTOCOL.md)).
- **`WearProtocolDataConverter`** — encode/decode helpers for structured payloads (handshake, preference
  updates, search history).
- **`BaseSettingsSyncManager`** / **`SyncSettingsRegistry`** — settings LWW engine + canonical↔local key
  mapping, shared by both devices.
- **`BookmarkTombstoneStore`** — shared deletion-tombstone store (identity, wire format, apply) used by the
  Kotlin watch and Java phone code alike.
- **`SyncConnection`** (+ `BluetoothSyncConnection` / `TcpSyncConnection`) — stream abstraction for the BT
  transport.
- **`RoutingController`** — the routing state machine; extended with a non-exclusive `RouteEventListener`
  observer list so headless integrations don't have to seize the single UI `Container`.
- **JNI / native** — `sdk/src/main/cpp/.../wear/VirtualMwmManager.cpp` bridges to the C++ streaming core
  (`libs/platform/virtual_mwm_core.*`, `virtual_model_reader.cpp`).

---

## 6. Threading model

- **UI** — Compose on the main thread; state via `NavigationStateHolder` `StateFlow`.
- **Transport I/O** — coroutines on `Dispatchers.IO` (watch) / handler threads & the GMS data layer
  (phone). The Bluetooth read loop runs in the listener service's `SupervisorJob` scope.
- **BookmarkManager mutations** — must run on the **main/UI thread**; tombstone applies and merges are
  posted there and wrapped in the `isApplyingRemoteUpdate` guard.
- **Native streaming** — `VirtualModelReader::Read` blocks the calling render thread on `WaitForData`
  until bytes arrive (or throws cleanly on timeout); GUI-thread tasks catch `RootException` so a
  slow/dropped stream degrades to "retry on next frame" instead of aborting.

---

## 7. Operating modes

| Mode | Data source | GPS | Notes |
| :--- | :--- | :--- | :--- |
| **Companion (streaming)** | Phone (viewport stream) | Phone | Default; phone does heavy lifting |
| **Watch Local** | Watch storage | Phone | Overrides streaming when the watch has the region |
| **Standalone** | Watch storage | Watch | Forces Local + Map-enabled, uses the watch's own GPS |

Connection indicator (top-right on the watch): green cloud = GMS, green Bluetooth = BT, red = disconnected,
green SD = Watch Local, airplane = Standalone.

---

## 8. Where to start reading

| To understand… | Start at |
| :--- | :--- |
| The wire protocol | [`WearProtocol.java`](../../../sdk/src/main/java/app/organicmaps/sdk/sync/WearProtocol.java) + [PROTOCOL.md](PROTOCOL.md) |
| Watch inbound flow | `WearMessageRouter.kt` → `WearMessageDispatcher` → `wear/message/` |
| Watch outbound flow | `WearCommandService` → `IWearSyncBackend` |
| Watch UI state | `NavigationStateHolder` |
| Bookmark sync | `WatchBookmarkSyncManager` (watch) + `WearSyncService` (phone) + `BookmarkTombstoneStore` |
| Map streaming | `VirtualMwmManager.kt` + `VirtualMwmManager.cpp` + `virtual_mwm_core.*` |
| Navigation | `HeadlessRouteInteractor` + `RoutingController` |
