#include "platform/virtual_mwm_core.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "base/logging.hpp"

namespace
{
void RequestDataFromJava(std::string const & mwmName, uint64_t offset, size_t size)
{
  JNIEnv * env = jni::GetEnv();
  if (!env) return;

  jni::TScopedLocalClassRef const managerClass(env, env->FindClass("app/organicmaps/wear/VirtualMwmManager"));
  if (!managerClass)
  {
      LOG(LERROR, ("Could not find VirtualMwmManager class"));
      return;
  }
  jmethodID const methodId = jni::GetStaticMethodID(env, managerClass.get(), "onDataRequired", "(Ljava/lang/String;JI)V");

  jni::TScopedLocalRef const name(env, jni::ToJavaString(env, mwmName));
  env->CallStaticVoidMethod(managerClass.get(), methodId, name.get(), (jlong)offset, (jint)size);
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
Java_app_organicmaps_wear_VirtualMwmManager_nativeNotifyMounted(JNIEnv * env, jclass, jstring name, jstring path, jlong totalSize)
{
  // Initialize the core with our JNI-based request handler.
  static bool handlerSet = false;
  if (!handlerSet)
  {
      wear::SetRequestDataHandler(&RequestDataFromJava);
      handlerSet = true;
  }
  wear::RegisterVirtualMwm(jni::ToNativeString(env, name), jni::ToNativeString(env, path), (uint64_t)totalSize);
}
}
