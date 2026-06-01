#include "platform/virtual_mwm_core.hpp"
#include "base/logging.hpp"

#include <mutex>
#include <condition_variable>
#include <map>
#include <chrono>
#include <vector>

namespace
{
constexpr uint64_t kChunkSize = 64 * 1024;

struct MwmWaitInfo
{
  std::mutex m_mutex;
  std::condition_variable m_cv;
  std::vector<uint64_t> m_availableChunks;
  uint64_t m_totalSize = 0;
  std::string m_path;

  void MarkAvailable(uint64_t offset, size_t size)
  {
    uint64_t startChunk = offset / kChunkSize;
    uint64_t endChunk = (offset + size - 1) / kChunkSize;
    for (uint64_t i = startChunk; i <= endChunk; ++i)
    {
      uint64_t wordIdx = i / 64;
      uint64_t bitIdx = i % 64;
      if (wordIdx < m_availableChunks.size())
        m_availableChunks[wordIdx] |= (1ULL << bitIdx);
    }
  }

  bool IsAvailable(uint64_t offset, size_t size) const
  {
    if (m_totalSize == 0) return false;
    uint64_t startChunk = offset / kChunkSize;
    uint64_t endChunk = (offset + size - 1) / kChunkSize;
    for (uint64_t i = startChunk; i <= endChunk; ++i)
    {
      uint64_t wordIdx = i / 64;
      uint64_t bitIdx = i % 64;
      if (wordIdx >= m_availableChunks.size() || !(m_availableChunks[wordIdx] & (1ULL << bitIdx)))
        return false;
    }
    return true;
  }
};

std::map<std::string, std::unique_ptr<MwmWaitInfo>> g_waitInfos;
std::mutex g_waitInfosMutex;
wear::TRequestDataFn g_requestDataHandler;

MwmWaitInfo & GetWaitInfo(std::string const & mwmName)
{
  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  auto it = g_waitInfos.find(mwmName);
  if (it == g_waitInfos.end())
  {
    auto info = std::make_unique<MwmWaitInfo>();
    auto & ref = *info;
    g_waitInfos[mwmName] = std::move(info);
    return ref;
  }
  return *(it->second);
}
} // namespace

namespace wear
{
void WaitForData(std::string const & mwmName, uint64_t offset, size_t size)
{
  LOG(LDEBUG, ("WaitForData:", mwmName, "offset:", offset, "size:", size));
  if (g_requestDataHandler)
      g_requestDataHandler(mwmName, offset, size);

  MwmWaitInfo & info = GetWaitInfo(mwmName);
  std::unique_lock<std::mutex> lock(info.m_mutex);

  if (info.m_cv.wait_for(lock, std::chrono::seconds(5), [&]{ return info.IsAvailable(offset, size); }))
  {
     LOG(LDEBUG, ("Data arrived for Virtual MWM:", mwmName, "offset:", offset));
  }
  else
  {
    LOG(LWARNING, ("Timeout waiting for Virtual MWM data:", mwmName, "offset:", offset));
  }
}

bool IsDataAvailable(std::string const & mwmName, uint64_t offset, size_t size)
{
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info.m_mutex);
  return info.IsAvailable(offset, size);
}

void SignalData(std::string const & mwmName, uint64_t offset, size_t size)
{
  LOG(LDEBUG, ("SignalData:", mwmName, "offset:", offset, "size:", size));
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  {
    std::lock_guard<std::mutex> lock(info.m_mutex);
    info.MarkAvailable(offset, size);
  }
  info.m_cv.notify_all();
}

void RegisterVirtualMwm(std::string const & mwmName, std::string const & path, uint64_t totalSize)
{
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info.m_mutex);
  info.m_path = path;
  info.m_totalSize = totalSize;
  uint64_t numChunks = (totalSize + kChunkSize - 1) / kChunkSize;
  info.m_availableChunks.assign((numChunks + 63) / 64, 0);
  LOG(LINFO, ("Registered virtual MWM:", mwmName, "path:", path, "size:", totalSize));
}

std::string GetVirtualMwmPath(std::string const & mwmName)
{
  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  auto it = g_waitInfos.find(mwmName);
  if (it != g_waitInfos.end()) return it->second->m_path;

  // Try with .mwm extension if not found
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
  {
      auto it2 = g_waitInfos.find(mwmName.substr(0, mwmName.size() - 4));
      if (it2 != g_waitInfos.end()) return it2->second->m_path;
  }

  return "";
}

void SetRequestDataHandler(TRequestDataFn fn)
{
    g_requestDataHandler = std::move(fn);
}
} // namespace wear
