#include "platform/virtual_mwm_core.hpp"
#include "platform/virtual_model_reader.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "coding/files_container.hpp"
#include "coding/reader.hpp"

#include "base/exception.hpp"
#include "base/logging.hpp"

#include "defines.hpp"

#include <fcntl.h>
#include <unistd.h>
#include <linux/falloc.h>
#include <vector>

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

JNIEXPORT void JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeSetStreamingPaused(JNIEnv *, jclass, jboolean paused)
{
  wear::SetStreamingPaused(paused == JNI_TRUE);
}

// Returns the absolute [offset, size, offset, size, ...] byte ranges of the small,
// render-gating sections of the mwm (data header, version, scale/geometry index, feature-offsets
// table). These are tiny compared to geometry but every viewport query and feature lookup hits
// them; fetching them up front lets those reads resolve from the local cache instead of one slow
// Bluetooth round-trip each, so the visible tile paints in one batch rather than dribbling in.
//
// Reading the container TOC faults its (small) blocks via WaitForData, so this MUST be called off
// the UI thread. Returns null if the mwm isn't virtual or the TOC isn't available yet (the caller
// retries later).
JNIEXPORT jlongArray JNICALL
Java_app_organicmaps_wear_VirtualMwmManager_nativeGetCriticalSectionRanges(JNIEnv * env, jclass, jstring name)
{
  std::string const mwmName = jni::ToNativeString(env, name);
  std::string const path = wear::GetVirtualMwmPath(mwmName);
  if (path.empty())
    return nullptr;

  // Order matters — the caller prefetches in this order:
  //  1. Tiny render-gating sections (header/version/scale-index/feature-offsets): needed for ANY
  //     render; fetching them first removes a Bluetooth round-trip per viewport query / feature
  //     lookup (optimization B).
  //  2. Coarsest geometry+triangle buckets (geom0/trg0): the most simplified LOD, smallest geometry
  //     sections. Caching them lets the region draw a coarse overview quickly — filling the gap
  //     between the resident World map (continents) and street detail — before finer geomN stream in
  //     on demand (optimization A, pragmatic variant: no engine change).
  std::vector<std::string> tags = {HEADER_FILE_TAG, VERSION_FILE_TAG, INDEX_FILE_TAG,
                                   FEATURE_OFFSETS_FILE_TAG};
  tags.push_back(std::string(GEOMETRY_FILE_TAG) + '0');
  tags.push_back(std::string(TRIANGLE_FILE_TAG) + '0');

  std::vector<jlong> ranges;
  try
  {
    ModelReaderPtr reader(std::make_unique<VirtualModelReader>(mwmName, path));
    FilesContainerR cont(reader);
    for (auto const & tag : tags)
    {
      if (!cont.IsExist(tag))
        continue;
      auto const r = cont.GetAbsoluteOffsetAndSize(tag);
      ranges.push_back(static_cast<jlong>(r.first));
      ranges.push_back(static_cast<jlong>(r.second));
    }
  }
  catch (RootException const & e)
  {
    LOG(LWARNING, ("nativeGetCriticalSectionRanges failed for", mwmName, ":", e.what()));
    return nullptr;
  }

  jlongArray result = env->NewLongArray(static_cast<jsize>(ranges.size()));
  if (result != nullptr && !ranges.empty())
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(ranges.size()), ranges.data());
  return result;
}
}
