package app.organicmaps.sdk.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.Track;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Saved-track sync engine shared by the watch (Kotlin) and phone (Java) layers. The goal mirrors the
 * bookmark engine ({@link BookmarkSyncCore}): a single unified track pool, every saved track present
 * on both devices, reconciled last-writer-wins, offline-tolerant.
 *
 * <p>Tracks are polylines: too heavy for an inline upsert wire, and there is no native API to read a
 * track's raw points or build a track from points. So sync is two-tier:
 * <ul>
 *   <li><b>Manifest</b> (cheap, frequent): per-track metadata — uuid, name, color, category, ts —
 *       telling the peer <i>which</i> tracks exist. LWW + tombstones, exactly like bookmark upsert.</li>
 *   <li><b>Blob</b> (heavy, on demand): the track's geometry as a KMZ, fetched only when the peer
 *       sees a manifest entry for a uuid it lacks. Shipped over the chunked file channel and imported
 *       via {@link BookmarkManager#loadBookmarksFile(String, boolean, long)} (native merges the
 *       imported temp category into the target category and deletes the leftover).</li>
 * </ul>
 *
 * <p><b>Identity</b> is a UUID minted at save time and embedded in the track <b>description</b> as a
 * hidden marker. Native trackIds are not stable across app restart / kml reload, so an id-keyed map
 * would be fragile; the description survives both a restart and the KMZ round-trip (it is serialized
 * into the KML natively), so the same logical track carries the same uuid on both devices without any
 * server-assigned id. This is the track analog of the bookmark content-addressed key.
 *
 * <p><b>Visibility is not synced</b> — it is category-level and per-device by design.
 *
 * <p>All build/apply methods touch {@link BookmarkManager} and MUST run on the engine's UI thread.
 * Minting a uuid mutates the track (writes its description) the first time, so callers should set
 * their remote-update guard around these calls to avoid re-entrant change notifications.
 */
public final class TrackSyncCore
{
  private static final String TAG = "TrackSyncCore";
  private static final String LWW_PREFS = "track_lww";          // uuid -> ts; HASH_PREFIX+uuid -> content hash
  private static final String KNOWN_PREFS = "track_known";       // uuid -> 1 (last-seen local set, for deletion detect)
  private static final String TOMB_PREFS = "track_tombstones";   // uuid -> deletion ts
  private static final String HASH_PREFIX = "hash_";
  private static final long MAX_TOMB_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 30 days

  // Hidden marker appended to a track's description to carry its stable sync uuid. The leading
  // zero-width space keeps it visually unobtrusive; display code calls displayDescription() to strip.
  private static final String MARKER = "​#trksync:";

  private TrackSyncCore() {}

  private static SharedPreferences lww(@NonNull Context c) { return c.getSharedPreferences(LWW_PREFS, Context.MODE_PRIVATE); }
  private static SharedPreferences known(@NonNull Context c) { return c.getSharedPreferences(KNOWN_PREFS, Context.MODE_PRIVATE); }
  private static SharedPreferences tomb(@NonNull Context c) { return c.getSharedPreferences(TOMB_PREFS, Context.MODE_PRIVATE); }

  private static int contentHash(@NonNull String name, int color, @NonNull String cat, @NonNull String desc)
  {
    return Objects.hash(name, color, cat, desc);
  }

  // ---- uuid <-> description ----------------------------------------------

  /** The user-visible part of a track description (marker stripped). */
  @NonNull
  public static String displayDescription(@Nullable String raw)
  {
    if (raw == null) return "";
    int i = raw.indexOf(MARKER);
    return i < 0 ? raw : raw.substring(0, i);
  }

  /** Extract the embedded sync uuid from a description, or null if absent. */
  @Nullable
  public static String extractSyncId(@Nullable String desc)
  {
    if (desc == null) return null;
    int i = desc.indexOf(MARKER);
    if (i < 0) return null;
    String id = desc.substring(i + MARKER.length()).trim();
    return id.isEmpty() ? null : id;
  }

  /** Produce a description carrying {@code uuid} while preserving the visible text. */
  @NonNull
  public static String embedSyncId(@Nullable String desc, @NonNull String uuid)
  {
    return displayDescription(desc) + MARKER + uuid;
  }

  // ---- track enumeration helpers -----------------------------------------

  /** All (categoryName, trackId) pairs across every category. */
  @NonNull
  private static List<long[]> allTrackIds(@NonNull BookmarkManager mgr)
  {
    List<long[]> out = new ArrayList<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
        out.add(new long[] { cat.getId(), cat.getTrackIdByPosition(i) });
    }
    return out;
  }

  @Nullable
  private static BookmarkCategory findCategory(@NonNull BookmarkManager mgr, @NonNull String name)
  {
    for (BookmarkCategory cat : mgr.getCategories())
      if (cat.getName().equalsIgnoreCase(name))
        return cat;
    return null;
  }

  @Nullable
  private static BookmarkCategory findCategoryById(@NonNull BookmarkManager mgr, long id)
  {
    for (BookmarkCategory cat : mgr.getCategories())
      if (cat.getId() == id)
        return cat;
    return null;
  }

  /** Find the track carrying {@code uuid} in its description, or -1. */
  public static long findTrackByUuid(@NonNull BookmarkManager mgr, @NonNull String uuid)
  {
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getTrackIdByPosition(i);
        Track t = mgr.getTrack(id);
        if (t != null && uuid.equals(extractSyncId(t.getDescription())))
          return id;
      }
    }
    return -1;
  }

  /** Read the track's uuid, minting + persisting one in its description if absent. Mutates on mint. */
  @NonNull
  public static String ensureSyncId(@NonNull BookmarkManager mgr, long trackId)
  {
    Track t = mgr.getTrack(trackId);
    String existing = (t != null) ? extractSyncId(t.getDescription()) : null;
    if (existing != null)
      return existing;
    String uuid = UUID.randomUUID().toString();
    if (t != null)
      t.update(t.getName(), t.getColor(), embedSyncId(t.getDescription(), uuid));
    return uuid;
  }

  // ---- manifest wire -----------------------------------------------------
  // [count:4] then per record: [uuidLen:4][uuid][nameLen:4][name][color:4][catLen:4][cat][ts:8]

  /**
   * Serialize every local saved track as a manifest batch, stamped with each uuid's stored LWW ts
   * (falling back to {@code now} for any still unstamped). Mints uuids as needed. Also refreshes the
   * "known" set so {@link #detectDeletions} can later notice removals.
   */
  @NonNull
  public static byte[] buildManifest(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    SharedPreferences.Editor knownEd = known(c).edit().clear();
    long now = System.currentTimeMillis();

    List<byte[]> records = new ArrayList<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      String catName = cat.getName();
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getTrackIdByPosition(i);
        Track t = mgr.getTrack(id);
        if (t == null)
          continue;
        String uuid = ensureSyncId(mgr, id);
        String name = t.getName();
        int color = t.getColor();
        long ts = prefs.getLong(uuid, 0);
        if (ts == 0)
        {
          ts = now;
          editor.putLong(uuid, ts);
          editor.putInt(HASH_PREFIX + uuid, contentHash(name, color, catName, displayDescription(t.getDescription())));
        }
        knownEd.putInt(uuid, 1);
        records.add(encodeRecord(uuid, name, color, catName, ts));
      }
    }
    editor.apply();
    knownEd.apply();

    int size = 4;
    for (byte[] r : records)
      size += r.length;
    ByteBuffer b = ByteBuffer.allocate(size);
    b.putInt(records.size());
    for (byte[] r : records)
      b.put(r);
    Log.d(TAG, "Built track manifest: " + records.size() + " track(s)");
    return b.array();
  }

  @NonNull
  private static byte[] encodeRecord(@NonNull String uuid, @NonNull String name, int color, @NonNull String cat, long ts)
  {
    byte[] u = uuid.getBytes(StandardCharsets.UTF_8);
    byte[] n = name.getBytes(StandardCharsets.UTF_8);
    byte[] ca = cat.getBytes(StandardCharsets.UTF_8);
    ByteBuffer b = ByteBuffer.allocate(4 + u.length + 4 + n.length + 4 + 4 + ca.length + 8);
    b.putInt(u.length); b.put(u);
    b.putInt(n.length); b.put(n);
    b.putInt(color);
    b.putInt(ca.length); b.put(ca);
    b.putLong(ts);
    return b.array();
  }

  /** A track named in a peer manifest that this device does not yet have — caller fetches its blob. */
  public static final class Missing
  {
    public final String uuid;
    public final String name;
    public final String categoryName;
    public final long ts;
    Missing(String uuid, String name, String categoryName, long ts)
    { this.uuid = uuid; this.name = name; this.categoryName = categoryName; this.ts = ts; }
  }

  /**
   * Apply a received manifest with per-uuid LWW. Tracks already present are renamed / recolored /
   * moved in place when the remote stamp is newer. Tracks absent locally (and not tombstoned newer)
   * are returned as {@link Missing} so the caller can request their blobs. Idempotent; UI thread only.
   */
  @NonNull
  public static List<Missing> applyManifest(@NonNull Context c, @NonNull BookmarkManager mgr, @NonNull byte[] payload)
  {
    List<Missing> missing = new ArrayList<>();
    ByteBuffer b = ByteBuffer.wrap(payload);
    if (b.remaining() < 4)
      return missing;
    int count = b.getInt();
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    Map<String, Long> tombstones = allTombstones(c);

    int updated = 0;
    for (int i = 0; i < count; i++)
    {
      String uuid = readString(b);
      String name = readString(b);
      if (uuid == null || name == null || b.remaining() < 4)
        break;
      int color = b.getInt();
      String cat = readString(b);
      if (cat == null || b.remaining() < 8)
        break;
      long ts = b.getLong();

      Long tomb = tombstones.get(uuid);
      if (tomb != null && tomb >= ts)
        continue; // deletion wins over this (or an equally-old) manifest entry
      if (tomb != null)
      {
        clearTombstone(c, uuid);
        tombstones.remove(uuid);
      }

      long localTs = prefs.getLong(uuid, 0);
      long trackId = findTrackByUuid(mgr, uuid);

      if (trackId == -1)
      {
        missing.add(new Missing(uuid, name, cat, ts));
        continue;
      }
      if (ts > localTs)
      {
        try
        {
          Track t = mgr.getTrack(trackId);
          if (t != null)
          {
            // Preserve the embedded uuid in the description while applying the remote name/color.
            t.update(name, color, embedSyncId(t.getDescription(), uuid));
            BookmarkCategory cur = findCategoryById(mgr, t.getCategoryId());
            if (cur == null || !cur.getName().equalsIgnoreCase(cat))
            {
              BookmarkCategory toCat = findCategory(mgr, cat);
              long toCatId = (toCat != null) ? toCat.getId() : mgr.createCategory(cat);
              t.setCategoryId(toCatId);
            }
            updated++;
          }
        }
        catch (Exception e)
        {
          Log.w(TAG, "track update failed for '" + name + "': " + e.getMessage(), e);
        }
        editor.putLong(uuid, ts);
      }
    }
    editor.apply();
    if (updated > 0)
      Log.d(TAG, "Applied track manifest: updated " + updated + ", missing " + missing.size());
    return missing;
  }

  /**
   * Bump per-uuid LWW timestamps for local edits (rename / recolor / move / new), detected by a
   * content hash diff. Mints uuids as needed. Call on every genuine local track change before pushing.
   */
  public static void stampLocalChange(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    SharedPreferences prefs = lww(c);
    SharedPreferences.Editor editor = prefs.edit();
    long now = System.currentTimeMillis();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getTrackIdByPosition(i);
        Track t = mgr.getTrack(id);
        if (t == null)
          continue;
        String uuid = ensureSyncId(mgr, id);
        int hash = contentHash(t.getName(), t.getColor(), cat.getName(), displayDescription(t.getDescription()));
        if (prefs.getInt(HASH_PREFIX + uuid, Integer.MIN_VALUE) != hash)
        {
          editor.putLong(uuid, now);
          editor.putInt(HASH_PREFIX + uuid, hash);
        }
      }
    }
    editor.apply();
  }

  /** After importing a track blob, record its manifest ts so we don't bounce it back as a local edit. */
  public static void recordImported(@NonNull Context c, @NonNull BookmarkManager mgr, @NonNull String uuid, long ts)
  {
    long id = findTrackByUuid(mgr, uuid);
    SharedPreferences.Editor e = lww(c).edit().putLong(uuid, ts);
    if (id != -1)
    {
      Track t = mgr.getTrack(id);
      BookmarkCategory cat = (t != null) ? findCategoryById(mgr, t.getCategoryId()) : null;
      if (t != null && cat != null)
        e.putInt(HASH_PREFIX + uuid, contentHash(t.getName(), t.getColor(), cat.getName(), displayDescription(t.getDescription())));
    }
    e.apply();
    known(c).edit().putInt(uuid, 1).apply();
  }

  // ---- deletions / tombstones --------------------------------------------

  /**
   * Compare the current local uuid set against the last-known set ({@link #buildManifest} refreshes
   * it) and tombstone any uuid that vanished, so a local delete propagates to the peer. Returns the
   * newly-tombstoned uuids (the caller sends a tombstone message for each).
   */
  @NonNull
  public static List<String> detectDeletions(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    Set<String> current = new HashSet<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
      {
        Track t = mgr.getTrack(cat.getTrackIdByPosition(i));
        String uuid = (t != null) ? extractSyncId(t.getDescription()) : null;
        if (uuid != null)
          current.add(uuid);
      }
    }
    List<String> removed = new ArrayList<>();
    long now = System.currentTimeMillis();
    SharedPreferences knownPrefs = known(c);
    for (String uuid : knownPrefs.getAll().keySet())
    {
      if (!current.contains(uuid))
      {
        recordTombstone(c, uuid, now);
        lww(c).edit().remove(uuid).remove(HASH_PREFIX + uuid).apply();
        removed.add(uuid);
      }
    }
    // Refresh the known set to match the current reality.
    SharedPreferences.Editor ed = knownPrefs.edit().clear();
    for (String uuid : current)
      ed.putInt(uuid, 1);
    ed.apply();
    return removed;
  }

  public static void recordTombstone(@NonNull Context c, @NonNull String uuid, long ts)
  {
    SharedPreferences p = tomb(c);
    if (ts > p.getLong(uuid, 0))
      p.edit().putLong(uuid, ts).apply();
  }

  public static void clearTombstone(@NonNull Context c, @NonNull String uuid)
  {
    if (tomb(c).contains(uuid))
      tomb(c).edit().remove(uuid).apply();
  }

  @NonNull
  @SuppressWarnings("unchecked")
  public static Map<String, Long> allTombstones(@NonNull Context c)
  {
    gcTombstones(c);
    return (Map<String, Long>) (Map<String, ?>) tomb(c).getAll();
  }

  private static void gcTombstones(@NonNull Context c)
  {
    long cutoff = System.currentTimeMillis() - MAX_TOMB_AGE_MS;
    SharedPreferences p = tomb(c);
    SharedPreferences.Editor e = null;
    for (Map.Entry<String, ?> entry : p.getAll().entrySet())
    {
      if (entry.getValue() instanceof Long && (Long) entry.getValue() < cutoff)
      {
        if (e == null) e = p.edit();
        e.remove(entry.getKey());
      }
    }
    if (e != null) e.apply();
  }

  /** Delete every local track whose uuid is tombstoned (and not revived by a newer LWW). UI thread. */
  public static int applyTombstones(@NonNull Context c, @NonNull BookmarkManager mgr)
  {
    Map<String, Long> tombstones = allTombstones(c);
    if (tombstones.isEmpty())
      return 0;
    List<Long> toDelete = new ArrayList<>();
    for (BookmarkCategory cat : mgr.getCategories())
    {
      int count = cat.getTracksCount();
      for (int i = 0; i < count; i++)
      {
        long id = cat.getTrackIdByPosition(i);
        Track t = mgr.getTrack(id);
        if (t == null) continue;
        String uuid = extractSyncId(t.getDescription());
        if (uuid == null) continue;
        Long tombTs = tombstones.get(uuid);
        if (tombTs == null) continue;
        if (lww(c).getLong(uuid, 0) > tombTs) continue; // revived by a newer edit — survive
        toDelete.add(id);
      }
    }
    for (long id : toDelete)
      mgr.deleteTrack(id);
    if (!toDelete.isEmpty())
      Log.d(TAG, "Applied track tombstones: removed " + toDelete.size() + " track(s)");
    return toDelete.size();
  }

  // ---- single tombstone wire ---------------------------------------------
  // [uuidLen:4][uuid][ts:8]

  @NonNull
  public static byte[] encodeTombstone(@NonNull String uuid, long ts)
  {
    byte[] u = uuid.getBytes(StandardCharsets.UTF_8);
    ByteBuffer b = ByteBuffer.allocate(4 + u.length + 8);
    b.putInt(u.length); b.put(u); b.putLong(ts);
    return b.array();
  }

  /** Apply a single received tombstone: record it then delete the matching local track. UI thread. */
  public static void applyTombstone(@NonNull Context c, @NonNull BookmarkManager mgr, @NonNull byte[] payload)
  {
    ByteBuffer b = ByteBuffer.wrap(payload);
    String uuid = readString(b);
    if (uuid == null || b.remaining() < 8)
      return;
    long ts = b.getLong();
    recordTombstone(c, uuid, ts);
    lww(c).edit().remove(uuid).remove(HASH_PREFIX + uuid).apply();
    known(c).edit().remove(uuid).apply();
    applyTombstones(c, mgr);
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
}
