package app.organicmaps.sdk.bookmarks.data;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.annotation.Keep;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.util.StorageUtils;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@MainThread
public enum BookmarkManager {
  INSTANCE;

  // These values have to match the values of kml::CompilationType from kml/types.hpp
  public static final int CATEGORY = 0;

  private static final String[] BOOKMARKS_EXTENSIONS = Framework.nativeGetBookmarksFilesExts();

  private static final String TAG = BookmarkManager.class.getSimpleName();

  @NonNull
  private final BookmarkCategoriesDataProvider mCategoriesCoreDataProvider = new CoreBookmarkCategoriesDataProvider();

  @NonNull
  private BookmarkCategoriesDataProvider mCurrentDataProvider = mCategoriesCoreDataProvider;

  private final BookmarkCategoriesCache mBookmarkCategoriesCache = new BookmarkCategoriesCache();

  @NonNull
  private final List<BookmarksLoadingListener> mListeners = new ArrayList<>();

  @NonNull
  private final List<BookmarksSortingListener> mSortingListeners = new ArrayList<>();

  @NonNull
  private final List<BookmarksSharingListener> mSharingListeners = new ArrayList<>();

  private final Map<String, Long> mPendingFileMerges = new HashMap<>();
  // FIFO of merge targets, used as a fallback when the load callback reports a different fileName than
  // the path we passed (OM renames the imported file/category on a name collision). Loads complete in
  // order, so the front entry is the target for the next merge-load callback.
  private final java.util.ArrayDeque<Long> mPendingMergeOrder = new java.util.ArrayDeque<>();
  // Snapshot of category ids taken right before each merge-load, keyed by the loaded path. After the
  // load, the imported category is whatever id is new vs the snapshot — this is how we locate it to
  // merge+delete. (nativeGetCategoryByFileName can't: OM uniquifies the imported category's filename
  // on a name collision, so querying by the source path returns -1 and the duplicate "My PlacesN"
  // survives → category explosion.)
  private final Map<String, java.util.Set<Long>> mPendingMergeSnapshots = new HashMap<>();
  private final java.util.ArrayDeque<java.util.Set<Long>> mPendingMergeSnapshotOrder = new java.util.ArrayDeque<>();

  @Nullable
  private OnElevationCurrentPositionChangedListener mOnElevationCurrentPositionChangedListener;

  @Nullable
  private OnElevationActivePointChangedListener mOnElevationActivePointChangedListener;

  @Nullable
  public Bookmark addNewBookmark(double lat, double lon)
  {
    return nativeAddBookmarkToLastEditedCategory(lat, lon);
  }

  public void addLoadingListener(@NonNull BookmarksLoadingListener listener)
  {
    mListeners.add(listener);
  }

  public void removeLoadingListener(@NonNull BookmarksLoadingListener listener)
  {
    mListeners.remove(listener);
  }

  public void addSortingListener(@NonNull BookmarksSortingListener listener)
  {
    mSortingListeners.add(listener);
  }

  public void removeSortingListener(@NonNull BookmarksSortingListener listener)
  {
    mSortingListeners.remove(listener);
  }

  public void addSharingListener(@NonNull BookmarksSharingListener listener)
  {
    mSharingListeners.add(listener);
  }

  public void removeSharingListener(@NonNull BookmarksSharingListener listener)
  {
    mSharingListeners.remove(listener);
  }

  public void setElevationActivePointChangedListener(@Nullable OnElevationActivePointChangedListener listener)
  {
    if (listener != null)
      nativeSetElevationActiveChangedListener();
    else
      nativeRemoveElevationActiveChangedListener();

    mOnElevationActivePointChangedListener = listener;
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  public void onBookmarksChanged()
  {
    Logger.d(TAG, "DEBUG_BOOKMARKS_PIPELINE: onBookmarksChanged (MANUAL change detected)");
    updateCache();
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onBookmarksLoadingStarted()
  {
    for (BookmarksLoadingListener listener : mListeners)
      listener.onBookmarksLoadingStarted();
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onBookmarksLoadingFinished()
  {
    updateCache();
    mCurrentDataProvider = new CacheBookmarkCategoriesDataProvider();
    for (BookmarksLoadingListener listener : mListeners)
      listener.onBookmarksLoadingFinished();
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onBookmarksSortingCompleted(@NonNull SortedBlock[] sortedBlocks, long timestamp)
  {
    for (BookmarksSortingListener listener : mSortingListeners)
      listener.onBookmarksSortingCompleted(sortedBlocks, timestamp);
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onBookmarksSortingCancelled(long timestamp)
  {
    for (BookmarksSortingListener listener : mSortingListeners)
      listener.onBookmarksSortingCancelled(timestamp);
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onBookmarksFileLoaded(boolean success, @NonNull String fileName, boolean isTemporaryFile)
  {
    // Native passes the source path we loaded as fileName, so the path key resolves the target.
    Long targetCategoryId = mPendingFileMerges.remove(fileName);
    java.util.Set<Long> snapshot = mPendingMergeSnapshots.remove(fileName);
    Logger.d(TAG, "onBookmarksFileLoaded: success=" + success + " fileName=" + fileName
        + " pathKeyTarget=" + targetCategoryId + " fifoSize=" + mPendingMergeOrder.size());
    if (targetCategoryId == null && !mPendingMergeOrder.isEmpty())
    {
      // Path key missed (shouldn't normally happen) — fall back to FIFO order; loads complete in
      // submission order, so the front entries belong to this callback.
      targetCategoryId = mPendingMergeOrder.peekFirst();
      snapshot = mPendingMergeSnapshotOrder.peekFirst();
    }
    if (success && targetCategoryId != null)
    {
      mPendingMergeOrder.remove(targetCategoryId);
      mPendingMergeSnapshotOrder.remove(snapshot);
      // The imported category is whichever id is new vs the pre-load snapshot. Merge it into the
      // intended target and delete the leftover, so a name collision never leaves a "My PlacesN"
      // duplicate (category explosion). nativeGetCategoryByFileName can't find it — the import was
      // renamed on collision.
      boolean merged = false;
      if (snapshot != null)
      {
        for (BookmarkCategory c : nativeGetBookmarkCategories())
        {
          long id = c.getId();
          if (id != targetCategoryId && !snapshot.contains(id))
          {
            Logger.d(TAG, "Merging imported category " + id + " (" + c.getName() + ") into " + targetCategoryId);
            nativeMergeCategories(id, targetCategoryId);
            nativeDeleteCategory(id);
            merged = true;
            break;
          }
        }
      }
      if (!merged)
        Logger.d(TAG, "onBookmarksFileLoaded: no new category to merge (snapshot=" + (snapshot == null ? "null" : snapshot.size()) + ", target=" + targetCategoryId + ")");
    }
    else if (!success && !mPendingMergeOrder.isEmpty())
    {
      mPendingMergeOrder.pollFirst(); // failed load — drop its target so the FIFO stays aligned
      mPendingMergeSnapshotOrder.pollFirst();
    }

    // Android could create temporary file with bookmarks in some cases (KML/KMZ file is a blob
    // in the intent, so we have to create a temporary file on the disk). Here we can delete it.
    if (isTemporaryFile)
    {
      File tmpFile = new File(fileName);
      tmpFile.delete();
    }

    if (success)
    {
      for (BookmarksLoadingListener listener : mListeners)
        listener.onBookmarksFileImportSuccessful();
    }
    else
    {
      for (BookmarksLoadingListener listener : mListeners)
        listener.onBookmarksFileImportFailed();
    }
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onPreparedFileForSharing(BookmarkSharingResult result)
  {
    for (BookmarksSharingListener listener : mSharingListeners)
      listener.onPreparedFileForSharing(result);
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onElevationCurrentPositionChanged()
  {
    if (mOnElevationCurrentPositionChangedListener != null)
      mOnElevationCurrentPositionChangedListener.onCurrentPositionChanged();
  }

  public void setElevationCurrentPositionChangedListener(@Nullable OnElevationCurrentPositionChangedListener listener)
  {
    if (listener != null)
      nativeSetElevationCurrentPositionChangedListener();
    else
      nativeRemoveElevationCurrentPositionChangedListener();

    mOnElevationCurrentPositionChangedListener = listener;
  }

  // Called from JNI.
  @Keep
  @SuppressWarnings("unused")
  @MainThread
  private void onElevationActivePointChanged()
  {
    if (mOnElevationActivePointChangedListener != null)
      mOnElevationActivePointChangedListener.onElevationActivePointChanged();
  }

  @Nullable
  public Bookmark updateBookmarkPlacePage(long bmkId)
  {
    return nativeUpdateBookmarkPlacePage(bmkId);
  }

  public void updateTrackPlacePage()
  {
    nativeUpdateTrackPlacePage();
  }

  @Nullable
  public BookmarkInfo getBookmarkInfo(long bmkId)
  {
    return nativeGetBookmarkInfo(bmkId);
  }

  @NonNull
  public Track getTrack(long trackId)
  {
    return nativeGetTrack(trackId, Track.class);
  }

  public static void loadBookmarks()
  {
    UiThread.run(BookmarkManager::nativeLoadBookmarks);
  }

  public void deleteCategory(long catId)
  {
    nativeDeleteCategory(catId);
  }

  public void deleteTrack(long trackId)
  {
    nativeDeleteTrack(trackId);
  }

  public void deleteBookmark(long bmkId)
  {
    nativeDeleteBookmark(bmkId);
  }

  public long createCategory(@NonNull String name)
  {
    return nativeCreateCategory(name);
  }

  public void showBookmarkOnMap(long bmkId)
  {
    // Guard against an id that does not exist in this engine instance (e.g. a bookmark id sent from
    // the watch, whose ids differ from the phone's). nativeShowBookmarkOnMap -> Framework::ShowBookmark
    // dereferences the mark without a null check and aborts natively on a bad id.
    if (nativeGetBookmarkInfo(bmkId) == null)
      return;
    nativeShowBookmarkOnMap(bmkId);
  }

  public void showBookmarkCategoryOnMap(long catId)
  {
    nativeShowBookmarkCategoryOnMap(catId);
  }

  @PredefinedColors.Color
  public int getLastEditedColor()
  {
    return nativeGetLastEditedColor();
  }

  @MainThread
  public void loadBookmarksFile(@NonNull String path, boolean isTemporaryFile)
  {
    Logger.d(TAG, "Loading bookmarks file from: " + path);
    nativeLoadBookmarksFile(path, isTemporaryFile);
  }

  @MainThread
  public void loadBookmarksFile(@NonNull String path, boolean isTemporaryFile, long targetCategoryId)
  {
    Logger.d(TAG, "Loading bookmarks file from: " + path + " for merge into " + targetCategoryId);
    mPendingFileMerges.put(path, targetCategoryId);
    mPendingMergeOrder.addLast(targetCategoryId);
    java.util.Set<Long> snapshot = currentCategoryIds();
    mPendingMergeSnapshots.put(path, snapshot);
    mPendingMergeSnapshotOrder.addLast(snapshot);
    nativeLoadBookmarksFile(path, isTemporaryFile);
  }

  /** Ids of all bookmark categories right now (used to detect the category an import creates). */
  @MainThread
  private java.util.Set<Long> currentCategoryIds()
  {
    java.util.Set<Long> ids = new java.util.HashSet<>();
    BookmarkCategory[] cats = nativeGetBookmarkCategories();
    if (cats != null)
    {
      for (BookmarkCategory c : cats)
        ids.add(c.getId());
    }
    return ids;
  }

  static @Nullable String getBookmarksFilenameFromUri(@NonNull ContentResolver resolver, @NonNull Uri uri)
  {
    String filename = null;
    final String scheme = uri.getScheme();
    if (scheme.equals("content"))
    {
      try (Cursor cursor = resolver.query(uri, null, null, null, null))
      {
        if (cursor != null && cursor.moveToFirst())
        {
          final int columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
          if (columnIndex >= 0)
            filename = cursor.getString(columnIndex);
        }
      }
    }

    if (filename == null)
    {
      filename = uri.getPath();
      if (filename == null)
        return null;
      final int cut = filename.lastIndexOf('/');
      if (cut != -1)
        filename = filename.substring(cut + 1);
    }
    // See IsBadCharForPath()
    filename = filename.replaceAll("[:/\\\\<>\"|?*]", "");

    final String lowerCaseFilename = filename.toLowerCase(java.util.Locale.ROOT);
    // Check that filename contains bookmarks extension.
    for (String ext : BOOKMARKS_EXTENSIONS)
    {
      if (lowerCaseFilename.endsWith(ext))
        return filename;
    }

    // Samsung browser adds .xml extension to downloaded gpx files.
    // Duplicate files have " (1).xml", " (2).xml" suffixes added.
    final String gpxExt = ".gpx";
    final int gpxStart = lowerCaseFilename.lastIndexOf(gpxExt);
    if (gpxStart != -1)
      return filename.substring(0, gpxStart + gpxExt.length());

    // Try get guess extension from the mime type.
    final String mime = resolver.getType(uri);
    if (mime != null)
    {
      final int i = mime.lastIndexOf('.');
      if (i != -1)
      {
        final String type = mime.substring(i + 1);
        if (type.equalsIgnoreCase("kmz"))
          return filename + ".kmz";
        else if (type.equalsIgnoreCase("kml+xml"))
          return filename + ".kml";
      }
      if (mime.endsWith("gpx+xml") || mime.endsWith("gpx")) // match application/gpx, application/gpx+xml
        return filename + ".gpx";
    }

    // WhatsApp doesn't provide correct mime type and extension for GPX files.
    if (uri.getHost().contains("com.whatsapp.provider.media"))
      return filename + ".gpx";

    return null;
  }

  @WorkerThread
  public boolean importBookmarksFile(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File tempDir)
  {
    Logger.i(TAG, "Importing bookmarks from " + uri);
    try
    {
      String filename = getBookmarksFilenameFromUri(resolver, uri);
      if (filename == null)
      {
        Logger.w(TAG, "Could not find a supported file type in " + uri);
        UiThread.run(() -> {
          for (BookmarksLoadingListener listener : mListeners)
            listener.onBookmarksFileUnsupported(uri);
        });
        return false;
      }

      Logger.d(TAG, "Downloading bookmarks file from " + uri + " into " + filename);
      final File tempFile = new File(tempDir, filename);
      StorageUtils.copyFile(resolver, uri, tempFile);
      Logger.d(TAG, "Downloaded bookmarks file from " + uri + " into " + filename);
      UiThread.run(() -> loadBookmarksFile(tempFile.getAbsolutePath(), true));
      return true;
    }
    catch (IOException | SecurityException e)
    {
      Logger.e(TAG, "Could not download bookmarks file from " + uri, e);
      UiThread.run(() -> {
        for (BookmarksLoadingListener listener : mListeners)
          listener.onBookmarksFileDownloadFailed(uri, e.toString());
      });
      return false;
    }
  }

  @WorkerThread
  public void importBookmarksFile(@NonNull ContentResolver resolver, @NonNull Uri uri, @NonNull File tempDir, long targetCategoryId)
  {
    Logger.i(TAG, "Importing bookmarks from " + uri + " for merge into " + targetCategoryId);
    try
    {
      String filename = getBookmarksFilenameFromUri(resolver, uri);
      if (filename == null)
      {
        Logger.w(TAG, "Could not find a supported file type in " + uri);
        UiThread.run(() -> {
          for (BookmarksLoadingListener listener : mListeners)
            listener.onBookmarksFileUnsupported(uri);
        });
        return;
      }

      final File tempFile = new File(tempDir, filename);
      StorageUtils.copyFile(resolver, uri, tempFile);
      UiThread.run(() -> loadBookmarksFile(tempFile.getAbsolutePath(), true, targetCategoryId));
    }
    catch (IOException | SecurityException e)
    {
      Logger.e(TAG, "Could not download bookmarks file from " + uri, e);
      UiThread.run(() -> {
        for (BookmarksLoadingListener listener : mListeners)
          listener.onBookmarksFileDownloadFailed(uri, e.toString());
      });
    }
  }

  @WorkerThread
  public void importBookmarksFiles(@NonNull ContentResolver resolver, @NonNull List<Uri> uris, @NonNull File tempDir)
  {
    for (Uri uri : uris)
      importBookmarksFile(resolver, uri, tempDir);
  }

  public boolean isAsyncBookmarksLoadingInProgress()
  {
    return nativeIsAsyncBookmarksLoadingInProgress();
  }

  @NonNull
  public List<BookmarkCategory> getCategories()
  {
    return mCurrentDataProvider.getCategories();
  }
  public int getCategoriesCount()
  {
    return mCurrentDataProvider.getCategoriesCount();
  }

  @NonNull
  BookmarkCategoriesCache getBookmarkCategoriesCache()
  {
    return mBookmarkCategoriesCache;
  }

  private void updateCache()
  {
    getBookmarkCategoriesCache().update(mCategoriesCoreDataProvider.getCategories());
  }

  public void addCategoriesUpdatesListener(@NonNull DataChangedListener listener)
  {
    getBookmarkCategoriesCache().registerListener(listener);
  }

  public void removeCategoriesUpdatesListener(@NonNull DataChangedListener listener)
  {
    getBookmarkCategoriesCache().unregisterListener(listener);
  }

  @Nullable
  public BookmarkCategory getCategoryById(long categoryId)
  {
    return mCurrentDataProvider.getCategoryById(categoryId);
  }

  public boolean isUsedCategoryName(@NonNull String name)
  {
    return nativeIsUsedCategoryName(name);
  }

  public void prepareForSearch(long catId)
  {
    nativePrepareForSearch(catId);
  }

  public boolean areAllCategoriesVisible()
  {
    return nativeAreAllCategoriesVisible();
  }

  public boolean areAllCategoriesInvisible()
  {
    return nativeAreAllCategoriesInvisible();
  }

  public void setAllCategoriesVisibility(boolean visible)
  {
    nativeSetAllCategoriesVisibility(visible);
  }

  public void prepareCategoriesForSharing(long[] catIds, @NonNull KmlFileType kmlFileType)
  {
    nativePrepareFileForSharing(catIds, kmlFileType.ordinal());
  }

  public void prepareTrackForSharing(long trackId, @NonNull KmlFileType kmlFileType)
  {
    nativePrepareTrackFileForSharing(trackId, kmlFileType.ordinal());
  }

  public void setNotificationsEnabled(boolean enabled)
  {
    nativeSetNotificationsEnabled(enabled);
  }

  public void getSortedCategory(long catId, @BookmarkCategory.SortingType int sortingType, boolean hasMyPosition,
                                double lat, double lon, long timestamp)
  {
    nativeGetSortedCategory(catId, sortingType, hasMyPosition, lat, lon, timestamp);
  }

  @NonNull
  public List<BookmarkCategory> getChildrenCategories(long catId)
  {
    return mCurrentDataProvider.getChildrenCategories(catId);
  }

  @NonNull
  native BookmarkCategory nativeGetBookmarkCategory(long catId);
  @NonNull
  native BookmarkCategory[] nativeGetBookmarkCategories();
  native int nativeGetBookmarkCategoriesCount();
  @NonNull
  native BookmarkCategory[] nativeGetChildrenCategories(long catId);

  public void setElevationActivePoint(long trackId, double distance, @NonNull ElevationInfo.Point point)
  {
    nativeSetElevationActivePoint(trackId, distance, point.getLatitude(), point.getLongitude());
  }

  @Nullable
  private native Bookmark nativeUpdateBookmarkPlacePage(long bmkId);

  private native void nativeUpdateTrackPlacePage();

  @Nullable
  private native BookmarkInfo nativeGetBookmarkInfo(long bmkId);

  @NonNull
  private native Track nativeGetTrack(long trackId, Class<Track> trackClazz);

  private static native void nativeLoadBookmarks();

  private native boolean nativeDeleteCategory(long catId);

  private native void nativeDeleteTrack(long trackId);

  private native void nativeDeleteBookmark(long bmkId);

  /**
   * @return category Id
   */
  private native long nativeCreateCategory(@NonNull String name);

  private native void nativeShowBookmarkOnMap(long bmkId);

  private native void nativeShowBookmarkCategoryOnMap(long catId);

  @Nullable
  private native Bookmark nativeAddBookmarkToLastEditedCategory(double lat, double lon);

  @PredefinedColors.Color
  private native int nativeGetLastEditedColor();

  private static native void nativeLoadBookmarksFile(@NonNull String path, boolean isTemporaryFile);

  private static native boolean nativeIsAsyncBookmarksLoadingInProgress();

  private static native boolean nativeIsUsedCategoryName(@NonNull String name);

  private static native void nativePrepareForSearch(long catId);

  private static native boolean nativeAreAllCategoriesVisible();

  private static native boolean nativeAreAllCategoriesInvisible();

  private static native void nativeSetAllCategoriesVisibility(boolean visible);

  private static native void nativePrepareFileForSharing(long[] catIds, int kmlFileType);

  private static native void nativePrepareTrackFileForSharing(long trackId, int kmlFileType);

  private static native void nativeSetNotificationsEnabled(boolean enabled);

  private native void nativeGetSortedCategory(long catId, @BookmarkCategory.SortingType int sortingType,
                                              boolean hasMyPosition, double lat, double lon, long timestamp);

  private static native void nativeSetElevationCurrentPositionChangedListener();

  public static native void nativeRemoveElevationCurrentPositionChangedListener();

  private static native void nativeSetElevationActivePoint(long trackId, double distanceInMeters, double latitude,
                                                           double longitude);

  private static native void nativeSetElevationActiveChangedListener();

  public static native void nativeRemoveElevationActiveChangedListener();

  private native long nativeGetCategoryByFileName(@NonNull String fileName);

  private native void nativeMergeCategories(long srcCatId, long dstCatId);

  public interface BookmarksLoadingListener
  {
    default void onBookmarksLoadingStarted() {}
    default void onBookmarksLoadingFinished() {}
    default void onBookmarksFileUnsupported(@NonNull Uri uri) {}
    default void onBookmarksFileDownloadFailed(@NonNull Uri uri, @NonNull String string) {}
    default void onBookmarksFileImportSuccessful() {}
    default void onBookmarksFileImportFailed() {}
  }

  public interface BookmarksSortingListener
  {
    void onBookmarksSortingCompleted(@NonNull SortedBlock[] sortedBlocks, long timestamp);
    default void onBookmarksSortingCancelled(long timestamp){};
  }

  public interface BookmarksSharingListener
  {
    void onPreparedFileForSharing(@NonNull BookmarkSharingResult result);
  }

  public interface OnElevationActivePointChangedListener
  {
    void onElevationActivePointChanged();
  }

  public interface OnElevationCurrentPositionChangedListener
  {
    void onCurrentPositionChanged();
  }

  static class BookmarkCategoriesCache
  {
    @NonNull
    private final List<BookmarkCategory> mCategories = new ArrayList<>();
    @NonNull
    private final List<DataChangedListener> mListeners = new ArrayList<>();

    void update(@NonNull List<BookmarkCategory> categories)
    {
      mCategories.clear();
      mCategories.addAll(categories);
      notifyChanged();
    }

    @NonNull
    public List<BookmarkCategory> getCategories()
    {
      return Collections.unmodifiableList(mCategories);
    }

    public void registerListener(@NonNull DataChangedListener listener)
    {
      if (mListeners.contains(listener))
        throw new IllegalStateException("Observer " + listener + " is already registered.");

      mListeners.add(listener);
    }

    public void unregisterListener(@NonNull DataChangedListener listener)
    {
      int index = mListeners.indexOf(listener);
      if (index == -1)
        throw new IllegalStateException("Observer " + listener + " was not registered.");

      mListeners.remove(index);
    }

    protected void notifyChanged()
    {
      for (DataChangedListener item : mListeners)
        item.onChanged();
    }
  }
}
