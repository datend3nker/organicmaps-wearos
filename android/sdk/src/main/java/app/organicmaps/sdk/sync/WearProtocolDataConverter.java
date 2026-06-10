package app.organicmaps.sdk.sync;

import android.content.Context;
import android.location.Location;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.search.SearchResult;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class WearProtocolDataConverter {

    public static byte[] encodeNavigationStatus(@NonNull Context context, boolean isNavigating, @Nullable RoutingInfo info, @Nullable Location location, float[] routeLats, float[] routeLons) {
        byte[] streetBytes = (info != null && info.nextStreet != null) ? info.nextStreet.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] distBytes = (info != null && info.distToTurn != null) ? info.distToTurn.toString(context).getBytes(StandardCharsets.UTF_8) : new byte[0];
        
        int routePoints = (routeLats != null) ? routeLats.length : 0;
        ByteBuffer buffer = ByteBuffer.allocate(64 + streetBytes.length + distBytes.length + (routePoints * 4 * 2));
        
        buffer.put((byte) (isNavigating ? 1 : 0));
        buffer.put((byte) (info != null ? info.carDirection.ordinal() : 0));
        buffer.put((byte) (info != null ? info.pedestrianDirection.ordinal() : 0));
        buffer.put((byte) (info != null ? info.exitNum : 0));
        buffer.putFloat((float) (info != null ? info.completionPercent : 0.0));
        buffer.putDouble(location != null ? location.getLatitude() : 0.0);
        buffer.putDouble(location != null ? location.getLongitude() : 0.0);
        buffer.putDouble(info != null ? info.turnLat : 0.0);
        buffer.putDouble(info != null ? info.turnLon : 0.0);
        
        buffer.putFloat(location != null && location.hasBearing() ? location.getBearing() : -1.0f);
        buffer.putFloat(location != null ? (float) location.getSpeed() : -1.0f);
        buffer.putFloat((float) (info != null ? info.speedLimitMps : -1.0));

        buffer.putInt(routePoints);
        buffer.putInt(streetBytes.length);
        buffer.putInt(distBytes.length);
        buffer.put(streetBytes);
        buffer.put(distBytes);
        
        if (routePoints > 0) {
            for (float lat : routeLats) buffer.putFloat(lat);
            for (float lon : routeLons) buffer.putFloat(lon);
        }
        
        return buffer.array();
    }

    public static byte[] encodeSearchResults(@NonNull Context context, @NonNull SearchResult[] results, boolean isSearching, int maxCount) {
        int count = Math.min(results.length, maxCount);
        int totalSize = 1; // isSearching
        List<byte[]> nameBytesList = new ArrayList<>();
        List<byte[]> descBytesList = new ArrayList<>();
        List<byte[]> distBytesList = new ArrayList<>();
        List<byte[]> featureBytesList = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            SearchResult res = results[i];
            byte[] nb = (res.getTitle(context) != null ? res.getTitle(context) : "").getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            
            String desc = "";
            String dist = "";
            String feature = "";
            if (res.description != null) {
                if (res.description.localizedFeatureType != null) {
                    desc = res.description.localizedFeatureType;
                    feature = res.description.localizedFeatureType;
                } else if (res.description.region != null) {
                    desc = res.description.region;
                }
                
                if (res.description.distance != null && res.description.distance.isValid()) {
                    dist = res.description.distance.toString(context);
                }
            }
            byte[] db = desc.getBytes(StandardCharsets.UTF_8);
            descBytesList.add(db);
            byte[] distB = dist.getBytes(StandardCharsets.UTF_8);
            distBytesList.add(distB);
            byte[] fb = feature.getBytes(StandardCharsets.UTF_8);
            featureBytesList.add(fb);
            
            totalSize += 4 + nb.length + 4 + db.length + 8 + 8 + 4 + distB.length + 4 + fb.length;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.put((byte) (isSearching ? 1 : 0));
        for (int i = 0; i < count; i++) {
            byte[] nb = nameBytesList.get(i);
            buffer.putInt(nb.length);
            buffer.put(nb);
            byte[] db = descBytesList.get(i);
            buffer.putInt(db.length);
            buffer.put(db);
            buffer.putDouble(results[i].lat);
            buffer.putDouble(results[i].lon);
            byte[] distB = distBytesList.get(i);
            buffer.putInt(distB.length);
            buffer.put(distB);
            byte[] fb = featureBytesList.get(i);
            buffer.putInt(fb.length);
            buffer.put(fb);
        }
        return buffer.array();
    }

    public static byte[] encodeSearchHistory(@NonNull List<String> history, int maxCount) {
        int count = Math.min(history.size(), maxCount);
        int totalSize = 4;
        List<byte[]> historyBytes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] b = history.get(i).getBytes(StandardCharsets.UTF_8);
            historyBytes.add(b);
            totalSize += 4 + b.length;
        }
        
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (byte[] b : historyBytes) {
            buffer.putInt(b.length);
            buffer.put(b);
        }
        return buffer.array();
    }

    public static byte[] encodeBookmarkCategories(@NonNull List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories, @NonNull android.content.SharedPreferences syncPrefs, int maxCount) {
        int count = Math.min(categories.size(), maxCount);
        int totalSize = 4;
        List<byte[]> nameBytesList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            byte[] nb = cat.getName().getBytes(StandardCharsets.UTF_8);
            nameBytesList.add(nb);
            totalSize += 8 + 4 + nb.length + 1 + 4 + 4 + 8;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (int i = 0; i < count; i++) {
            app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat = categories.get(i);
            byte[] nb = nameBytesList.get(i);
            buffer.putLong(cat.getId());
            buffer.putInt(nb.length);
            buffer.put(nb);
            buffer.put((byte) (cat.isVisible() ? 1 : 0));
            buffer.putInt(cat.getBookmarksCount());
            buffer.putInt(cat.getTracksCount());
            buffer.putLong(syncPrefs.getLong(cat.getName(), 0));
        }
        return buffer.array();
    }

    public static byte[] serializeValue(Object v) {
        if (v instanceof Boolean) return new byte[]{(byte)((Boolean)v ? 1 : 0)};
        if (v instanceof String) return ((String)v).getBytes(StandardCharsets.UTF_8);
        if (v instanceof Integer) return ByteBuffer.allocate(4).putInt((Integer)v).array();
        if (v instanceof Long) return ByteBuffer.allocate(8).putLong((Long)v).array();
        return new byte[0];
    }

    public static byte getValueType(Object v) {
        if (v instanceof Boolean) return 1;
        if (v instanceof String) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        return 0;
    }

    public static Object deserializeValue(byte type, byte[] b) {
        try {
            if (type == 1) return b[0] == 1;
            if (type == 2) return new String(b, StandardCharsets.UTF_8);
            if (type == 3) return ByteBuffer.wrap(b).getInt();
            if (type == 4) return ByteBuffer.wrap(b).getLong();
        } catch (Exception e) {
            // Log.e("WearProtocol", "Failed to deserialize value of type " + type, e);
        }
        return null;
    }

    public static byte[] encodePreferenceUpdates(@NonNull List<String> keys, @NonNull List<Object> values, @NonNull List<Long> timestamps) {
        int count = keys.size();
        int totalSize = 4;
        List<byte[]> keyBytesList = new ArrayList<>();
        List<byte[]> valBytesList = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            byte[] kb = keys.get(i).getBytes(StandardCharsets.UTF_8);
            keyBytesList.add(kb);
            byte[] vb = serializeValue(values.get(i));
            valBytesList.add(vb);
            totalSize += 4 + kb.length + 1 + 4 + vb.length + 8;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        buffer.putInt(count);
        for (int i = 0; i < count; i++) {
            byte[] kb = keyBytesList.get(i);
            byte[] vb = valBytesList.get(i);
            buffer.putInt(kb.length);
            buffer.put(kb);
            buffer.put(getValueType(values.get(i)));
            buffer.putInt(vb.length);
            buffer.put(vb);
            buffer.putLong(timestamps.get(i));
        }
        return buffer.array();
    }

    public static byte[] encodeHandshake(int versionCode, byte flags) {
        ByteBuffer buffer = ByteBuffer.allocate(5);
        buffer.putInt(versionCode);
        buffer.put(flags);
        return buffer.array();
    }

    public static int decodeHandshakeVersion(@NonNull byte[] data) {
        if (data.length < 4) return -1;
        return ByteBuffer.wrap(data).getInt();
    }
}
