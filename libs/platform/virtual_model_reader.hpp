#pragma once

#include "coding/reader.hpp"
#include <string>
#include <memory>

class VirtualModelReader : public ModelReader
{
public:
  VirtualModelReader(std::string const & mwmName, std::string const & sparsePath);
  VirtualModelReader(std::string const & mwmName, std::string const & sparsePath, uint64_t offset, uint64_t size);

  // Reader overrides:
  uint64_t Size() const override;
  void Read(uint64_t pos, void * p, size_t size) const override;
  std::unique_ptr<Reader> CreateSubReader(uint64_t pos, uint64_t size) const override;

private:
  std::string m_mwmName;
  std::string m_sparsePath;
  uint64_t m_offset;
  uint64_t m_size;
  mutable std::unique_ptr<Reader> m_proxyReader;

  void EnsureReader() const;
};
