#include "platform/virtual_model_reader.hpp"
#include "platform/virtual_mwm_core.hpp"
#include "platform/platform.hpp"
#include "base/logging.hpp"

VirtualModelReader::VirtualModelReader(std::string const & mwmName, std::string const & sparsePath)
  : ModelReader(mwmName), m_mwmName(mwmName), m_sparsePath(sparsePath), m_offset(0), m_size(0)
{
}

VirtualModelReader::VirtualModelReader(std::string const & mwmName, std::string const & sparsePath, uint64_t offset, uint64_t size)
  : ModelReader(mwmName), m_mwmName(mwmName), m_sparsePath(sparsePath), m_offset(offset), m_size(size)
{
}

uint64_t VirtualModelReader::Size() const
{
  if (m_size > 0) return m_size;
  EnsureReader();
  return m_proxyReader->Size();
}

void VirtualModelReader::Read(uint64_t pos, void * p, size_t size) const
{
  uint64_t const absPos = m_offset + pos;
  if (!wear::IsDataAvailable(m_mwmName, absPos, size))
  {
    wear::WaitForData(m_mwmName, absPos, size);
  }

  EnsureReader();
  m_proxyReader->Read(pos, p, size);
}

std::unique_ptr<Reader> VirtualModelReader::CreateSubReader(uint64_t pos, uint64_t size) const
{
  return std::make_unique<VirtualModelReader>(m_mwmName, m_sparsePath, m_offset + pos, size);
}

void VirtualModelReader::EnsureReader() const
{
  if (!m_proxyReader)
  {
    m_proxyReader = GetPlatform().GetReader(m_sparsePath, "f");
    if (m_offset > 0 || m_size > 0)
    {
       uint64_t s = m_size;
       if (s == 0) s = m_proxyReader->Size() - m_offset;
       m_proxyReader = m_proxyReader->CreateSubReader(m_offset, s);
    }
  }
}
