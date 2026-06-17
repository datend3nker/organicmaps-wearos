#pragma once

#include "drape/gl_includes.hpp"
#include "drape/oglcontext.hpp"

#include <atomic>

namespace android
{
class AndroidOGLContext : public dp::OGLContext
{
public:
  AndroidOGLContext(EGLDisplay display, EGLSurface surface, EGLConfig config, AndroidOGLContext * contextToShareWith);
  ~AndroidOGLContext();

  void MakeCurrent() ;
  void DoneCurrent() ;
  void Present() ;
  void SetFramebuffer(ref_ptr<dp::BaseFramebuffer> framebuffer) override;
  void SetRenderingEnabled(bool enabled) ;
  void SetPresentAvailable(bool available) ;
  bool Validate() ;

  void SetSurface(EGLSurface surface);
  void ResetSurface();

  void ClearCurrent();

private:
  // {@ Owned by Context
  EGLContext m_nativeContext;
  // @}

  // {@ Owned by Factory
  EGLSurface m_surface;
  EGLDisplay m_display;
  // @}

  std::atomic<bool> m_presentAvailable;
};
}  // namespace android
