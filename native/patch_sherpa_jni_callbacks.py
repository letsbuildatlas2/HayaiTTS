#!/usr/bin/env python3
"""Patch sherpa-onnx v1.13.2 JNI callback thread affinity; fail closed on source drift."""
from pathlib import Path
import sys
p = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("sherpa-onnx/jni/offline-tts.cc")
s = p.read_text()
old = """    std::function<int32_t(const float *, int32_t, float)> callback_wrapper =
        [env, callback](const float *samples, int32_t n, float) -> int32_t {
      jfloatArray samples_arr = env->NewFloatArray(n);
      env->SetFloatArrayRegion(samples_arr, 0, n, samples);
      int32_t ret = CallCallback(env, callback, samples_arr);
      env->DeleteLocalRef(samples_arr);
      return ret;
    };

    audio = tts->Generate(p_text, """
replacement = """    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
      env->ReleaseStringUTFChars(text, p_text);
      return nullptr;
    }
    jobject global_callback = env->NewGlobalRef(callback);
    if (!global_callback) {
      env->ReleaseStringUTFChars(text, p_text);
      return nullptr;
    }
    std::function<int32_t(const float *, int32_t, float)> callback_wrapper =
        [vm, global_callback](const float *samples, int32_t n, float) -> int32_t {
      JNIEnv *current_env = nullptr;
      bool attached = false;
      jint status = vm->GetEnv(reinterpret_cast<void **>(&current_env), JNI_VERSION_1_6);
      if (status == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&current_env, nullptr) != JNI_OK) return 0;
        attached = true;
      } else if (status != JNI_OK) {
        return 0;
      }
      int32_t ret = 0;
      jfloatArray samples_arr = current_env->NewFloatArray(n);
      if (samples_arr && !current_env->ExceptionCheck()) {
        current_env->SetFloatArrayRegion(samples_arr, 0, n, samples);
        if (!current_env->ExceptionCheck()) {
          ret = CallCallback(current_env, global_callback, samples_arr);
        }
      }
      if (samples_arr) current_env->DeleteLocalRef(samples_arr);
      if (current_env->ExceptionCheck()) {
        current_env->ExceptionDescribe();
        current_env->ExceptionClear();
        ret = 0;
      }
      if (attached) vm->DetachCurrentThread();
      return ret;
    };

    audio = tts->Generate(p_text, """
# Both generateWithCallbackImpl and generateWithConfigImpl have the same unsafe callback.
assert s.count(old) == 2, f"Expected 2 original callback implementations, found {s.count(old)}"
s = s.replace(old, replacement)
# Cleanup must occur after Generate completes, before the else branch.
for arg in ("config", "gen_config"):
    needle = f"    audio = tts->Generate(p_text, {arg}, callback_wrapper);\n  }} else {{"
    assert s.count(needle) == 1, f"Unexpected Generate signature for {arg}"
    s = s.replace(needle, f"    audio = tts->Generate(p_text, {arg}, callback_wrapper);\n    env->DeleteGlobalRef(global_callback);\n  }} else {{")
# Instrument native JNI entry and callback dispatch without logging speech contents.
assert '#include <android/log.h>' not in s
s = s.replace('#include <string>', '#include <android/log.h>\n#include <string>', 1)
entry = '  if (callback) {\n    JavaVM *vm = nullptr;'
assert s.count(entry) == 2
s = s.replace(entry, '  if (callback) {\n    __android_log_print(ANDROID_LOG_INFO, "AtlasNativeJNI", "callback bridge entered");\n    JavaVM *vm = nullptr;')
dispatch = '      jfloatArray samples_arr = current_env->NewFloatArray(n);'
assert s.count(dispatch) == 2
s = s.replace(dispatch, '      __android_log_print(ANDROID_LOG_INFO, "AtlasNativeJNI", "native callback invoked samples=%d", n);\n' + dispatch)
done = '    env->DeleteGlobalRef(global_callback);'
assert s.count(done) == 2
s = s.replace(done, '    __android_log_print(ANDROID_LOG_INFO, "AtlasNativeJNI", "native Generate returned samples=%zu", audio.samples.size());\n' + done)
# Phone-only diagnostics: pass the final buffer through the JNI callback if
# Kokoro produced audio but never invoked its callback. This is diagnostic,
# not low-latency streaming, and does not change the returned audio.
start = '    std::function<int32_t(const float *, int32_t, float)> callback_wrapper =\\n        [vm, global_callback](const float *samples, int32_t n, float) -> int32_t {'
assert s.count(start) == 2
s = s.replace(start, '    int32_t native_callback_count = 0;\\n    std::function<int32_t(const float *, int32_t, float)> callback_wrapper =\\n        [vm, global_callback, &native_callback_count](const float *samples, int32_t n, float) -> int32_t {\\n      ++native_callback_count;')
end = '    env->DeleteGlobalRef(global_callback);'
assert s.count(end) == 2
s = s.replace(end, '    if (native_callback_count == 0 && !audio.samples.empty()) {\\n      __android_log_print(ANDROID_LOG_INFO, "AtlasNativeJNI", "no native chunks: testing final-buffer JNI callback");\\n      callback_wrapper(audio.samples.data(), static_cast<int32_t>(audio.samples.size()), 1.0f);\\n    }\\n' + end)
p.write_text(s)
print(f"Patched and instrumented both callback bridges in {p}")
