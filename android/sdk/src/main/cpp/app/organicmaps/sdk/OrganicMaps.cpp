#include "app/organicmaps/sdk/Framework.hpp"

#include "app/organicmaps/sdk/platform/AndroidPlatform.hpp"

#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "drape_frontend/visual_params.hpp"

extern "C"
{
// static void nativeSetSettingsDir(String settingsPath);
JNIEXPORT void Java_app_organicmaps_sdk_OrganicMaps_nativeSetSettingsDir(JNIEnv * env, jclass clazz,
                                                                         jstring settingsPath)
{
  android::Platform::Instance().SetSettingsDir(jni::ToNativeString(env, settingsPath));
}

// static void nativeInitPlatform(Context context, String apkPath, String storagePath, String privatePath, String
// tmpPath, String flavorName, String buildType, boolean isTablet);
JNIEXPORT void Java_app_organicmaps_sdk_OrganicMaps_nativeInitPlatform(JNIEnv * env, jclass clazz, jobject context,
                                                                       jstring apkPath, jstring writablePath,
                                                                       jstring privatePath, jstring tmpPath,
                                                                       jstring flavorName, jstring buildType,
                                                                       jboolean isTablet)
{
  android::Platform::Instance().Initialize(env, context, apkPath, writablePath, privatePath, tmpPath, flavorName,
                                           buildType, isTablet);
}

// static void nativeInitFramework(@NonNull Runnable onComplete);
JNIEXPORT void Java_app_organicmaps_sdk_OrganicMaps_nativeInitFramework(JNIEnv * env, jclass clazz, jobject onComplete)
{
  if (!df::VisualParams::Instance().IsInited())
  {
    df::VisualParams::Init(1.0, 512);
  }

  if (!g_framework)
  {
    g_framework.Assign(new android::Framework([onComplete = jni::make_global_ref_safe(onComplete)]()
    {
      GetPlatform().RunTask(Platform::Thread::Gui, [onComplete]()
      {
        JNIEnv * env = jni::GetEnv();
        if (env == nullptr)
          return;
        jmethodID const methodId = jni::GetMethodID(env, *onComplete, "run", "()V");
        env->CallVoidMethod(*onComplete, methodId);
      });
    }));
  }
}

// static void nativeAddLocalization(String name, String value);
JNIEXPORT void Java_app_organicmaps_sdk_OrganicMaps_nativeAddLocalization(JNIEnv * env, jclass clazz, jstring name,
                                                                          jstring value)
{
  if (g_framework)
    g_framework->AddString(jni::ToNativeString(env, name), jni::ToNativeString(env, value));
}

JNIEXPORT void Java_app_organicmaps_sdk_OrganicMaps_nativeOnTransit(JNIEnv *, jclass, jboolean foreground)
{
  if (!g_framework)
    return;

  if (static_cast<bool>(foreground))
    g_framework->NativeFramework()->EnterForeground();
  else
    g_framework->NativeFramework()->EnterBackground();
}
}
