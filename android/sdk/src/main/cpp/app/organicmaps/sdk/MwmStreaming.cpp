#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "storage/storage.hpp"

#include "platform/country_file.hpp"
#include "platform/local_country_file.hpp"
#include "platform/local_country_file_utils.hpp"
#include "platform/country_defines.hpp"
#include "platform/platform.hpp"

#include "coding/file_reader.hpp"

#include "base/file_name_utils.hpp"
#include "base/logging.hpp"

#include <string>
#include <vector>

namespace
{
// The phone framework registers local maps once, at startup (RegisterAllLocalMaps). A map that the
// user downloads while the app keeps running lands on disk but is never added to Storage's in-memory
// list, so GetLatestLocalFile() returns null and the watch is told the map is "not found" — until
// the phone app is restarted. To serve the watch without a restart, fall back to building a
// LocalCountryFile straight from disk. This only reads the file; it does not mutate Storage state.
bool ResolveLocalFile(::Framework * f, std::string const & mwmName, platform::LocalCountryFile & out)
{
  storage::Storage & storage = f->GetStorage();
  if (storage::LocalFilePtr lf = storage.GetLatestLocalFile(mwmName))
  {
    out = *lf;
    return true;
  }

  int64_t const version = storage.GetCurrentDataVersion();
  platform::CountryFile const countryFile(mwmName);

  // Versioned maps directory first (e.g. .../files/251123), then the writable root (older layout).
  std::vector<std::string> const dirs = {
      base::JoinPath(GetPlatform().WritableDir(), std::to_string(version)),
      GetPlatform().WritableDir()};

  for (auto const & dir : dirs)
  {
    platform::LocalCountryFile candidate(dir, countryFile, version);
    candidate.SyncWithDisk();
    if (candidate.OnDisk(MapFileType::Map))
    {
      LOG(LWARNING, ("Serving on-disk map not registered in Storage to watch:", mwmName, "from", dir));
      out = candidate;
      return true;
    }
  }
  return false;
}
}  // namespace

extern "C"
{
JNIEXPORT jbyteArray JNICALL Java_app_organicmaps_sdk_Framework_nativeGetMwmBytes(JNIEnv * env, jclass, jstring name, jlong offset, jint size)
{
  ::Framework * f = frm();
  if (!f)
  {
    LOG(LWARNING, ("Native framework is not initialized."));
    return env->NewByteArray(0);
  }

  std::string mwmName = jni::ToNativeString(env, name);
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
    mwmName = mwmName.substr(0, mwmName.size() - 4);

  platform::LocalCountryFile localFile;
  if (!ResolveLocalFile(f, mwmName, localFile))
  {
    LOG(LWARNING, ("MWM file not found for:", mwmName));
    return env->NewByteArray(0);
  }

  try
  {
    std::unique_ptr<ModelReader> reader = platform::GetCountryReader(localFile, MapFileType::Map);
    uint64_t const fileSize = reader->Size();
    if (offset < 0 || static_cast<uint64_t>(offset) >= fileSize)
    {
      LOG(LWARNING, ("Offset out of bounds for:", mwmName, "offset:", offset, "fileSize:", fileSize));
      return env->NewByteArray(0);
    }

    size_t bytesToRead = static_cast<size_t>(size);
    if (static_cast<uint64_t>(offset) + bytesToRead > fileSize)
      bytesToRead = static_cast<size_t>(fileSize - offset);

    std::vector<uint8_t> buffer(bytesToRead);
    reader->Read(static_cast<uint64_t>(offset), buffer.data(), bytesToRead);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(bytesToRead));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytesToRead), reinterpret_cast<jbyte const *>(buffer.data()));
    return result;
  }
  catch (RootException const & e)
  {
    LOG(LERROR, ("Error reading MWM file:", mwmName, e.what()));
    return env->NewByteArray(0);
  }
}

JNIEXPORT jlong JNICALL Java_app_organicmaps_sdk_Framework_nativeGetMwmSize(JNIEnv * env, jclass, jstring name)
{
  ::Framework * f = frm();
  if (!f) return 0;

  std::string mwmName = jni::ToNativeString(env, name);
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
    mwmName = mwmName.substr(0, mwmName.size() - 4);

  platform::LocalCountryFile localFile;
  if (!ResolveLocalFile(f, mwmName, localFile))
    return 0;

  try
  {
    std::unique_ptr<ModelReader> reader = platform::GetCountryReader(localFile, MapFileType::Map);
    return static_cast<jlong>(reader->Size());
  }
  catch (RootException const & e)
  {
    LOG(LERROR, ("Error getting MWM size:", mwmName, e.what()));
    return 0;
  }
}
}
