#include "app/organicmaps/sdk/Framework.hpp"
#include "app/organicmaps/sdk/core/jni_helper.hpp"
#include "platform/settings.hpp"
#include "base/logging.hpp"

extern "C"
{
JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_util_Config_nativeHasConfigValue(JNIEnv * env, jclass thiz, jstring name)
{
  if (name == nullptr)
    return static_cast<jboolean>(false);

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return static_cast<jboolean>(false);

  std::string value;
  return settings::Get(key, value);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeDeleteConfigValue(JNIEnv * env, jclass thiz, jstring name)
{
  if (name == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  settings::Delete(key);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetBoolean(JNIEnv * env, jclass thiz, jstring name,
                                                                         jboolean defaultVal)
{
  if (name == nullptr)
    return defaultVal;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return defaultVal;

  bool val;
  if (settings::Get(key, val))
    return static_cast<jboolean>(val);

  return defaultVal;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetBoolean(JNIEnv * env, jclass thiz, jstring name,
                                                                     jboolean val)
{
  if (name == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  (void)settings::Set(key, static_cast<bool>(val));
}

JNIEXPORT jint JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetInt(JNIEnv * env, jclass thiz, jstring name,
                                                                 jint defaultValue)
{
  if (name == nullptr)
    return defaultValue;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return defaultValue;

  int32_t value;
  if (settings::Get(key, value))
    return static_cast<jint>(value);

  return defaultValue;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetInt(JNIEnv * env, jclass thiz, jstring name, jint value)
{
  if (name == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  (void)settings::Set(key, static_cast<int32_t>(value));
}

JNIEXPORT jlong JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetLong(JNIEnv * env, jclass thiz, jstring name,
                                                                   jlong defaultValue)
{
  if (name == nullptr)
    return defaultValue;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return defaultValue;

  int64_t value;
  if (settings::Get(key, value))
    return static_cast<jlong>(value);

  return defaultValue;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetLong(JNIEnv * env, jclass thiz, jstring name, jlong value)
{
  if (name == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  (void)settings::Set(key, static_cast<int64_t>(value));
}

JNIEXPORT jdouble JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetDouble(JNIEnv * env, jclass thiz, jstring name,
                                                                       jdouble defaultValue)
{
  if (name == nullptr)
    return defaultValue;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return defaultValue;

  double value;
  if (settings::Get(key, value))
    return static_cast<jdouble>(value);

  return defaultValue;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetDouble(JNIEnv * env, jclass thiz, jstring name,
                                                                    jdouble value)
{
  if (name == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  (void)settings::Set(key, static_cast<double>(value));
}

JNIEXPORT jstring JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetString(JNIEnv * env, jclass thiz, jstring name,
                                                                       jstring defaultValue)
{
  if (name == nullptr)
    return defaultValue;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return defaultValue;

  std::string value;
  if (settings::Get(key, value))
  {
    jstring res = jni::ToJavaString(env, value);
    jni::HandleJavaException(env);
    return res;
  }

  return defaultValue;
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetString(JNIEnv * env, jclass thiz, jstring name,
                                                                    jstring value)
{
  if (name == nullptr || value == nullptr)
    return;

  std::string const key = jni::ToNativeString(env, name);
  if (jni::HandleJavaException(env)) return;

  std::string const val = jni::ToNativeString(env, value);
  if (jni::HandleJavaException(env)) return;

  (void)settings::Set(key, val);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetLargeFontsSize(JNIEnv * env, jclass thiz)
{
  ::Framework * f = frm();
  return f ? f->LoadLargeFontsSize() : static_cast<jboolean>(false);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetLargeFontsSize(JNIEnv * env, jclass thiz, jboolean value)
{
  ::Framework * f = frm();
  if (f) f->SetLargeFontsSize(value);
}

JNIEXPORT jboolean JNICALL Java_app_organicmaps_sdk_util_Config_nativeGetTransliteration(JNIEnv * env, jclass thiz)
{
  ::Framework * f = frm();
  return f ? f->LoadTransliteration() : static_cast<jboolean>(false);
}

JNIEXPORT void JNICALL Java_app_organicmaps_sdk_util_Config_nativeSetTransliteration(JNIEnv * env, jclass thiz, jboolean value)
{
  ::Framework * f = frm();
  if (f)
  {
    f->SaveTransliteration(value);
    f->AllowTransliteration(value);
  }
}
}  // extern "C"
