#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "storage/storage.hpp"

#include "platform/local_country_file.hpp"
#include "platform/country_defines.hpp"

#include "coding/file_reader.hpp"

#include "base/logging.hpp"

#include <string>
#include <vector>

extern "C"
{
JNIEXPORT jbyteArray Java_app_organicmaps_sdk_Framework_nativeGetMwmBytes(JNIEnv * env, jclass, jstring name, jlong offset, jint size)
{
  ::Framework * f = frm();
  if (!f)
  {
    LOG(LWARNING, ("Native framework is not initialized."));
    return env->NewByteArray(0);
  }

  std::string const mwmName = jni::ToNativeString(env, name);
  storage::Storage & storage = f->GetStorage();
  storage::LocalFilePtr localFile = storage.GetLatestLocalFile(mwmName);

  if (!localFile)
  {
    LOG(LWARNING, ("MWM file not found for:", mwmName));
    return env->NewByteArray(0);
  }

  std::string const path = localFile->GetPath(MapFileType::Map);
  try
  {
    FileReader reader(path);
    uint64_t const fileSize = reader.Size();
    if (offset < 0 || static_cast<uint64_t>(offset) >= fileSize)
    {
      LOG(LWARNING, ("Offset out of bounds for:", mwmName, "offset:", offset, "fileSize:", fileSize));
      return env->NewByteArray(0);
    }

    size_t bytesToRead = static_cast<size_t>(size);
    if (static_cast<uint64_t>(offset) + bytesToRead > fileSize)
      bytesToRead = static_cast<size_t>(fileSize - offset);

    std::vector<uint8_t> buffer(bytesToRead);
    reader.Read(static_cast<uint64_t>(offset), buffer.data(), bytesToRead);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytesToRead));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytesToRead), reinterpret_cast<jbyte const *>(buffer.data()));
    return result;
  }
  catch (Reader::Exception const & e)
  {
    LOG(LERROR, ("Error reading MWM file:", path, e.what()));
    return env->NewByteArray(0);
  }
}

JNIEXPORT jlong Java_app_organicmaps_sdk_Framework_nativeGetMwmSize(JNIEnv * env, jclass, jstring name)
{
  ::Framework * f = frm();
  if (!f) return 0;

  std::string const mwmName = jni::ToNativeString(env, name);
  storage::Storage & storage = f->GetStorage();
  storage::LocalFilePtr localFile = storage.GetLatestLocalFile(mwmName);

  if (!localFile) return 0;

  std::string const path = localFile->GetPath(MapFileType::Map);
  uint64_t size = 0;
  if (Platform::GetFileSizeByFullPath(path, size))
    return static_cast<jlong>(size);

  return 0;
}
}
