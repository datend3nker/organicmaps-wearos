package app.organicmaps.sdk.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkInfo;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistent, propagated record of individually-deleted bookmarks.
 *
 * <p>Background bookmark sync is a non-destructive <b>union</b>-merge: it never removes a bookmark.
 * Without tombstones, a bookmark deleted on one device is re-added from the other device's copy on
 * the next sync. A tombstone records the identity of a deleted bookmark plus a deletion timestamp;
 * it is sent to the peer (live) and applied after every union-merge (offline case), so the deletion
 * sticks on both sides.
 *
 * <p>Identity = lowercased category name + lowercased bookmark name + position (lat/lon quantized to
 * ~1.1&nbsp;m). This mirrors the de-duplication identity used by the native category merge.
 *
 * <p>A tombstone is cleared when the user re-creates a bookmark with the same identity (so a delete
 * is not permanent), and the whole store is garbage-collected after {@link #MAX_AGE_MS}.
 *
 * <p>This class is shared by the watch (Kotlin) and phone (Java) sync layers so the wire format and
 * matching rules have a single source of truth.
 */
public final class BookmarkTombstoneStore
{
  private static final String TAG = "BookmarkTombstone";
  private static final String PREFS = "bookmark_tombstones";
  private static final long MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

  private BookmarkTombstoneStore() {}

  // ---- identity ----------------------------------------------------------

  public static long quant(double deg)
  {
    return Math.round(deg * 1e5); // ~1.1 m at the equator
  }

  @NonNull
  public static String identityKey(@NonNull String categoryName, @NonNull String bookmarkName, double lat, double lon)
  {
    return categoryName.toLowerCase() + "|" + bookmarkName.toLowerCase() + "|" + quant(lat) + "|" + quant(lon);
  }

  // ---- wire format -------------------------------------------------------
  // [ts:8][catLen:4][cat][nameLen:4][name][lat:8][lon:8]

  @NonNull
  public static byte[] encode(@NonNull String categoryName, @NonNull String bookmarkName, double lat, double lon, long ts)
  {
    byte[] cat = categoryName.getBytes(StandardCharsets.UTF_8);
    byte[] name = bookmarkName.getBytes(StandardCharsets.UTF_8);
    ByteBuffer b = ByteBuffer.allocate(8 + 4 + cat.length + 4 + name.length + 8 + 8);
    b.putLong(ts);
    b.putInt(cat.length);
    b.put(cat);
    b.putInt(name.length);
    b.put(name);
    b.putDouble(lat);
    b.putDouble(lon);
    return b.array();
  }

  @Nullable
  public static Parsed decode(@NonNull byte[] payload)
  {
    try
    {
      ByteBuffer b = ByteBuffer.wrap(payload);
      long ts = b.getLong();
      int catLen = b.getInt();
      byte[] cat = new byte[catLen];
      b.get(cat);
      int nameLen = b.getInt();
      byte[] name = new byte[nameLen];
      b.get(name);
      double lat = b.getDouble();
      double lon = b.getDouble();
      Parsed p = new Parsed();
      p.categoryName = new String(cat, StandardCharsets.UTF_8);
      p.bookmarkName = new String(name, StandardCharsets.UTF_8);
      p.lat = lat;
      p.lon = lon;
      p.ts = ts;
      return p;
    }
    catch (Exception e)
    {
      Log.w(TAG, "Failed to decode tombstone payload: " + e.getMessage());
      return null;
    }
  }

  public static final class Parsed
  {
    public String categoryName;
    public String bookmarkName;
    public double lat;
    public double lon;
    public long ts;

    @NonNull
    public String key()
    {
      return identityKey(categoryName, bookmarkName, lat, lon);
    }
  }

  // ---- store -------------------------------------------------------------

  private static SharedPreferences prefs(@NonNull Context c)
  {
    return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  /** Record (or refresh to the newer timestamp) a tombstone. */
  public static void record(@NonNull Context c, @NonNull String key, long ts)
  {
    SharedPreferences p = prefs(c);
    if (ts > p.getLong(key, 0))
      p.edit().putLong(key, ts).apply();
  }

  /** Drop a tombstone (e.g. the user re-created a bookmark with that identity). */
  public static void clear(@NonNull Context c, @NonNull String key)
  {
    if (prefs(c).contains(key))
      prefs(c).edit().remove(key).apply();
  }

  public static boolean contains(@NonNull Context c, @NonNull String key)
  {
    return prefs(c).contains(key);
  }

  /** All live tombstones (after garbage-collecting expired ones). key -> deletion ts. */
  @NonNull
  @SuppressWarnings("unchecked")
  public static Map<String, Long> all(@NonNull Context c)
  {
    gc(c);
    return (Map<String, Long>) (Map<String, ?>) prefs(c).getAll();
  }

  public static void gc(@NonNull Context c)
  {
    long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
    SharedPreferences p = prefs(c);
    SharedPreferences.Editor e = null;
    for (Map.Entry<String, ?> entry : p.getAll().entrySet())
    {
      Object v = entry.getValue();
      if (v instanceof Long && (Long) v < cutoff)
      {
        if (e == null)
          e = p.edit();
        e.remove(entry.getKey());
      }
    }
    if (e != null)
      e.apply();
  }

  // ---- apply -------------------------------------------------------------

  /**
   * Delete from {@code category} every bookmark whose identity is tombstoned. Must run on the UI
   * thread (it mutates BookmarkManager). Returns the number of bookmarks removed.
   */
  public static int applyToCategory(@NonNull Context c, @NonNull BookmarkManager mgr, @NonNull BookmarkCategory category)
  {
    Map<String, Long> tombstones = all(c);
    if (tombstones.isEmpty())
      return 0;

    List<Long> toDelete = new ArrayList<>();
    int count = category.getBookmarksCount();
    for (int i = 0; i < count; i++)
    {
      long id = category.getBookmarkIdByPosition(i);
      BookmarkInfo info = mgr.getBookmarkInfo(id);
      if (info == null || info.getCategoryId() != category.getId())
        continue;
      String key = identityKey(category.getName(), info.getName(), info.getLat(), info.getLon());
      Long tombTs = tombstones.get(key);
      if (tombTs == null)
        continue;
      // Respect last-writer-wins: a bookmark revived by a strictly-newer upsert (its stored LWW ts >
      // the tombstone) must survive an older, now-obsolete tombstone. Delete wins on an exact tie,
      // matching applyUpsertBatch's tomb >= ts suppression.
      if (BookmarkSyncCore.lwwTimestamp(c, key) > tombTs)
        continue;
      toDelete.add(id);
    }
    for (long id : toDelete)
      mgr.deleteBookmark(id);
    if (!toDelete.isEmpty())
      Log.d(TAG, "Applied tombstones to '" + category.getName() + "': removed " + toDelete.size() + " bookmark(s)");
    return toDelete.size();
  }

  /** Quantized position ("latQ|lonQ") of every bookmark across all categories. */
  @NonNull
  public static Set<String> allCurrentPositions(@NonNull BookmarkManager mgr)
  {
    Set<String> positions = new HashSet<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getBookmarksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getBookmarkIdByPosition(i);
        BookmarkInfo info = mgr.getBookmarkInfo(id);
        if (info != null)
          positions.add(quant(info.getLat()) + "|" + quant(info.getLon()));
      }
    }
    return positions;
  }

  /** Extract the "latQ|lonQ" position suffix from an identity key (cat|name|latQ|lonQ), or null. */
  @Nullable
  public static String positionOfKey(@NonNull String key)
  {
    int lo = key.lastIndexOf('|');
    if (lo <= 0) return null;
    int la = key.lastIndexOf('|', lo - 1);
    if (la <= 0) return null;
    return key.substring(la + 1);
  }

  /** Current identity set of a category (for snapshot/diff-based deletion detection). */
  @NonNull
  public static Set<String> currentIdentities(@NonNull BookmarkManager mgr, @NonNull BookmarkCategory category)
  {
    Set<String> ids = new HashSet<>();
    int count = category.getBookmarksCount();
    for (int i = 0; i < count; i++)
    {
      long id = category.getBookmarkIdByPosition(i);
      BookmarkInfo info = mgr.getBookmarkInfo(id);
      if (info != null)
        ids.add(identityKey(category.getName(), info.getName(), info.getLat(), info.getLon()));
    }
    return ids;
  }

  /** Move tombstones recorded under {@code oldCategoryName} to {@code newCategoryName}'s identity key. */
  public static void migrateCategoryRename(@NonNull Context c, @NonNull String oldCategoryName, @NonNull String newCategoryName)
  {
    String oldPrefix = oldCategoryName.toLowerCase() + "|";
    SharedPreferences p = prefs(c);
    SharedPreferences.Editor e = null;
    for (Map.Entry<String, ?> entry : p.getAll().entrySet())
    {
      String key = entry.getKey();
      if (!key.startsWith(oldPrefix) || !(entry.getValue() instanceof Long))
        continue;
      if (e == null)
        e = p.edit();
      String newKey = newCategoryName.toLowerCase() + "|" + key.substring(oldPrefix.length());
      e.remove(key);
      e.putLong(newKey, (Long) entry.getValue());
    }
    if (e != null)
      e.apply();
  }

  /** Build a wire payload directly from a stored identity key (cat|name|latQ|lonQ) + timestamp. */
  @Nullable
  public static byte[] encodeFromKey(@NonNull String key, long ts)
  {
    // Split into exactly 4 fields from the right so category/bookmark names may contain '|'.
    int lo = key.lastIndexOf('|');
    if (lo <= 0) return null;
    int la = key.lastIndexOf('|', lo - 1);
    if (la <= 0) return null;
    int nm = key.indexOf('|');
    if (nm < 0 || nm >= la) return null;
    try
    {
      String cat = key.substring(0, nm);
      String name = key.substring(nm + 1, la);
      double lat = Long.parseLong(key.substring(la + 1, lo)) / 1e5;
      double lon = Long.parseLong(key.substring(lo + 1)) / 1e5;
      return encode(cat, name, lat, lon, ts);
    }
    catch (Exception e)
    {
      return null;
    }
  }
}
