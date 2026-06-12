#pragma once

#include <string>
#include <cstdint>
#include <functional>

namespace wear
{
// Requests data from phone and blocks current thread until it arrives or timeout.
void WaitForData(std::string const & mwmName, uint64_t offset, size_t size);

// Returns true if data for the given range is already available in the local cache.
bool IsDataAvailable(std::string const & mwmName, uint64_t offset, size_t size);

// Signals that data has arrived for the given range.
void SignalData(std::string const & mwmName, uint64_t offset, size_t size);

// Registers a virtual MWM with its local sparse path and total size.
void RegisterVirtualMwm(std::string const & mwmName, std::string const & path, uint64_t totalSize);

// Returns the local sparse path for a registered virtual MWM, or empty string if not virtual.
std::string GetVirtualMwmPath(std::string const & mwmName);

// Returns true if the given MWM was ever registered as virtual in this session.
bool IsVirtualMwm(std::string const & mwmName);

// Callback mechanism to decouple platform logic from JNI.
using TRequestDataFn = std::function<void(std::string const & mwmName, uint64_t offset, size_t size)>;
void SetRequestDataHandler(TRequestDataFn fn);
} // namespace wear
