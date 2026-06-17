#include "platform/virtual_mwm_core.hpp"
#include "base/logging.hpp"
#include "base/file_name_utils.hpp"

#include <mutex>
#include <condition_variable>
#include <map>
#include <chrono>
#include <vector>
#include <thread>
#include <atomic>

namespace
{
constexpr uint64_t kChunkSize = 1024;

struct MwmWaitInfo
{
  std::mutex m_mutex;
  std::condition_variable m_cv;
  std::vector<uint64_t> m_availableChunks;
  std::vector<uint64_t> m_pinnedChunks;
  uint64_t m_totalSize = 0;
  std::string m_path;

  void MarkPinned(uint64_t offset, size_t size)
  {
    uint64_t const end = offset + size;
    uint64_t startChunk = offset / kChunkSize;
    uint64_t endChunk = (end + kChunkSize - 1) / kChunkSize;

    if (m_totalSize > 0 && end > m_totalSize)
      endChunk = (m_totalSize + kChunkSize - 1) / kChunkSize;

    for (uint64_t i = startChunk; i < endChunk; ++i)
    {
      uint64_t wordIdx = i / 64;
      uint64_t bitIdx = i % 64;
      if (wordIdx < m_pinnedChunks.size())
        m_pinnedChunks[wordIdx] |= (1ULL << bitIdx);
    }
  }

  bool IsPinnedChunk(uint64_t chunk) const
  {
    uint64_t wordIdx = chunk / 64;
    uint64_t bitIdx = chunk % 64;
    return wordIdx < m_pinnedChunks.size() && (m_pinnedChunks[wordIdx] & (1ULL << bitIdx));
  }

  // Clears available bits for [offset, offset+size) except for pinned chunks.
  // Returns true iff every chunk in the range was cleared (none pinned).
  bool ClearAvailableUnpinned(uint64_t offset, size_t size)
  {
    uint64_t const end = offset + size;
    uint64_t startChunk = offset / kChunkSize;
    uint64_t endChunk = (end + kChunkSize - 1) / kChunkSize;

    if (m_totalSize > 0 && end > m_totalSize)
      endChunk = (m_totalSize + kChunkSize - 1) / kChunkSize;

    bool clearedAll = true;
    for (uint64_t i = startChunk; i < endChunk; ++i)
    {
      if (IsPinnedChunk(i))
      {
        clearedAll = false;
        continue;
      }
      uint64_t wordIdx = i / 64;
      uint64_t bitIdx = i % 64;
      if (wordIdx < m_availableChunks.size())
        m_availableChunks[wordIdx] &= ~(1ULL << bitIdx);
    }
    return clearedAll;
  }

  void MarkAvailable(uint64_t offset, size_t size)
  {
    uint64_t const end = offset + size;
    uint64_t startChunk = offset / kChunkSize;
    uint64_t endChunk = (end + kChunkSize - 1) / kChunkSize;

    if (m_totalSize > 0 && end > m_totalSize)
      endChunk = (m_totalSize + kChunkSize - 1) / kChunkSize;

    for (uint64_t i = startChunk; i < endChunk; ++i)
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
std::vector<std::string> g_allVirtualMwms;
std::mutex g_waitInfosMutex;
wear::TRequestDataFn g_requestDataHandler;
std::thread::id g_uiThreadId;
std::atomic<bool> g_uiThreadIdSet{false};

MwmWaitInfo & GetWaitInfo(std::string const & mwmNameWithExt)
{
  std::string mwmName = mwmNameWithExt;
  base::GetNameFromFullPath(mwmName);
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
    mwmName = mwmName.substr(0, mwmName.size() - 4);

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
bool WaitForData(std::string const & mwmNameWithExt, uint64_t offset, size_t size)
{
  std::string mwmName = mwmNameWithExt;
  base::GetNameFromFullPath(mwmName);
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
    mwmName = mwmName.substr(0, mwmName.size() - 4);

  LOG(LDEBUG, ("WaitForData:", mwmName, "offset:", offset, "size:", size));

  MwmWaitInfo & info = GetWaitInfo(mwmName);

  // Never block the UI/main thread on a network round-trip to the phone: it would freeze input and
  // ANR. If the data isn't already cached, request it and fail fast — the read is skipped and will
  // re-fault once the bytes arrive (same path as a normal timeout, just instant).
  if (g_uiThreadIdSet.load(std::memory_order_relaxed) && std::this_thread::get_id() == g_uiThreadId)
  {
    {
      std::lock_guard<std::mutex> lock(info.m_mutex);
      if (info.IsAvailable(offset, size))
        return true;
    }
    if (g_requestDataHandler)
      g_requestDataHandler(mwmName, offset, size);
    LOG(LDEBUG, ("WaitForData on UI thread, not blocking:", mwmName, "offset:", offset));
    return false;
  }

  for (int attempt = 1; attempt <= 4; ++attempt)
  {
    {
      std::lock_guard<std::mutex> lock(info.m_mutex);
      if (info.IsAvailable(offset, size))
        return true;
    }

    if (g_requestDataHandler)
      g_requestDataHandler(mwmName, offset, size);

    std::unique_lock<std::mutex> lock(info.m_mutex);
    if (info.m_cv.wait_for(lock, std::chrono::seconds(5), [&]{ return info.IsAvailable(offset, size); }))
    {
      LOG(LDEBUG, ("Data arrived for Virtual MWM:", mwmName, "offset:", offset, "attempt:", attempt));
      return true;
    }

    LOG(LWARNING, ("Timeout waiting for Virtual MWM data:", mwmName, "offset:", offset, "attempt:", attempt));
  }

  LOG(LERROR, ("Final timeout waiting for Virtual MWM data:", mwmName, "offset:", offset));
  return false;
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

void PinData(std::string const & mwmName, uint64_t offset, size_t size)
{
  LOG(LDEBUG, ("PinData:", mwmName, "offset:", offset, "size:", size));
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info.m_mutex);
  info.MarkPinned(offset, size);
}

bool InvalidateData(std::string const & mwmName, uint64_t offset, size_t size)
{
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info.m_mutex);
  return info.ClearAvailableUnpinned(offset, size);
}

void RegisterVirtualMwm(std::string const & mwmName, std::string const & path, uint64_t totalSize)
{
  MwmWaitInfo & info = GetWaitInfo(mwmName);
  {
    std::lock_guard<std::mutex> lock(info.m_mutex);
    info.m_path = path;
    info.m_totalSize = totalSize;
    uint64_t numChunks = (totalSize + kChunkSize - 1) / kChunkSize;
    info.m_availableChunks.assign((numChunks + 63) / 64, 0);
    info.m_pinnedChunks.assign((numChunks + 63) / 64, 0);
  }

  {
    std::string name = mwmName;
    base::GetNameFromFullPath(name);
    if (name.size() > 4 && name.substr(name.size() - 4) == ".mwm")
      name = name.substr(0, name.size() - 4);

    std::lock_guard<std::mutex> lock(g_waitInfosMutex);
    if (std::find(g_allVirtualMwms.begin(), g_allVirtualMwms.end(), name) == g_allVirtualMwms.end())
      g_allVirtualMwms.push_back(name);
  }

  LOG(LINFO, ("Registered virtual MWM:", mwmName, "path:", path, "size:", totalSize));
}

std::string GetVirtualMwmPath(std::string const & mwmName)
{
  std::string name = mwmName;
  base::GetNameFromFullPath(name);
  if (name.size() > 4 && name.substr(name.size() - 4) == ".mwm")
    name = name.substr(0, name.size() - 4);

  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  auto it = g_waitInfos.find(name);
  if (it != g_waitInfos.end()) return it->second->m_path;

  return "";
}

bool IsVirtualMwm(std::string const & mwmName)
{
  std::string name = mwmName;
  base::GetNameFromFullPath(name);
  if (name.size() > 4 && name.substr(name.size() - 4) == ".mwm")
    name = name.substr(0, name.size() - 4);

  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  return std::find(g_allVirtualMwms.begin(), g_allVirtualMwms.end(), name) != g_allVirtualMwms.end();
}

void SetRequestDataHandler(TRequestDataFn fn)
{
    g_requestDataHandler = std::move(fn);
}

void SetUiThreadId(std::thread::id id)
{
    g_uiThreadId = id;
    g_uiThreadIdSet.store(true, std::memory_order_relaxed);
}
} // namespace wear
