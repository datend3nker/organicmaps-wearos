package app.organicmaps.sdk.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sdk.bookmarks.data.Bookmark;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkInfo;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.Icon;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-bookmark, content-addressed sync engine shared by the watch (Kotlin) and phone (Java) layers.
 * The goal is a single unified bookmark pool: every bookmark present on both devices, full feature
 * parity with the phone, reconciled Nextcloud-style (bidirectional, offline-tolerant, last-writer-wins).
 *
 * <p>Replaces the old category-grained KMZ export/import sync, which used the category <b>name</b> as
 * the sync unit: a same-named KMZ imported on the peer collided, the engine uniquified it
 * ("My Places" -> "My Places1"), the transient category fired a metadata broadcast, the peer pulled
 * it, and the bidirectional reconcile turned that into an exponential cascade
 * (1 -> 11 -> 111 -> ...). The post-hoc merge could never win that race.
 *
 * <p>Here the sync unit is the individual bookmark. Identity is content-addressed
 * ({@code cat|name|latQ|lonQ}, the same key as {@link BookmarkTombstoneStore}), so both devices
 * derive the same id for the same logical pin without any server-assigned GUID. There is no KMZ, no
 * file import, and no category uniquify step — categories are pure name buckets, found-or-created by
 * exact name. Applying a remote batch never collides, so the cascade is structurally impossible.
 *
 * <p><b>Synced fields:</b> category, name, position, color, icon type, description — i.e. everything
 * the phone's bookmark editor exposes (tracks are out of scope; they have no per-point native API).
 *
 * <p><b>Conflict resolution</b> is last-writer-wins by timestamp per identity. The local timestamp is
 * bumped whenever a bookmark's <i>content</i> (name/color/type/description) changes, detected by
 * diffing a stored per-identity content hash — so edits propagate, not just adds. Deletions ride
 * {@link BookmarkTombstoneStore} (a tombstone with ts >= the upsert ts suppresses re-creation).
 *
 * <p>All build/apply/stamp methods touch {@link BookmarkManager} and so MUST run on the engine's main
 * (UI) thread; callers set their own {@code isApplyingRemoteUpdate} guard around apply.
 */
public final class BookmarkSyncCore
{
  private static final String TAG = "BookmarkSyncCore";
  private static final String LWW_PREFS = "bookmark_lww";
  private static final String HASH_PREFIX = "hash_"; // content hash; raw key = LWW timestamp

  private BookmarkSyncCore() {}

  private static SharedPreferences lww(@NonNull Context c)
  {
    return c.getSharedPreferences(LWW_PREFS, Context.MODE_PRIVATE);
  }

  private static int contentHash(@NonNull String name, int color, int type, @NonNull String desc)
  {
    return Objects.hash(name, color, type, desc);
  }

  // ---- wire format -------------------------------------------------------
  // [count:4] then per record:
  //   [catLen:4][cat][nameLen:4][name][lat:8][lon:8][color:4][type:4][descLen:4][desc][ts:8]

  /**
   * Serialize every local bookmark (with all synced fields) as an upsert batch, stamped with each
   * identity's stored LWW timestamp. Run {@link #stampLocalChange} first on local edits so new and
   * edited bookmarks carry a fresh timestamp; here we only fall back to {@code now} for any identity
   * still unstamped, so a fresh device never contributes ts 0.
   */
  @NonNull
  public static byte[] buildUpsertBatch(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    long now = System.currentTimeMillis();

    java.util.ArrayList<byte[]> records = new java.util.ArrayList<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      String catName = cat.getName();
      int count = cat.getBookmarksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getBookmarkIdByPosition(i);
        BookmarkInfo info = mgr.getBookmarkInfo(id);
        if (info == null)
          continue;
        String name = info.getName();
        Icon icon = info.getIcon();
        int color = icon.getColor();
        int type = icon.getType();
        String desc = info.getDescription();

        String key = BookmarkTombstoneStore.identityKey(catName, name, info.getLat(), info.getLon());
        long ts = prefs.getLong(key, 0);
        if (ts == 0)
        {
          ts = now;
          editor.putLong(key, ts);
          editor.putInt(HASH_PREFIX + key, contentHash(name, color, type, desc));
        }
        records.add(encodeRecord(catName, name, info.getLat(), info.getLon(), color, type, desc, ts));
      }
    }
    editor.apply();

    int size = 4;
    for (byte[] r : records)
      size += r.length;
    ByteBuffer b = ByteBuffer.allocate(size);
    b.putInt(records.size());
    for (byte[] r : records)
      b.put(r);
    Log.d(TAG, "Built upsert batch: " + records.size() + " bookmark(s)");
    return b.array();
  }

  @NonNull
  private static byte[] encodeRecord(@NonNull String cat, @NonNull String name, double lat, double lon,
                                     int color, int type, @NonNull String desc, long ts)
  {
    byte[] catB = cat.getBytes(StandardCharsets.UTF_8);
    byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
    byte[] descB = desc.getBytes(StandardCharsets.UTF_8);
    ByteBuffer b = ByteBuffer.allocate(4 + catB.length + 4 + nameB.length + 8 + 8 + 4 + 4 + 4 + descB.length + 8);
    b.putInt(catB.length);
    b.put(catB);
    b.putInt(nameB.length);
    b.put(nameB);
    b.putDouble(lat);
    b.putDouble(lon);
    b.putInt(color);
    b.putInt(type);
    b.putInt(descB.length);
    b.put(descB);
    b.putLong(ts);
    return b.array();
  }

  /**
   * Apply a received upsert batch with per-identity LWW. For each record: a newer-or-equal tombstone
   * suppresses it (delete wins); a new identity is created; an existing identity is updated in place
   * when the remote stamp is newer (name same — it's part of the identity — but color/type/description
   * may have changed). Idempotent. MUST run on the UI thread under the caller's remote-update guard.
   */
  public static void applyUpsertBatch(@NonNull Context c, @NonNull BookmarkManager mgr, @NonNull byte[] payload)
  {
    ByteBuffer b = ByteBuffer.wrap(payload);
    if (b.remaining() < 4)
      return;
    int count = b.getInt();
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    Map<String, Long> tombstones = BookmarkTombstoneStore.all(c);

    int created = 0, updated = 0;
    for (int i = 0; i < count; i++)
    {
      String cat = readString(b);
      String name = readString(b);
      if (cat == null || name == null || b.remaining() < 8 + 8 + 4 + 4 + 4)
        break;
      double lat = b.getDouble();
      double lon = b.getDouble();
      int color = b.getInt();
      int type = b.getInt();
      String desc = readString(b);
      if (desc == null || b.remaining() < 8)
        break;
      long ts = b.getLong();

      String key = BookmarkTombstoneStore.identityKey(cat, name, lat, lon);

      Long tomb = tombstones.get(key);
      if (tomb != null && tomb >= ts)
        continue; // deletion wins over this (or an equally-old) upsert

      long localTs = prefs.getLong(key, 0);
      long existingId = findBookmarkId(mgr, cat, name, lat, lon);

      if (existingId == -1)
      {
        if (createBookmark(mgr, cat, name, lat, lon, color, type, desc))
        {
          created++;
          editor.putLong(key, ts);
          editor.putInt(HASH_PREFIX + key, contentHash(name, color, type, desc));
        }
      }
      else if (ts > localTs)
      {
        try
        {
          BookmarkInfo info = mgr.getBookmarkInfo(existingId);
          if (info != null)
            info.update(name, new Icon(color, type), desc);
          updated++;
        }
        catch (Exception e)
        {
          Log.w(TAG, "update failed for '" + name + "': " + e.getMessage(), e);
        }
        editor.putLong(key, ts);
        editor.putInt(HASH_PREFIX + key, contentHash(name, color, type, desc));
      }
    }
    editor.apply();
    if (created > 0 || updated > 0)
      Log.d(TAG, "Applied upsert batch: created " + created + ", updated " + updated);
  }

  /**
   * Refresh per-identity LWW timestamps for local edits: bump {@code ts} to {@code now} for any
   * bookmark whose content hash changed (new, renamed-in-place, recolored, re-described). Call on
   * every genuine local bookmark change before pushing. Cleans up hashes for vanished identities.
   */
  public static void stampLocalChange(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    long now = System.currentTimeMillis();
    java.util.HashSet<String> live = new java.util.HashSet<>();

    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getBookmarksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getBookmarkIdByPosition(i);
        BookmarkInfo info = mgr.getBookmarkInfo(id);
        if (info == null)
          continue;
        Icon icon = info.getIcon();
        String name = info.getName();
        int hash = contentHash(name, icon.getColor(), icon.getType(), info.getDescription());
        String key = BookmarkTombstoneStore.identityKey(cat.getName(), name, info.getLat(), info.getLon());
        live.add(key);
        live.add(HASH_PREFIX + key);
        if (prefs.getInt(HASH_PREFIX + key, Integer.MIN_VALUE) != hash)
        {
          editor.putLong(key, now);
          editor.putInt(HASH_PREFIX + key, hash);
        }
      }
    }
    editor.apply();
  }

  // ---- helpers -----------------------------------------------------------

  @Nullable
  private static String readString(@NonNull ByteBuffer b)
  {
    if (b.remaining() < 4)
      return null;
    int len = b.getInt();
    if (len < 0 || b.remaining() < len)
      return null;
    byte[] bytes = new byte[len];
    b.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Nullable
  private static BookmarkCategory findCategory(@NonNull BookmarkManager mgr, @NonNull String name)
  {
    for (BookmarkCategory cat : mgr.getCategories())
      if (cat.getName().equalsIgnoreCase(name))
        return cat;
    return null;
  }

  /** Find a bookmark by content identity within its named category, or -1. */
  private static long findBookmarkId(@NonNull BookmarkManager mgr, @NonNull String catName,
                                     @NonNull String name, double lat, double lon)
  {
    BookmarkCategory cat = findCategory(mgr, catName);
    if (cat == null)
      return -1;
    String target = BookmarkTombstoneStore.identityKey(catName, name, lat, lon);
    int count = cat.getBookmarksCount();
    for (int i = 0; i < count; i++)
    {
      long id = cat.getBookmarkIdByPosition(i);
      BookmarkInfo info = mgr.getBookmarkInfo(id);
      if (info == null)
        continue;
      if (BookmarkTombstoneStore.identityKey(catName, info.getName(), info.getLat(), info.getLon()).equals(target))
        return id;
    }
    return -1;
  }

  /** Create a bookmark at (lat,lon) with all fields in the found-or-created category. */
  private static boolean createBookmark(@NonNull BookmarkManager mgr, @NonNull String catName,
                                        @NonNull String name, double lat, double lon,
                                        int color, int type, @NonNull String desc)
  {
    try
    {
      BookmarkCategory cat = findCategory(mgr, catName);
      long catId = (cat != null) ? cat.getId() : mgr.createCategory(catName);

      // addNewBookmark drops the pin into the last-edited category; move it to the target and set
      // all params (name, color, icon type, description).
      Bookmark bmk = mgr.addNewBookmark(lat, lon);
      if (bmk == null)
        return false;
      long bmkId = bmk.getBookmarkId();
      if (bmk.getCategoryId() != catId)
        bmk.setCategoryId(catId);
      new BookmarkInfo(catId, bmkId).update(name, new Icon(color, type), desc);
      return true;
    }
    catch (Exception e)
    {
      Log.w(TAG, "createBookmark failed for '" + name + "': " + e.getMessage(), e);
      return false;
    }
  }
}
