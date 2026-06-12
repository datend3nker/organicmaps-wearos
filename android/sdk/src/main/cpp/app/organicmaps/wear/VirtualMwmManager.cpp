#include "platform/virtual_mwm_core.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "base/logging.hpp"

namespace
{
jclass g_managerClass = nullptr;
jmethodID g_onDataRequiredMethod = nullptr;

void RequestDataFromJava(std::string const & mwmName, uint64_t offset, size_t size)
{
  JNIEnv * env = jni::GetEnv();
  if (env == nullptr || g_managerClass == nullptr || g_onDataRequiredMethod == nullptr)
    return;

  jni::TScopedLocalRef const name(env, jni::ToJavaString(env, mwmName));
  env->CallStaticVoidMethod(g_managerClass, g_onDataRequiredMethod, name.get(), (jlong)offset, (jint)size);
  jni::HandleJavaException(env);
}
} // namespace

extern "C"
{
JNIEXPORT void JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeDataArrived(JNIEnv * env, jclass, jstring name, jlong offset, jint size)
{
  wear::SignalData(jni::ToNativeString(env, name), (uint64_t)offset, (size_t)size);
}

JNIEXPORT void JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeNotifyMounted(JNIEnv * env, jclass clazz, jstring name, jstring path, jlong totalSize)
{
  // Initialize the core with our JNI-based request handler.
  static bool handlerSet = false;
  if (!handlerSet)
  {
      g_managerClass = (jclass)env->NewGlobalRef(clazz);
      g_onDataRequiredMethod = jni::GetStaticMethodID(env, g_managerClass, "onDataRequired", "(Ljava/lang/String;JI)V");
      wear::SetRequestDataHandler(&RequestDataFromJava);
      handlerSet = true;
  }
  wear::RegisterVirtualMwm(jni::ToNativeString(env, name), jni::ToNativeString(env, path), (uint64_t)totalSize);
}
}
