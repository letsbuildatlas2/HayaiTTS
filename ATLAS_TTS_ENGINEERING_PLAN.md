# Atlas 2.0 — George TTS latency investigation and handoff

Date: 2026-10-17. Branch: feature/atlas-streaming-tts. Keep the working buffered baseline commit f2b1b6d and Atlas diagnostics commit c860161 available for rollback.

## Verified device evidence
- Experimental Android TTS package: dev.ahmedmohamed.hayaitts.atlasstream.
- Kokoro 11-speaker model, bm_george, correct sound.
- Segmented Atlas playback caused audible gaps; do not use for normal conversations.
- Native callback streaming experiment stalled after onStart, no audio.
- Buffered synthesis restored smooth audible playback.
- Buffered request-to-first-audio: 4814 ms and 4784 ms, complete at 8900 ms. Baseline original: 9151 ms first audio. These are buffer timings, not verified acoustic onset.
- Samsung TTS works in Atlas. Studio playback and Android TTS service are separate paths.

## Research and hypothesis
- sherpa-onnx JNI generateWithCallbackImpl invokes native generation callback through JNI. An upstream issue #3492 reports Android Kokoro JNI thread-affinity crash with generateWithCallback; verify sherpa-onnx version and whether this affects our binary before relying on that path.
- Upstream Kokoro examples show callbacks are available, but callback delivery cadence and threading must be verified, not assumed. Avoid interpreting a callback as guaranteed low-latency streaming.
- Investigate runtime model load, numThreads, provider, warm-up and callback vs buffered performance. Do not assume extra threads always help.

## Engineering sequence (minimal phone installs)
1. Freeze known-good buffered build; preserve package suffix and signing key; NEVER uninstall original HayaiTTS or Atlas.
2. Inspect exact sherpa-onnx dependency version, JNI callback implementation, SherpaTtsRuntime.synthesizeStreaming, model config, and any model-load synchronization.
3. Prepare one new experimental branch or feature flag with safe, time-bounded streaming and fallback. Note: native blocking calls cannot be safely cancelled just by Kotlin coroutine timeout; if unsafe, retain buffered path and prioritize runtime optimization.
4. Instrument separately: model-ready time, native generate entry/exit, first native callback, first PCM delivery, Android onAudioAvailable/onDone/onError; include explicit engine package. Ensure failure signals, no silent completion.
5. Run GitHub Actions compilation and static checks BEFORE inviting phone install. Preserve existing test app ID, package signature and data. Do not change applicationIdSuffix or debug signing.
6. User acceptance test: same placeholder utterance 3 times, measure first audio and completion, confirm continuous natural delivery, no silent requests, voice loop resumes, and typed replies remain silent. Compare with 4784–4814 ms baseline.
7. If native streaming unreliable, benchmark tuned buffered Kokoro or alternate British offline voice engines against quality/latency. Avoid repeated install cycles without strong evidence.

## Acceptance targets
- Correct George voice, reliable onDone/error, continuous delivery and hands-free operation.
- Aim for 1–2 s first audible response, but treat as goal not promise; confirm on actual S21+.
- Explicit rollback path and single clear installation instruction per candidate.

## Return-to-work checklist
- Review GitHub code and upstream issue #3492.
- Make and validate changes on a separate experimental branch, without overwriting known-good commit.
- Present user with exactly one APK to install only when build passes and evidence justifies testing.
