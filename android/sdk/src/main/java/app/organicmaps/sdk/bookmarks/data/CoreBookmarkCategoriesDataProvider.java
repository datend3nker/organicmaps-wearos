package app.organicmaps.sdk.bookmarks.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

class CoreBookmarkCategoriesDataProvider implements BookmarkCategoriesDataProvider
{
  @Nullable
  @Override
  public BookmarkCategory getCategoryById(long categoryId)
  {
    return BookmarkManager.INSTANCE.nativeGetBookmarkCategory(categoryId);
  }

  @NonNull
  @Override
  public List<BookmarkCategory> getCategories()
  {
    BookmarkCategory[] categories = BookmarkManager.INSTANCE.nativeGetBookmarkCategories();
    return Arrays.asList(categories);
  }

  @Override
  public int getCategoriesCount()
  {
    return BookmarkManager.INSTANCE.nativeGetBookmarkCategoriesCount();
  }

  @NonNull
  @Override
  public List<BookmarkCategory> getChildrenCategories(long parentId)
  {
    BookmarkCategory[] categories = BookmarkManager.INSTANCE.nativeGetChildrenCategories(parentId);
    return Arrays.asList(categories);
  }
}
