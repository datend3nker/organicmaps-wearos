#pragma once

#include <string>
#include <cstdint>
#include <functional>
#include <thread>

namespace wear
{
// Requests data from phone and blocks current thread until it arrives or timeout.
// Returns true if data is available (already existed or arrived).
bool WaitForData(std::string const & mwmName, uint64_t offset, size_t size);

// Returns true if data for the given range is already available in the local cache.
bool IsDataAvailable(std::string const & mwmName, uint64_t offset, size_t size);

// Signals that data has arrived for the given range.
void SignalData(std::string const & mwmName, uint64_t offset, size_t size);

// Marks a byte range as pinned: it will never be invalidated/evicted. Used for ranges that are
// read via a direct mmap (e.g. succinct sections like the features-offsets table), which bypass
// WaitForData on subsequent reads and would read zeros if their backing blocks were punched out.
void PinData(std::string const & mwmName, uint64_t offset, size_t size);

// Clears the "available" mark for the given range so the next read re-faults and re-fetches it.
// Pinned chunks are left untouched. Returns true iff the whole requested range was cleared
// (i.e. no pinned chunk overlapped it). Used by the bounded-cache eviction path.
bool InvalidateData(std::string const & mwmName, uint64_t offset, size_t size);

// Registers a virtual MWM with its local sparse path and total size.
void RegisterVirtualMwm(std::string const & mwmName, std::string const & path, uint64_t totalSize);

// Returns the local sparse path for a registered virtual MWM, or empty string if not virtual.
std::string GetVirtualMwmPath(std::string const & mwmName);

// Returns true if the given MWM was ever registered as virtual in this session.
bool IsVirtualMwm(std::string const & mwmName);

// Callback mechanism to decouple platform logic from JNI.
using TRequestDataFn = std::function<void(std::string const & mwmName, uint64_t offset, size_t size)>;
void SetRequestDataHandler(TRequestDataFn fn);

// Records the UI/main thread id (called once from JNI_OnLoad). WaitForData never blocks on this
// thread: a streamed read that misses the local cache requests the data and returns immediately so
// the read fails fast (skipped + re-faulted later) instead of stalling the UI thread into an ANR.
void SetUiThreadId(std::thread::id id);
} // namespace wear
