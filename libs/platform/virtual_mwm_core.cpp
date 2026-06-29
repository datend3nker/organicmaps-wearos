#include "platform/virtual_mwm_core.hpp"
#include "base/logging.hpp"
#include "base/file_name_utils.hpp"

#include <mutex>
#include <condition_variable>
#include <map>
#include <chrono>
#include <memory>
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
  // Set true when the MWM is unregistered. Waiters wake and bail out instead of blocking for data
  // that will never come. Guarded by m_mutex.
  bool m_defunct = false;

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

// shared_ptr (not unique_ptr): a reader thread blocked in WaitForData holds its own shared_ptr for
// the whole wait, so the MwmWaitInfo — and its mutex/cv — stays alive even if UnregisterVirtualMwm
// erases the map entry concurrently. With unique_ptr the erase destroyed the mutex under the waiting
// thread → "pthread_mutex_lock called on a destroyed mutex" FORTIFY abort (or a hang) on unmount/
// remount during streaming.
std::map<std::string, std::shared_ptr<MwmWaitInfo>> g_waitInfos;
std::vector<std::string> g_allVirtualMwms;
std::mutex g_waitInfosMutex;
wear::TRequestDataFn g_requestDataHandler;
std::thread::id g_uiThreadId;
std::atomic<bool> g_uiThreadIdSet{false};
// Set while the watch is ambient / its surface is being torn down. Blocked WaitForData calls bail
// out immediately instead of holding a drape read thread for up to 40s, which otherwise backs up the
// render pipeline so the ambient/surface transition can't complete (Ambient transition timeout +
// "Out of order buffers" surface desync = frozen watch). Reset to false on resume so streaming
// resumes with the full timeout during active use.
std::atomic<bool> g_streamingPaused{false};

std::shared_ptr<MwmWaitInfo> GetWaitInfo(std::string const & mwmNameWithExt)
{
  std::string mwmName = mwmNameWithExt;
  base::GetNameFromFullPath(mwmName);
  if (mwmName.size() > 4 && mwmName.substr(mwmName.size() - 4) == ".mwm")
    mwmName = mwmName.substr(0, mwmName.size() - 4);

  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  auto it = g_waitInfos.find(mwmName);
  if (it == g_waitInfos.end())
  {
    auto info = std::make_shared<MwmWaitInfo>();
    g_waitInfos[mwmName] = info;
    return info;
  }
  return it->second;
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

  // Hold the shared_ptr for the whole call: keeps the mutex/cv alive even if the MWM is unregistered
  // mid-wait (see g_waitInfos comment).
  auto const info = GetWaitInfo(mwmName);

  // Never block the UI/main thread on a network round-trip to the phone: it would freeze input and
  // ANR. If the data isn't already cached, request it and fail fast — the read is skipped and will
  // re-fault once the bytes arrive (same path as a normal timeout, just instant).
  if (g_uiThreadIdSet.load(std::memory_order_relaxed) && std::this_thread::get_id() == g_uiThreadId)
  {
    {
      std::lock_guard<std::mutex> lock(info->m_mutex);
      if (info->IsAvailable(offset, size))
        return true;
    }
    if (g_requestDataHandler)
      g_requestDataHandler(mwmName, offset, size);
    LOG(LDEBUG, ("WaitForData on UI thread, not blocking:", mwmName, "offset:", offset));
    return false;
  }

  for (int attempt = 1; attempt <= 8; ++attempt)
  {
    {
      std::lock_guard<std::mutex> lock(info->m_mutex);
      if (info->IsAvailable(offset, size))
        return true;
      if (info->m_defunct)
        return false;
    }

    // Watch is ambient / surface tearing down: don't hold a read thread blocking on the phone — bail
    // so the render pipeline drains and the transition completes (the tile re-faults on resume).
    if (g_streamingPaused.load(std::memory_order_relaxed))
      return false;

    if (g_requestDataHandler)
      g_requestDataHandler(mwmName, offset, size);

    std::unique_lock<std::mutex> lock(info->m_mutex);
    if (info->m_cv.wait_for(lock, std::chrono::seconds(5),
                            [&]{ return info->IsAvailable(offset, size) || info->m_defunct ||
                                        g_streamingPaused.load(std::memory_order_relaxed); }))
    {
      if (info->m_defunct || g_streamingPaused.load(std::memory_order_relaxed))
        return false;
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
  auto const info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info->m_mutex);
  return info->IsAvailable(offset, size);
}

void SignalData(std::string const & mwmName, uint64_t offset, size_t size)
{
  LOG(LDEBUG, ("SignalData:", mwmName, "offset:", offset, "size:", size));
  auto const info = GetWaitInfo(mwmName);
  {
    std::lock_guard<std::mutex> lock(info->m_mutex);
    info->MarkAvailable(offset, size);
  }
  info->m_cv.notify_all();
}

void PinData(std::string const & mwmName, uint64_t offset, size_t size)
{
  LOG(LDEBUG, ("PinData:", mwmName, "offset:", offset, "size:", size));
  auto const info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info->m_mutex);
  info->MarkPinned(offset, size);
}

bool InvalidateData(std::string const & mwmName, uint64_t offset, size_t size)
{
  auto const info = GetWaitInfo(mwmName);
  std::lock_guard<std::mutex> lock(info->m_mutex);
  return info->ClearAvailableUnpinned(offset, size);
}

void RegisterVirtualMwm(std::string const & mwmName, std::string const & path, uint64_t totalSize)
{
  auto const info = GetWaitInfo(mwmName);
  {
    std::lock_guard<std::mutex> lock(info->m_mutex);
    info->m_path = path;
    info->m_totalSize = totalSize;
    uint64_t numChunks = (totalSize + kChunkSize - 1) / kChunkSize;
    info->m_availableChunks.assign((numChunks + 63) / 64, 0);
    info->m_pinnedChunks.assign((numChunks + 63) / 64, 0);
    // Re-registering a previously-unregistered MWM: clear the defunct flag so reads block normally.
    info->m_defunct = false;
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

void UnregisterVirtualMwm(std::string const & mwmName)
{
  std::string name = mwmName;
  base::GetNameFromFullPath(name);
  if (name.size() > 4 && name.substr(name.size() - 4) == ".mwm")
    name = name.substr(0, name.size() - 4);

  std::shared_ptr<MwmWaitInfo> info;
  {
    std::lock_guard<std::mutex> lock(g_waitInfosMutex);
    auto it = g_waitInfos.find(name);
    if (it != g_waitInfos.end())
    {
      info = it->second;       // keep alive past erase so any in-flight waiter's mutex stays valid
      g_waitInfos.erase(it);
    }
    auto vit = std::find(g_allVirtualMwms.begin(), g_allVirtualMwms.end(), name);
    if (vit != g_allVirtualMwms.end())
      g_allVirtualMwms.erase(vit);
  }

  // Wake any thread blocked in WaitForData on this MWM so it bails out promptly (and never touches a
  // destroyed mutex). The waiter holds its own shared_ptr, so the object lives until it returns.
  if (info)
  {
    {
      std::lock_guard<std::mutex> lock(info->m_mutex);
      info->m_defunct = true;
    }
    info->m_cv.notify_all();
  }

  LOG(LINFO, ("Unregistered virtual MWM:", mwmName));
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

void SetStreamingPaused(bool paused)
{
  g_streamingPaused.store(paused, std::memory_order_relaxed);
  if (!paused)
    return;

  // Wake every thread currently blocked in WaitForData so it observes the paused flag and returns
  // immediately, freeing the drape read threads for the ambient/surface transition.
  std::lock_guard<std::mutex> lock(g_waitInfosMutex);
  for (auto const & kv : g_waitInfos)
    kv.second->m_cv.notify_all();
}
} // namespace wear
