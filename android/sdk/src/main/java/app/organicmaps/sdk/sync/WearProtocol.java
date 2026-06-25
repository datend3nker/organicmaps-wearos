package app.organicmaps.sdk.sync;

import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;

public class WearProtocol {
    public static final byte PROTOCOL_VERSION = 1;

    // GMS Paths
    public static final String PATH_NAVIGATION_STATUS = "/navigation/status";
    public static final String PATH_NAVIGATION_STOP = "/navigation/stop";
    public static final String PATH_NAVIGATION_START = "/navigation/start";
    public static final String PATH_SEARCH_RESULTS = "/search/results";
    public static final String PATH_SEARCH_QUERY = "/search/query";
    public static final String PATH_SEARCH_SELECT = "/search/select";
    public static final String PATH_SEARCH_HISTORY = "/search/history";
    public static final String PATH_SEARCH_HISTORY_REQUEST = "/search/history/request";
    public static final String PATH_SEARCH_HISTORY_SYNC = "/search/history/sync";
    public static final String PATH_PREFERENCES_PHONE = "/preferences/phone";
    public static final String PATH_PREFERENCES_WATCH = "/preferences/watch";
    public static final String PATH_PREFERENCES_REQUEST = "/preferences/request";
    public static final String PATH_PREFERENCES_UPDATES = "/preferences/updates";
    public static final String PATH_PREFERENCES_TRIGGER = "/preferences/trigger";
    public static final String PATH_PING = "/ping";
    public static final String PATH_PONG = "/pong";
    public static final String PATH_LAUNCH = "/launch";
    public static final String PATH_TRACK_RECORDING = "/track/recording";
    public static final String PATH_TRACK_RECORDING_TOGGLE = "/track/recording/toggle";
    // Explicit recording controls (path-routed, no dedicated message type): save the current
    // recording (optional UTF-8 name payload) or discard it.
    public static final String PATH_TRACK_RECORDING_SAVE = "/track/recording/save";
    public static final String PATH_TRACK_RECORDING_DISCARD = "/track/recording/discard";
    // Saved-track sync (distinct from the live recording controls above). Two tiers:
    //   - manifest/tombstone: lightweight per-track metadata + deletions (LWW), reconciled both ways.
    //   - blob request/blob: heavy KMZ geometry, fetched on demand only for tracks a device lacks.
    // Identity is a UUID minted at save time and embedded in the track description (survives app
    // restart AND the KMZ round-trip), the track analog of the bookmark content-addressed id.
    public static final String PATH_TRACK_MANIFEST = "/track/manifest";
    public static final String PATH_TRACK_TOMBSTONE = "/track/tombstone";
    public static final String PATH_TRACK_BLOB_REQUEST = "/track/blob/request";
    public static final String PATH_TRACK_BLOB = "/track/blob";
    public static final String PATH_BOOKMARKS = "/bookmarks";
    public static final String PATH_BOOKMARKS_REQUEST = "/bookmarks/request";
    public static final String PATH_BOOKMARKS_METADATA = "/bookmarks/metadata";
    public static final String PATH_BOOKMARK_CONFLICT = "/bookmark/conflict";
    public static final String PATH_BOOKMARK_FILE = "/bookmark/file";
    public static final String PATH_BOOKMARK_RENAME = "/bookmark/rename";
    public static final String PATH_BOOKMARK_DELETE = "/bookmark/delete";
    public static final String PATH_BOOKMARK_TOMBSTONE = "/bookmark/tombstone";
    public static final String PATH_BOOKMARK_SHOW = "/bookmark/show";
    public static final String PATH_BOOKMARK_UPDATE = "/bookmark/update";
    public static final String PATH_BOOKMARK_VISIBLE_TOGGLE = "/bookmark/visible/toggle";
    public static final String PATH_BOOKMARK_SYNC_REQUEST = "/bookmark/sync/request";
    public static final String PATH_BOOKMARK_CATEGORY_CREATE = "/bookmark/category/create";
    // Per-bookmark LWW upsert batch. Replaces the old category-grained KMZ export/import sync, whose
    // name-keyed category identity collided on import (My Places -> My Places1 -> ...) and cascaded
    // exponentially. Identity is now content-addressed (cat|name|lat|lon); see BookmarkSyncCore.
    public static final String PATH_BOOKMARK_UPSERT = "/bookmark/upsert";
    public static final String PATH_MAP_TILE_RESPONSE = "/map/tile/response";
    public static final String PATH_MAP_DOWNLOAD_REQUEST = "/map/download/request";
    public static final String PATH_MAP_DOWNLOAD_PROGRESS = "/map/download/progress";
    public static final String PATH_MAP_DOWNLOAD_CANCEL = "/map/download/cancel";
    public static final String PATH_MAP_STREAM_DATA = "/map/stream/data/";
    public static final String PATH_MAP_DOWNLOAD_NOT_FOUND = "/map/download/not_found";
    public static final String PATH_MAP_PHONE_DOWNLOADED = "/map/phone/downloaded";
    public static final String PATH_MAP_PHONE_DOWNLOADED_REQUEST = "/map/phone/downloaded/request";
    public static final String PATH_VIRTUAL_MWM_REQUEST = "/virtual_mwm/request";
    public static final String PATH_VIRTUAL_MWM_DATA = "/virtual_mwm/data";
    public static final String PATH_VIRTUAL_MWM_MOUNT = "/virtual_mwm/mount";
    public static final String PATH_VIRTUAL_MWM_METADATA_REQUEST = "/virtual_mwm/metadata_request";
    public static final String PATH_BACKEND_SWITCH = "/backend/switch";
    public static final String PATH_POI_SHOW = "/poi/show";
    public static final String PATH_ROUTE_BUILD_PROGRESS = "/navigation/route_build_progress";
    public static final String PATH_HANDSHAKE = "/handshake";

    // Canonical Setting Keys
    public static final String SETTING_MAP_ENABLED = "mapEnabled";
    public static final String SETTING_WATCH_LOCAL_MODE = "watchLocalMode";
    public static final String SETTING_STANDALONE_MODE = "standaloneMode";
    public static final String SETTING_AUTO_DOWNLOAD = "autoDownload";
    public static final String SETTING_MAP_DOWNLOAD_MODE = "mapDownloadMode";
    public static final String SETTING_BACKEND = "backend";
    public static final String SETTING_POI_MASK = "poiMask";
    public static final String SETTING_3D_ENABLED = "is3dEnabled";
    public static final String SETTING_3D_BUILDINGS_ENABLED = "is3dBuildingsEnabled";
    public static final String SETTING_AUTO_ZOOM_ENABLED = "isAutoZoomEnabled";
    public static final String SETTING_MEASUREMENT_UNITS = "measurementUnits";
    public static final String SETTING_MAP_STYLE = "mapStyle";
    public static final String SETTING_AVOID_TOLLS = "avoidTolls";
    public static final String SETTING_AVOID_MOTORWAYS = "avoidMotorways";
    public static final String SETTING_AVOID_FERRIES = "avoidFerries";
    public static final String SETTING_AVOID_UNPAVED = "avoidUnpaved";
    public static final String SETTING_TRANSIT_ENABLED = "transitEnabled";
    public static final String SETTING_BIKING_ENABLED = "bikingEnabled";
    public static final String SETTING_HIKING_ENABLED = "hikingEnabled";
    public static final String SETTING_ISOLINES_ENABLED = "isolinesEnabled";
    public static final String SETTING_LOCATION_SOURCE = "locationSource";
    public static final String SETTING_SYNC_NOTIFICATIONS_ENABLED = "syncNotificationsEnabled";

    // Bluetooth Message Types
    public static final byte TYPE_NAV_STATUS = 1;
    public static final byte TYPE_SEARCH_RESULTS = 2;
    public static final byte TYPE_SEARCH_HISTORY = 3;
    public static final byte TYPE_PREFERENCES = 4;
    public static final byte TYPE_MAP_DOWNLOAD_REQUEST = 5;
    public static final byte TYPE_MAP_TILE_RESPONSE = 6;
    public static final byte TYPE_MAP_DOWNLOAD_PROGRESS = 7;
    public static final byte TYPE_TRACK_RECORDING = 8;
    public static final byte TYPE_BOOKMARKS = 9;
    public static final byte TYPE_COMMAND = 10;
    public static final byte TYPE_MAP_CHUNK = 11;
    public static final byte TYPE_BOOKMARK_FILE = 12;
    public static final byte TYPE_VIRTUAL_MWM_REQUEST = 13;
    public static final byte TYPE_VIRTUAL_MWM_DATA = 14;
    public static final byte TYPE_VIRTUAL_MWM_MOUNT = 15;
    public static final byte TYPE_ROUTE_BUILD_PROGRESS = 16;
    public static final byte TYPE_BOOKMARK_RENAME = 17;
    public static final byte TYPE_BOOKMARK_DELETE = 18;
    public static final byte TYPE_PREFERENCES_UPDATES = 19;
    public static final byte TYPE_MAP_PHONE_DOWNLOADED = 20;
    public static final byte TYPE_HANDSHAKE = 21;
    public static final byte TYPE_BOOKMARKS_METADATA = 22;
    public static final byte TYPE_BOOKMARK_TOMBSTONE = 23;
    public static final byte TYPE_BOOKMARK_CATEGORY_CREATE = 24;
    public static final byte TYPE_BOOKMARK_UPSERT = 25;
    public static final byte TYPE_TRACK_MANIFEST = 26;
    public static final byte TYPE_TRACK_TOMBSTONE = 27;
    public static final byte TYPE_TRACK_BLOB_REQUEST = 28;
    public static final byte TYPE_TRACK_BLOB = 29;

    // Highest valid message type id. The Bluetooth framing layer uses this as a desync sanity
    // bound when parsing headers; keep it >= the largest TYPE_* above (with a little headroom).
    // MUST be updated when new TYPE_* values are added, or BT will reject them as "invalid header".
    public static final byte MAX_MESSAGE_TYPE = 30;

    // Priorities
    public static final int PRIORITY_HIGH = 0;
    public static final int PRIORITY_MEDIUM = 1;
    public static final int PRIORITY_LOW = 2;

    private static final Map<String, Byte> PATH_TO_TYPE = new HashMap<>();
    private static final Map<Byte, String> TYPE_TO_PATH = new HashMap<>();

    static {
        register(PATH_NAVIGATION_STATUS, TYPE_NAV_STATUS);
        register(PATH_SEARCH_RESULTS, TYPE_SEARCH_RESULTS);
        register(PATH_SEARCH_HISTORY, TYPE_SEARCH_HISTORY);
        register(PATH_SEARCH_HISTORY_SYNC, TYPE_SEARCH_HISTORY);
        register(PATH_PREFERENCES_PHONE, TYPE_PREFERENCES);
        register(PATH_PREFERENCES_WATCH, TYPE_PREFERENCES);
        register(PATH_PREFERENCES_UPDATES, TYPE_PREFERENCES_UPDATES);
        register(PATH_MAP_DOWNLOAD_REQUEST, TYPE_MAP_DOWNLOAD_REQUEST);
        register(PATH_MAP_TILE_RESPONSE, TYPE_MAP_TILE_RESPONSE);
        register(PATH_MAP_DOWNLOAD_PROGRESS, TYPE_MAP_DOWNLOAD_PROGRESS);
        register(PATH_TRACK_RECORDING, TYPE_TRACK_RECORDING);
        register(PATH_BOOKMARKS, TYPE_BOOKMARKS);
        register(PATH_BOOKMARKS_METADATA, TYPE_BOOKMARKS_METADATA);
        register(PATH_BOOKMARK_CONFLICT, TYPE_COMMAND);
        register(PATH_BOOKMARK_FILE, TYPE_BOOKMARK_FILE);
        register(PATH_BOOKMARK_RENAME, TYPE_BOOKMARK_RENAME);
        register(PATH_BOOKMARK_DELETE, TYPE_BOOKMARK_DELETE);
        register(PATH_BOOKMARK_TOMBSTONE, TYPE_BOOKMARK_TOMBSTONE);
        register(PATH_BOOKMARK_CATEGORY_CREATE, TYPE_BOOKMARK_CATEGORY_CREATE);
        register(PATH_BOOKMARK_UPSERT, TYPE_BOOKMARK_UPSERT);
        register(PATH_TRACK_MANIFEST, TYPE_TRACK_MANIFEST);
        register(PATH_TRACK_TOMBSTONE, TYPE_TRACK_TOMBSTONE);
        register(PATH_TRACK_BLOB_REQUEST, TYPE_TRACK_BLOB_REQUEST);
        register(PATH_TRACK_BLOB, TYPE_TRACK_BLOB);
        register(PATH_MAP_PHONE_DOWNLOADED, TYPE_MAP_PHONE_DOWNLOADED);
        register(PATH_VIRTUAL_MWM_REQUEST, TYPE_VIRTUAL_MWM_REQUEST);
        register(PATH_VIRTUAL_MWM_DATA, TYPE_VIRTUAL_MWM_DATA);
        register(PATH_VIRTUAL_MWM_MOUNT, TYPE_VIRTUAL_MWM_MOUNT);
        register(PATH_ROUTE_BUILD_PROGRESS, TYPE_ROUTE_BUILD_PROGRESS);
        register(PATH_HANDSHAKE, TYPE_HANDSHAKE);
    }

    private static void register(String path, byte type) {
        PATH_TO_TYPE.put(path, type);
        TYPE_TO_PATH.put(type, path);
    }

    public static byte getMessageType(@NonNull String path) {
        Byte type = PATH_TO_TYPE.get(path);
        return type != null ? type : TYPE_COMMAND;
    }

    public static String getPath(byte type) {
        return TYPE_TO_PATH.get(type);
    }

    public static int getPriority(byte type) {
        return switch (type) {
            case TYPE_NAV_STATUS, TYPE_COMMAND, TYPE_HANDSHAKE -> PRIORITY_HIGH;
            case TYPE_SEARCH_RESULTS, TYPE_SEARCH_HISTORY, TYPE_PREFERENCES, TYPE_PREFERENCES_UPDATES,
                 TYPE_TRACK_RECORDING, TYPE_BOOKMARKS, TYPE_BOOKMARKS_METADATA, TYPE_BOOKMARK_RENAME,
                 TYPE_BOOKMARK_DELETE, TYPE_BOOKMARK_UPSERT, TYPE_MAP_DOWNLOAD_PROGRESS,
                 TYPE_ROUTE_BUILD_PROGRESS, TYPE_MAP_PHONE_DOWNLOADED,
                 TYPE_TRACK_MANIFEST, TYPE_TRACK_TOMBSTONE, TYPE_TRACK_BLOB_REQUEST -> PRIORITY_MEDIUM;
            case TYPE_MAP_CHUNK, TYPE_BOOKMARK_FILE, TYPE_VIRTUAL_MWM_REQUEST, TYPE_VIRTUAL_MWM_DATA,
                 TYPE_VIRTUAL_MWM_MOUNT, TYPE_MAP_TILE_RESPONSE, TYPE_MAP_DOWNLOAD_REQUEST,
                 TYPE_TRACK_BLOB -> PRIORITY_LOW;
            default -> PRIORITY_MEDIUM;
        };
    }
}
