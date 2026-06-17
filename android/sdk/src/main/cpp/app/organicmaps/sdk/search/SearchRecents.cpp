#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "app/organicmaps/sdk/core/jni_java_methods.hpp"

#include "search/result.hpp"

using SearchRequest = search::QuerySaver::SearchRequest;

extern "C"
{
void Java_app_organicmaps_sdk_search_SearchRecents_nativeGetList(JNIEnv * env, jclass, jobject result)
{
  if (!g_framework)
    return;

  auto const & items = g_framework->NativeFramework()->GetSearchAPI().GetLastSearchQueries();
  if (items.empty())
    return;

  auto const listAddMethod = jni::ListBuilder::Instance(env).m_add;

  for (SearchRequest const & item : items)
  {
    jni::ScopedLocalRef str(env, jni::ToJavaString(env, item.second));
    env->CallBooleanMethod(result, listAddMethod, str.get());
  }
}

void Java_app_organicmaps_sdk_search_SearchRecents_nativeAdd(JNIEnv * env, jclass, jstring locale,
                                                                       jstring query)
{
  if (!g_framework)
    return;

  SearchRequest const sr(jni::ToNativeString(env, locale), jni::ToNativeString(env, query));
  g_framework->NativeFramework()->GetSearchAPI().SaveSearchQuery(sr);
}

void Java_app_organicmaps_sdk_search_SearchRecents_nativeClear(JNIEnv * env, jclass)
{
  if (!g_framework)
    return;

  g_framework->NativeFramework()->GetSearchAPI().ClearSearchHistory();
}
}
