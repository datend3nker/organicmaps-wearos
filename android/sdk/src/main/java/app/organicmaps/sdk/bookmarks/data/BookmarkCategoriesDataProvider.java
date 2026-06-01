package app.organicmaps.sdk.bookmarks.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.List;

public interface BookmarkCategoriesDataProvider
{
  @NonNull
  List<BookmarkCategory> getCategories();
  int getCategoriesCount();
  @NonNull
  List<BookmarkCategory> getChildrenCategories(long parentId);
  @Nullable
  BookmarkCategory getCategoryById(long categoryId);
}
