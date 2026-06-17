package app.organicmaps.bookmarks;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import app.organicmaps.R;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.BookmarkSharingResult;
import app.organicmaps.sdk.bookmarks.data.KmlFileType;
import app.organicmaps.sdk.util.log.Logger;
import app.organicmaps.util.SharingUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum BookmarksSharingHelper
{
  INSTANCE;

  private static final String TAG = BookmarksSharingHelper.class.getSimpleName();

  @Nullable
  private AlertDialog mProgressDialog;

  @Nullable
  private long[] mRequestedCategoryIds;

  public void prepareBookmarkCategoryForSharing(@NonNull Activity context, long catId, KmlFileType kmlFileType)
  {
    mRequestedCategoryIds = new long[] {catId};
    showProgressDialog(context);
    BookmarkManager.INSTANCE.prepareCategoriesForSharing(new long[] {catId}, kmlFileType);
  }

  public void prepareTrackForSharing(@NonNull Activity context, long trackId, KmlFileType kmlFileType)
  {
    mRequestedCategoryIds = new long[] {-2}; // Special marker for track sharing
    showProgressDialog(context);
    BookmarkManager.INSTANCE.prepareTrackForSharing(trackId, kmlFileType);
  }

  private void showProgressDialog(@NonNull Activity context)
  {
    final View view = LayoutInflater.from(context).inflate(R.layout.dialog_progress, null);
    final TextView tvMessage = view.findViewById(R.id.message);
    tvMessage.setText(R.string.please_wait);

    mProgressDialog = new MaterialAlertDialogBuilder(context, R.style.MwmTheme_AlertDialog)
        .setView(view)
        .setCancelable(false)
        .show();
  }

  public void onPreparedFileForSharing(@NonNull FragmentActivity context,
                                       @NonNull ActivityResultLauncher<SharingUtils.SharingIntent> launcher,
                                       @NonNull BookmarkSharingResult result)
  {
    boolean isExpected = false;
    long[] resultIds = result.getCategoriesIds();
    
    if (mRequestedCategoryIds != null)
    {
      if (mRequestedCategoryIds.length == 1 && mRequestedCategoryIds[0] == -2)
      {
        // Waiting for track, resultIds for tracks might be empty or null
        isExpected = (resultIds == null || resultIds.length == 0);
      }
      else
      {
        isExpected = Arrays.equals(resultIds, mRequestedCategoryIds);
      }
    }

    if (!isExpected)
    {
      Logger.d(TAG, "Ignoring prepared file for sharing (likely background sync). resultIds=" + Arrays.toString(resultIds) + ", requestedIds=" + Arrays.toString(mRequestedCategoryIds));
      return;
    }

    if (mProgressDialog != null && mProgressDialog.isShowing())
      mProgressDialog.dismiss();
    mProgressDialog = null;
    mRequestedCategoryIds = null;

    switch (result.getCode())
    {
    case BookmarkSharingResult.SUCCESS ->
      SharingUtils.shareBookmarkFile(context, launcher, result.getSharingPath(), result.getMimeType());
    case BookmarkSharingResult.EMPTY_CATEGORY ->
      new MaterialAlertDialogBuilder(context, R.style.MwmTheme_AlertDialog)
          .setTitle(R.string.bookmarks_error_title_share_empty)
          .setMessage(R.string.bookmarks_error_message_share_empty)
          .setPositiveButton(R.string.ok, null)
          .show();
    case BookmarkSharingResult.ARCHIVE_ERROR, BookmarkSharingResult.FILE_ERROR ->
    {
      new MaterialAlertDialogBuilder(context, R.style.MwmTheme_AlertDialog)
          .setTitle(R.string.dialog_routing_system_error)
          .setMessage(R.string.bookmarks_error_message_share_general)
          .setPositiveButton(R.string.ok, null)
          .show();
      List<String> names = new ArrayList<>();
      for (long categoryId : result.getCategoriesIds())
        names.add(BookmarkManager.INSTANCE.getCategoryById(categoryId).getName());
      Logger.e(TAG, "Failed to share bookmark categories " + names + ", error code: " + result.getCode());
    }
    default -> throw new AssertionError("Unsupported bookmark sharing code: " + result.getCode());
    }
  }

  public void prepareBookmarkCategoriesForSharing(@NonNull Activity context)
  {
    showProgressDialog(context);
    List<BookmarkCategory> categories = BookmarkManager.INSTANCE.getCategories();
    long[] categoryIds = new long[categories.size()];
    for (int i = 0; i < categories.size(); i++)
      categoryIds[i] = categories.get(i).getId();
    mRequestedCategoryIds = categoryIds;
    BookmarkManager.INSTANCE.prepareCategoriesForSharing(categoryIds, KmlFileType.Text);
  }
}
