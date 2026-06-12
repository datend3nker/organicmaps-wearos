package app.organicmaps.bookmarks;

import static app.organicmaps.bookmarks.Holders.CategoryViewHolder;
import static app.organicmaps.bookmarks.Holders.HeaderViewHolder;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import app.organicmaps.R;
import app.organicmaps.adapter.OnItemClickListener;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BookmarkCategoriesAdapter extends BaseBookmarkCategoryAdapter<RecyclerView.ViewHolder>
{
  private final static int HEADER_COUNT = 1;
  private final static int HEADER_POSITION = 0;
  private final static int TYPE_ACTION_HEADER = 0;
  private final static int TYPE_CATEGORY_ITEM = 1;
  private final static int TYPE_ACTION_ADD = 2;
  private final static int TYPE_ACTION_IMPORT = 3;
  private final static int TYPE_ACTION_EXPORT_ALL_AS_KMZ = 4;
  private final static int TYPE_ACTION_SYNC_WATCH = 5;

  private final static long ID_HEADER = -1;
  private final static long ID_ACTION_ADD = -2;
  private final static long ID_ACTION_IMPORT = -3;
  private final static long ID_ACTION_EXPORT_ALL_AS_KMZ = -4;
  private final static long ID_ACTION_SYNC_WATCH = -5;

  private final List<Integer> mFooterTypes = new ArrayList<>(Arrays.asList(
      TYPE_ACTION_ADD,
      TYPE_ACTION_IMPORT,
      TYPE_ACTION_EXPORT_ALL_AS_KMZ,
      TYPE_ACTION_SYNC_WATCH
  ));

  @Nullable
  private OnItemLongClickListener<BookmarkCategory> mLongClickListener;
  @Nullable
  private OnItemClickListener<BookmarkCategory> mClickListener;
  @Nullable
  private OnItemMoreClickListener<BookmarkCategory> mMoreClickListener;
  @Nullable
  private CategoryListCallback mCategoryListCallback;
  @NonNull
  private final MassOperationAction mMassOperationAction = new MassOperationAction();

  BookmarkCategoriesAdapter(@NonNull Context context, @NonNull List<BookmarkCategory> categories)
  {
    super(context.getApplicationContext(), categories);
    setHasStableIds(true);
  }

  public void setOnClickListener(@Nullable OnItemClickListener<BookmarkCategory> listener)
  {
    mClickListener = listener;
  }

  public void setOnMoreClickListener(@Nullable OnItemMoreClickListener<BookmarkCategory> listener)
  {
    mMoreClickListener = listener;
  }

  void setOnLongClickListener(@Nullable OnItemLongClickListener<BookmarkCategory> listener)
  {
    mLongClickListener = listener;
  }

  void setCategoryListCallback(@Nullable CategoryListCallback listener)
  {
    mCategoryListCallback = listener;
  }

  @Override
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
  {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    if (viewType == TYPE_ACTION_HEADER)
    {
      View header = inflater.inflate(R.layout.item_bookmark_group_list_header, parent, false);
      return new HeaderViewHolder(header);
    }
    if (viewType == TYPE_CATEGORY_ITEM)
    {
      View view = inflater.inflate(R.layout.item_bookmark_category, parent, false);
      final CategoryViewHolder holder = new CategoryViewHolder(view);
      view.setOnClickListener(new CategoryItemClickListener(holder));
      view.setOnLongClickListener(new LongClickListener(holder));
      return holder;
    }
    if (mFooterTypes.contains(viewType))
    {
      View item = inflater.inflate(R.layout.item_bookmark_button, parent, false);
      if (viewType == TYPE_ACTION_ADD)
        item.setOnClickListener(v -> {
          if (mCategoryListCallback != null)
            mCategoryListCallback.onAddButtonClick();
        });
      else if (viewType == TYPE_ACTION_IMPORT)
        item.setOnClickListener(v -> {
          if (mCategoryListCallback != null)
            mCategoryListCallback.onImportButtonClick();
        });
      else if (viewType == TYPE_ACTION_EXPORT_ALL_AS_KMZ)
        item.setOnClickListener(v -> {
          if (mCategoryListCallback != null)
            mCategoryListCallback.onExportButtonClick();
        });
      else if (viewType == TYPE_ACTION_SYNC_WATCH)
        item.setOnClickListener(v -> app.organicmaps.wear.WearSyncService.syncBookmarksNow());

      return new Holders.GeneralViewHolder(item);
    }
    throw new AssertionError("Invalid item type: " + viewType);
  }

  @Override
  public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position)
  {
    int type = getItemViewType(position);
    switch (type)
    {
    case TYPE_ACTION_HEADER ->
    {
      HeaderViewHolder headerViewHolder = (HeaderViewHolder) holder;
      headerViewHolder.setAction(mMassOperationAction, BookmarkManager.INSTANCE.areAllCategoriesInvisible());
      headerViewHolder.getText().setText(R.string.bookmark_lists);
    }
    case TYPE_CATEGORY_ITEM ->
    {
      final BookmarkCategory category = getCategoryByPosition(toCategoryPosition(position));
      CategoryViewHolder categoryHolder = (CategoryViewHolder) holder;
      categoryHolder.setEntity(category);
      categoryHolder.setName(category.getName());
      categoryHolder.setSize();
      categoryHolder.setVisibilityState(category.isVisible());
      ToggleVisibilityClickListener visibilityListener = new ToggleVisibilityClickListener(categoryHolder);
      categoryHolder.setVisibilityListener(visibilityListener);
      CategoryItemMoreClickListener moreClickListener = new CategoryItemMoreClickListener(categoryHolder);
      categoryHolder.setMoreButtonClickListener(moreClickListener);
    }
    case TYPE_ACTION_ADD ->
    {
      Holders.GeneralViewHolder generalViewHolder = (Holders.GeneralViewHolder) holder;
      generalViewHolder.getImage().setImageResource(R.drawable.ic_add_list);
      generalViewHolder.getText().setText(R.string.bookmarks_create_new_group);
    }
    case TYPE_ACTION_IMPORT ->
    {
      Holders.GeneralViewHolder generalViewHolder = (Holders.GeneralViewHolder) holder;
      generalViewHolder.getImage().setImageResource(R.drawable.ic_import);
      generalViewHolder.getText().setText(R.string.bookmarks_import);
    }
    case TYPE_ACTION_EXPORT_ALL_AS_KMZ ->
    {
      Holders.GeneralViewHolder generalViewHolder = (Holders.GeneralViewHolder) holder;
      generalViewHolder.getImage().setImageResource(R.drawable.ic_export);
      generalViewHolder.getText().setText(R.string.bookmarks_export);
    }
    case TYPE_ACTION_SYNC_WATCH ->
    {
      Holders.GeneralViewHolder generalViewHolder = (Holders.GeneralViewHolder) holder;
      generalViewHolder.getImage().setImageResource(R.drawable.ic_settings);
      generalViewHolder.getText().setText(R.string.bookmarks_sync_with_watch);
    }
    default -> throw new AssertionError("Invalid item type: " + type);
    }
  }

  @Override
  public int getItemViewType(int position)
  {
    if (position < HEADER_COUNT)
      return TYPE_ACTION_HEADER;

    int categoryCount = getBookmarkCategories().size();
    if (position < HEADER_COUNT + categoryCount)
      return TYPE_CATEGORY_ITEM;

    int footerPosition = position - (HEADER_COUNT + categoryCount);
    if (footerPosition < mFooterTypes.size())
      return mFooterTypes.get(footerPosition);

    throw new AssertionError("Invalid position: " + position);
  }

  @Override
  public long getItemId(int position)
  {
    int type = getItemViewType(position);
    return switch (type)
    {
      case TYPE_ACTION_HEADER -> ID_HEADER;
      case TYPE_CATEGORY_ITEM -> getCategoryByPosition(toCategoryPosition(position)).getId();
      case TYPE_ACTION_ADD -> ID_ACTION_ADD;
      case TYPE_ACTION_IMPORT -> ID_ACTION_IMPORT;
      case TYPE_ACTION_EXPORT_ALL_AS_KMZ -> ID_ACTION_EXPORT_ALL_AS_KMZ;
      case TYPE_ACTION_SYNC_WATCH -> ID_ACTION_SYNC_WATCH;
      default -> throw new AssertionError("Invalid item type: " + type);
    };
  }

  private int toCategoryPosition(int adapterPosition)
  {
    return adapterPosition - HEADER_COUNT;
  }

  @Override
  public int getItemCount()
  {
    int categoryCount = getBookmarkCategories().size();
    if (categoryCount == 0)
      return 0;
    return HEADER_COUNT + categoryCount + mFooterTypes.size();
  }

  private class LongClickListener implements View.OnLongClickListener
  {
    @NonNull
    private final CategoryViewHolder mHolder;

    LongClickListener(@NonNull CategoryViewHolder holder)
    {
      mHolder = holder;
    }

    @Override
    public boolean onLongClick(View view)
    {
      if (mLongClickListener != null)
      {
        mLongClickListener.onItemLongClick(view, mHolder.getEntity());
      }
      return true;
    }
  }

  private class MassOperationAction implements HeaderViewHolder.HeaderAction
  {
    @Override
    public void onHideAll()
    {
      BookmarkManager.INSTANCE.setAllCategoriesVisibility(false);
      notifyDataSetChanged();
    }

    @Override
    public void onShowAll()
    {
      BookmarkManager.INSTANCE.setAllCategoriesVisibility(true);
      notifyDataSetChanged();
    }
  }

  private class CategoryItemMoreClickListener implements View.OnClickListener
  {
    @NonNull
    private final CategoryViewHolder mHolder;

    CategoryItemMoreClickListener(@NonNull CategoryViewHolder holder)
    {
      mHolder = holder;
    }

    @Override
    public void onClick(View v)
    {
      if (mMoreClickListener != null)
        mMoreClickListener.onItemMoreClick(v, mHolder.getEntity());
    }
  }

  private class CategoryItemClickListener implements View.OnClickListener
  {
    @NonNull
    private final CategoryViewHolder mHolder;

    CategoryItemClickListener(@NonNull CategoryViewHolder holder)
    {
      mHolder = holder;
    }

    @Override
    public void onClick(View v)
    {
      if (mClickListener != null)
        mClickListener.onItemClick(v, mHolder.getEntity());
    }
  }

  private class ToggleVisibilityClickListener implements View.OnClickListener
  {
    @NonNull
    private final CategoryViewHolder mHolder;

    ToggleVisibilityClickListener(@NonNull CategoryViewHolder holder)
    {
      mHolder = holder;
    }

    @Override
    public void onClick(View v)
    {
      mHolder.getEntity().toggleVisibility();
      notifyItemChanged(mHolder.getBindingAdapterPosition());
      notifyItemChanged(HEADER_POSITION);
    }
  }
}
