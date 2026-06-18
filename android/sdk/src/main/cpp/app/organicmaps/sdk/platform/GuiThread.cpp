#include "app/organicmaps/sdk/platform/GuiThread.hpp"

#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "base/exception.hpp"
#include "base/logging.hpp"

#include <memory>

namespace android
{
GuiThread::GuiThread()
{
  JNIEnv * env = jni::GetEnv();
  if (env == nullptr)
    return;

  m_class = GetGlobalClassRef(env, "app/organicmaps/sdk/util/concurrency/UiThread");
  ASSERT(m_class, ());

  m_method = env->GetStaticMethodID(m_class, "forwardToMainThread", "(J)V");
  ASSERT(m_method, ());
}

GuiThread::~GuiThread()
{
  JNIEnv * env = jni::GetEnv();
  if (env != nullptr && m_class != nullptr)
    env->DeleteGlobalRef(m_class);
}

// static
void GuiThread::ProcessTask(jlong task)
{
  std::unique_ptr<Task> t(reinterpret_cast<Task *>(task));
  try
  {
    (*t)();
  }
  catch (RootException & e)
  {
    // A GUI-thread task that reads streamed (virtual) MWM data can fail when the bytes have not
    // arrived yet over a slow/dropped transport (e.g. Bluetooth). Swallow it so the app keeps
    // running instead of aborting in native code across the JNI boundary; the work retries once
    // the data is available.
    LOG(LWARNING, ("GuiThread task failed:", e.Msg()));
  }
  catch (std::exception & e)
  {
    // Defensive: any other C++ exception must not unwind across the JNI boundary either.
    LOG(LWARNING, ("GuiThread task failed (std::exception):", e.what()));
  }

  // The failed task may have called back into Java (e.g. to request streamed data) and left a
  // pending Java exception set in the env. If nativeProcessTask returns with one still pending, the
  // JNI method epilogue (GenericJniMethodEnd) dereferences a stale reference and SIGSEGVs. Clear it.
  JNIEnv * env = jni::GetEnv();
  if (env != nullptr && env->ExceptionCheck())
  {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

base::TaskLoop::PushResult GuiThread::Push(Task && task)
{
  // Pointer will be deleted in ProcessTask.
  auto t = new Task(std::move(task));
  JNIEnv * env = jni::GetEnv();
  if (env != nullptr)
  {
    env->CallStaticVoidMethod(m_class, m_method, reinterpret_cast<jlong>(t));
    return {true, kNoId};
  }
  delete t;
  return {false, kNoId};
}

base::TaskLoop::PushResult GuiThread::Push(Task const & task)
{
  // Pointer will be deleted in ProcessTask.
  auto t = new Task(task);
  JNIEnv * env = jni::GetEnv();
  if (env != nullptr)
  {
    env->CallStaticVoidMethod(m_class, m_method, reinterpret_cast<jlong>(t));
    return {true, kNoId};
  }
  delete t;
  return {false, kNoId};
}
}  // namespace android
