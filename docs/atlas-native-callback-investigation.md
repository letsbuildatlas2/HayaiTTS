# Native callback failure: investigation and fix specification

## Reproduction (Galaxy S21+, Kokoro en v0.19 George)

At 2026-10-18 18:58:02, `native callback probe begin` logged after model preparation. No first chunk, completion or error after 20 seconds. Android PCM delivery was *not* called in the callback. Buffered `generate()` remains functional. Do not use this callback in production until resolved.

## Source-level defect candidate

Upstream `sherpa-onnx/jni/offline-tts.cc` defines `Java_com_k2fsa_sherpa_onnx_OfflineTts_generateWithCallbackImpl`. Its lambda captures `[env, callback]` and invokes `env->NewFloatArray`, `env->SetFloatArrayRegion` and `CallCallback(env,...)`. A `JNIEnv*` is valid only on its originating thread. If `OfflineTts::Generate` invokes callbacks from worker threads, this is invalid. This is a hypothesis, not yet a confirmed root cause.

## Native patch design

1. On the JNI entry thread call `env->GetJavaVM(&vm)` and `env->NewGlobalRef(callback)`. Check errors.
2. In the callback obtain the current thread's `JNIEnv*` using `vm->GetEnv`. If detached, call `AttachCurrentThread`; detach **only if this callback attached**.
3. Create and release `jfloatArray` with the current thread's `JNIEnv*`, call Java callback and check `ExceptionCheck`; abort safely on exception.
4. Release the global callback reference **after** `Generate` returns. Ensure cleanup on all error paths.
5. Apply the same review to `generateWithConfigImpl`, which also captures `env` in its callback.
6. Build an Android arm64 sherpa-onnx AAR from the exact source revision corresponding to the vendored `app/libs/sherpa-onnx-1.13.2.aar`, with compatible public Kotlin/JNI interfaces. Do not replace the AAR with a different API version.
7. Rebuild HayaiTTS on this experiment branch. Record callback entry thread IDs, first chunk timestamp, number of chunks, final return and audio start. Use one 75-character George test with 4 threads and NNAPI off.
8. Keep the current buffered path as default until voice quality, no hangs, and first-audio timing are verified.

## Exit criteria

- Callback completes with nonzero chunks and no JNI errors on repeated warm/cold runs.
- First audible PCM reaches Atlas under 1.5 s for target-length response, or evidence explains why not.
- Stable buffered path unaffected.
- If callback still hangs, instrument native `OfflineTts::Generate` and Kokoro model implementation to identify the blocking stage before any further UI experiments.

## Safety

Never forcibly interrupt a blocked native JNI inference thread or switch cached engines while it is running. The probe switch must remain OFF for ordinary use.
