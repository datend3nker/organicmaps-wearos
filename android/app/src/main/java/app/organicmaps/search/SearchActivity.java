package app.organicmaps.search;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.organicmaps.base.BaseMwmFragmentActivity;

public class SearchActivity extends BaseMwmFragmentActivity
{
  public static final String EXTRA_QUERY = "search_query";
  public static final String EXTRA_LOCALE = "locale";
  public static final String EXTRA_SEARCH_ON_MAP = "search_on_map";

  public static void start(@NonNull Activity activity, @Nullable String query)
  {
    start(activity, query, null /* locale */, false /* isSearchOnMap */);
  }

  public static void start(@NonNull android.content.Context context, @Nullable String query)
  {
    final Intent i = new Intent(context, SearchActivity.class);
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    i.putExtra(EXTRA_QUERY, query);
    context.startActivity(i);
  }

  public static void start(@NonNull Activity activity, @Nullable String query, @Nullable String locale,
                           boolean isSearchOnMap)
  {
    final Intent i = new Intent(activity, SearchActivity.class);
    Bundle args = new Bundle();
    args.putString(EXTRA_QUERY, query);
    args.putString(EXTRA_LOCALE, locale);
    args.putBoolean(EXTRA_SEARCH_ON_MAP, isSearchOnMap);
    i.putExtras(args);
    activity.startActivity(i);
  }

  @Override
  protected void onNewIntent(Intent intent)
  {
    super.onNewIntent(intent);
    setIntent(intent);
    String query = intent.getStringExtra(EXTRA_QUERY);
    if (query != null)
    {
      SearchFragment fragment = (SearchFragment) getSupportFragmentManager().findFragmentByTag(getFragmentClass().getName());
      if (fragment != null)
        fragment.setQuery(query, false);
    }
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return SearchFragment.class;
  }
}
