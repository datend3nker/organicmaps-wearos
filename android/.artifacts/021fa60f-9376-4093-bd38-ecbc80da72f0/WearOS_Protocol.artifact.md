# Organic Maps Wear OS Communication Protocol

This document defines the communication protocol between the Organic Maps Android app (Phone) and the Wear OS app (Watch). The protocol is designed to be transport-agnostic, supporting both **Google Play Services (GMS) Wearable API** and **Standard Bluetooth (RFCOMM/TCP)**.

## 1. Architectural Layers

The protocol operates on three distinct layers to ensure maximum compatibility:

1.  **Transport Layer**:
    *   **GMS**: Uses `MessageClient` (low latency, ~100KB limit) and `ChannelClient` (streaming/files).
    *   **Bluetooth**: Custom RFCOMM (Socket) or TCP (Emulator) stream.
2.  **Sync Layer (`ISyncLayer`)**: An abstraction that hides transport details.
3.  **Application Layer (`WearMessageRouter`)**: Handles incoming command routing and payload parsing.

## 2. Message Framing

### 2.1 GMS Framing (Path-Based)
GMS uses named paths to route messages.
- **Header**: 1-byte Protocol Version.
- **Payload**: Binary data (compressed with GZIP if > 512 bytes for some paths).

### 2.2 Bluetooth Framing (Type-Based)
Since Bluetooth is a raw stream, it uses a fixed-length header:
| Size (bytes) | Field | Description |
| :--- | :--- | :--- |
| 1 | `PROTOCOL_VERSION` | Currently 1 |
| 1 | `MESSAGE_TYPE` | Numeric ID (e.g., 1 for Nav, 4 for Prefs) |
| 4 | `PAYLOAD_LENGTH` | Length of the following data |
| N | `PAYLOAD` | The actual data |

## 3. Key Functional Modules

### 3.1 Settings Synchronization
Settings are mapped between **Canonical Keys** (protocol names) and **Local Keys** (Android SharedPreferences keys).

- **Conflict Resolution**: Last Write Wins (LWW) based on 64-bit Unix timestamps.
- **State Machine**:
  1. A change on either device marks a key as "Dirty".
  2. The transport layer periodically or on-event pushes dirty updates.
  3. The receiver applies updates ONLY if the received timestamp is greater than the local timestamp.

### 3.2 Companion Mode (Map Streaming)
To support a "Companion Mode" where the watch uses phone data:
- **Virtual MWM Request**: The watch requests specific byte ranges of a map file (`PATH_VIRTUAL_MWM_REQUEST`).
- **Metadata Optimization**: The phone sends the MWM Header and Footer together in one packet to allow the watch to "mount" the container without fetching everything.
- **Chunking**: Data is sent in ~85KB chunks over GMS to stay within transport limits.

### 3.3 Map Transfer (Streaming)
For transferring full map files (e.g., copying a region to the watch):
- **GMS**: Uses `ChannelClient.sendFile()` for reliable, high-speed transfer.
- **Bluetooth**: Implements a manual chunking loop with `MSG_TYPE_MAP_CHUNK`.

### 3.4 Bookmark Synchronization
Bookmarks are synchronized between devices using KML/KMZ files, but the synchronization logic MUST be **Non-Destructive**.

- **Handshake**: Devices exchange a list of categories including their local `LAST_MODIFIED` timestamp and a unique `CATEGORY_UUID`.
- **Merge Logic**:
  - If a category is missing on one side, it is streamed and imported.
  - If a category exists on both sides but with different timestamps, the side with the **Newer Timestamp Wins**.
- **Granular Updates**: For future implementation, individual bookmarks within a category should be merged based on their own UUIDs and timestamps to prevent losing data when both sides are edited offline.

---

## 4. Liveness & App-to-App Handshake

It is critical to distinguish between a **Physical Device Connection** (Bluetooth pairing or GMS reachability) and an **Active Application Connection**.

### 4.1 Ping/Pong Heartbeat
To ensure the other application is alive and responsive:
- **Ping**: Sent by either device every 15 seconds if no other traffic has occurred.
- **Pong**: Must be sent immediately in response to a Ping.
- **Activity Tracking**: Receiving *any* valid protocol message resets the "Last Activity" timer.

### 4.2 Connection State Lifecycle
- **Connected**: Marked when a valid protocol message (or Pong) is received.
- **Disconnected (Timeout)**: If no message is received for **45 seconds**, the app-to-app connection is considered lost, even if the physical transport is still reported as "up".
- **Handshake on Reconnect**: When a connection is re-established (physical link returns), both sides SHOULD immediately trigger a `PATH_PREFERENCES_REQUEST` or push "Dirty" settings to ensure state consistency.

---

## 5. Known Weaknesses & Robustness Guidelines

> [!WARNING]
> **Current implementation is considered error-prone** in several areas. Use the following guidelines to improve robustness.

### 4.1 Clock Skew & Conflict Resolution
The current LWW (Last Write Wins) relies on `System.currentTimeMillis()`.
- **Issue**: If one device has a manual clock offset, sync will fail (one side will always win).
- **Recommendation**: Transition to **Logical Clocks (Vector Clocks)** or a simple monotonically increasing **Version Counter** per setting key.

### 4.2 Bookmark Merging (Critical)
- **Current Issue**: The current implementation is "Destructive Sync". It deletes the existing category and re-loads the new file. Local changes made on the watch while offline are lost.
- **Robustness Plan**:
  - Implement **Granular Merging**: Instead of category-level overwrites, sync individual bookmarks by UUID.
  - Use a **Tombstone mechanism** for deletions to ensure they propagate correctly instead of reappearing on the next sync.

### 4.3 Bluetooth Reliability
- **Issue**: Bluetooth (RFCOMM) can be flaky. The current implementation lacks app-level acknowledgments (ACKs) for critical commands.
- **Recommendation**: Implement a sequence number and retry mechanism for non-streaming messages over the Bluetooth layer.

### 4.4 Search Result Consistency
- **Issue**: Search results are currently fixed at a maximum of 15-20 items.
- **Recommendation**: Implement **Pagination** in the protocol (`limit` and `offset` fields) to allow browsing large result sets.

## 5. Performance & Resource Management

For a wearable protocol, power and data efficiency are as critical as reliability.

### 5.1 Heartbeats & Backoff
- **Active State**: 15s ping interval to maintain connection state.
- **Disconnected State**: Exponential backoff up to 5 minutes to prevent battery drain when the phone is out of range.
- **Traffic Awareness**: Sending any application-level message resets the heartbeat timer.

### 5.2 Message Deduplication
To prevent "message storms" or redundant processing:
- **Hash-based Checksumming**: `WearMessageRouter` keeps a rolling hash of the last received payload per path.
- **Time Windowing**: Messages with identical hashes received within 200ms (Search) or 500ms (Default) are ignored.

### 5.3 Data Compression
- All payloads larger than 512 bytes (Map tiles, Search results, MWM chunks) SHOULD be GZIP compressed.
- The receiver detects compression via a 1-byte flag in the payload header.

---

## 6. Recommendations for Future Protocol Evolution

When extending this protocol, consider these "Best Practices" for Wearable sync:

1.  **Capability Discovery**: Never assume the watch app is installed. Use the GMS Capability Client or a Bluetooth "Discovery Handshake" before initiating heavy sync.
2.  **Transactionality**: For bookmarks and settings, use transactions. If a multi-packet transfer (like a KML file) fails, the partial data should be discarded rather than merged.
3.  **Foreground Service Requirement**: For reliable Bluetooth streaming, the phone must keep a Foreground Service active to prevent the OS from killing the background process.
4.  **Batching**: Instead of sending 10 setting updates as 10 messages, batch them into a single `MSG_TYPE_PREFERENCES_UPDATES` packet.
