#include "platform/virtual_mwm_core.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "base/logging.hpp"

#include <fcntl.h>
#include <unistd.h>
#include <linux/falloc.h>

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
Java_app_organicmaps_wear_VirtualMwmManager_nativePinData(JNIEnv * env, jclass, jstring name, jlong offset, jlong size)
{
  wear::PinData(jni::ToNativeString(env, name), (uint64_t)offset, (size_t)size);
}

JNIEXPORT jboolean JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeInvalidateData(JNIEnv * env, jclass, jstring name, jlong offset, jlong size)
{
  return wear::InvalidateData(jni::ToNativeString(env, name), (uint64_t)offset, (size_t)size)
             ? JNI_TRUE
             : JNI_FALSE;
}

// Frees the physical disk blocks backing [offset, offset+size) of the sparse cache file while
// keeping its logical length (FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE). The region subsequently
// reads back as zeros; combined with nativeInvalidateData the reader will re-fault and re-fetch it.
// Returns false if the filesystem doesn't support hole punching (caller degrades to no eviction).
JNIEXPORT jboolean JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativePunchHole(JNIEnv * env, jclass, jstring path, jlong offset, jlong size)
{
  std::string const nativePath = jni::ToNativeString(env, path);
  int fd = open(nativePath.c_str(), O_RDWR);
  if (fd < 0)
  {
    LOG(LWARNING, ("nativePunchHole: failed to open", nativePath));
    return JNI_FALSE;
  }
  int const rc = fallocate(fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, (off_t)offset, (off_t)size);
  close(fd);
  if (rc != 0)
  {
    LOG(LWARNING, ("nativePunchHole: fallocate failed for", nativePath, "offset", offset, "rc", rc));
    return JNI_FALSE;
  }
  return JNI_TRUE;
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

JNIEXPORT void JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeNotifyUnmounted(JNIEnv * env, jclass, jstring name)
{
  wear::UnregisterVirtualMwm(jni::ToNativeString(env, name));
}
}
