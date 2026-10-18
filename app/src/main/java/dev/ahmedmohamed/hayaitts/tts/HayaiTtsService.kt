package dev.ahmedmohamed.hayaitts.tts

import android.media.AudioFormat
import android.os.SystemClock
import android.os.PowerManager
import android.content.Context
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import co.touchlab.kermit.Logger
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuning
import dev.ahmedmohamed.hayaitts.data.playground.VoiceTuningRepository
import dev.ahmedmohamed.hayaitts.data.voices.parseLocale
import dev.ahmedmohamed.hayaitts.domain.model.InstalledVoice
import dev.ahmedmohamed.hayaitts.domain.repo.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import java.util.Locale

/**
 * System-wide TTS engine. Iterates every voice the user has installed
 * (Room), exposing each one to the TTS framework via [onGetVoices].
 *
 * Locale resolution: when the framework picks a [language/country] the
 * framework calls [onIsLanguageAvailable]; we return `LANG_AVAILABLE` if any
 * installed voice tags it (BCP-47 prefix match). The actual voice picked is
 * decided by [SynthesisRequest.voiceName].
 */
class HayaiTtsService : TextToSpeechService() {

    private val log = Logger.withTag("HayaiTtsService")

    @Volatile private var stopRequested: Boolean = false
    @Volatile private var lastSelectedVoiceId: String = ""
    private val preloadLock = Any()
    private var preloadingVoiceId: String? = null

    override fun onCreate() {
        super.onCreate()
        // Start warming George as soon as Android creates this service,
        // before onLoadVoice or the first synthesis request. A missing model
        // is harmless: normal voice resolution and synthesis remain intact.
        Thread({
            val started = SystemClock.elapsedRealtime()
            try {
                val george = SherpaTtsRuntime.get(applicationContext)
                    .listAvailableVoices()
                    .firstOrNull { it.voiceId == "kokoro-en-v0_19" }
                if (george != null) {
                    val rate = SherpaTtsRuntime.get(applicationContext)
                        .sampleRateOf(george.voiceId)
                    AtlasTtsDiagnostics.record(applicationContext,
                        "service startup preload ready voice=${george.voiceId} sampleRate=$rate elapsed=${SystemClock.elapsedRealtime() - started}ms")
                    // Experimental: exercise the native graph before the first user request.
                    // No PCM is played. Keep off the stable branch until measured on device.
                    val warmupStarted = SystemClock.elapsedRealtime()
                    try {
                        val warmup = SherpaTtsRuntime.get(applicationContext).synthesize(
                            voiceId = george.voiceId,
                            text = "Hello.",
                            sid = 9,
                        )
                        AtlasTtsDiagnostics.record(applicationContext,
                            "experimental inference warmup ready elapsed=${SystemClock.elapsedRealtime() - warmupStarted}ms samples=${warmup.samples.size}")
                    } catch (warmupError: Throwable) {
                        AtlasTtsDiagnostics.record(applicationContext,
                            "experimental inference warmup failed: ${warmupError.javaClass.simpleName}")
                    }
                } else {
                    AtlasTtsDiagnostics.record(applicationContext,
                        "service startup preload skipped: George not installed")
                }
            } catch (t: Throwable) {
                AtlasTtsDiagnostics.record(applicationContext,
                    "service startup preload failed: ${t.javaClass.simpleName}")
            }
        }, "AtlasStartupPreload").apply { isDaemon = true; start() }
    }


    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voices = runtimeOrNull()?.listAvailableVoices().orEmpty()
        val requested = buildBcp47(lang, country, variant)
        log.i { "Atlas voice registry: language request=$lang/$country/$variant parsed=$requested available=${voices.size}" }
        return when {
            requested.isEmpty() -> TextToSpeech.LANG_NOT_SUPPORTED
            voices.any { it.matchesLocale(requested) } -> TextToSpeech.LANG_AVAILABLE
            voices.any { it.matchesLanguage(lang) } -> TextToSpeech.LANG_AVAILABLE
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> {
        // The framework expects ISO-639 alpha-3 + ISO-3166 alpha-3 + variant.
        // For the currently selected voice, derive these from its first
        // language tag. Defaults to eng/USA so the framework treats the engine
        // as English-capable when no synthesis has run yet.
        val voice = runtimeOrNull()?.listAvailableVoices()?.firstOrNull { it.voiceId == lastSelectedVoiceId }
        val tag = voice?.languages?.firstOrNull() ?: "en-US"
        val locale = parseLocale(tag)
        return arrayOf(locale.isO3Language.ifEmpty { "eng" }, runCatching { locale.isO3Country }.getOrDefault("USA"), "")
    }

    override fun onGetVoices(): List<Voice> {
        val voices = runtimeOrNull()?.listAvailableVoices().orEmpty()
        log.i { "Atlas voice registry: onGetVoices installed=${voices.size} speakerCounts=${voices.map { it.voiceId to it.speakers.size }}" }
        return voices.flatMap { voice ->
            if (voice.speakers.size > 1) {
                voice.speakers.map { speaker ->
                    val voiceName = "${voice.voiceId}#${speaker.id}#${speaker.name}"
                    val locale = parseLocale(voice.languages.firstOrNull() ?: "en-US")
                    Voice(
                        voiceName,
                        locale,
                        Voice.QUALITY_NORMAL,
                        Voice.LATENCY_NORMAL,
                        /* requiresNetworkConnection = */ false,
                        /* features = */ emptySet(),
                    )
                }
            } else {
                listOf(voice.toFrameworkVoice())
            }
        }
    }

    override fun onIsValidVoiceName(name: String?): Int {
        val accepted = resolveVoiceName(name) != null
        log.i { "Atlas voice registry: onIsValidVoiceName name=$name accepted=$accepted" }
        return if (accepted) TextToSpeech.SUCCESS else TextToSpeech.ERROR
    }

    override fun onLoadVoice(name: String?): Int {
        if (resolveVoiceName(name) == null) {
            log.w { "Atlas voice registry: onLoadVoice rejected name=$name" }
            return TextToSpeech.ERROR
        }
        log.i { "Atlas voice registry: onLoadVoice accepted name=$name" }
        lastSelectedVoiceId = name!!
        // Preload the selected voice before the first synthesis request.
        // Do not block Android's onLoadVoice binder callback: cold Kokoro
        // initialization can take several seconds on a phone.
        val selectedId = parseVoiceName(name).first
        synchronized(preloadLock) {
            if (preloadingVoiceId != selectedId) {
                preloadingVoiceId = selectedId
                Thread({
                    val started = SystemClock.elapsedRealtime()
                    try {
                        val rate = SherpaTtsRuntime.get(applicationContext).sampleRateOf(selectedId)
                        AtlasTtsDiagnostics.record(applicationContext,
                            "voice preload ready voice=$selectedId sampleRate=$rate elapsed=${SystemClock.elapsedRealtime() - started}ms")
                    } catch (t: Throwable) {
                        AtlasTtsDiagnostics.record(applicationContext,
                            "voice preload failed voice=$selectedId reason=${t.javaClass.simpleName}")
                    } finally {
                        synchronized(preloadLock) {
                            if (preloadingVoiceId == selectedId) preloadingVoiceId = null
                        }
                    }
                }, "AtlasVoicePreload").apply { isDaemon = true; start() }
            }
        }
        return TextToSpeech.SUCCESS
    }

    /**
     * Parse a framework voice name and validate it against the installed
     * catalog. Returns the resolved [InstalledVoice] (and optionally the
     * speaker id parsed from a `voiceId#speakerId#name` form) on success, or
     * null if the name is malformed or unknown.
     */
    private fun resolveVoiceName(name: String?): InstalledVoice? {
        if (name.isNullOrEmpty()) return null
        val (voiceId, speakerId) = parseVoiceName(name)
        val voice = runtimeOrNull()?.listAvailableVoices().orEmpty()
            .firstOrNull { it.voiceId == voiceId } ?: return null
        if (speakerId != null && voice.speakers.none { it.id == speakerId }) return null
        return voice
    }

    /**
     * Voice names exposed via [onGetVoices] use the form
     * `voiceId#speakerId#speakerName` for multi-speaker models and plain
     * `voiceId` otherwise. This helper recovers both parts.
     */
    private fun parseVoiceName(name: String): Pair<String, Int?> {
        if (name.isEmpty()) return "" to null
        val parts = name.split("#", limit = 3)
        val voiceId = parts[0]
        val speakerId = parts.getOrNull(1)?.toIntOrNull()
        return voiceId to speakerId
    }

    override fun onStop() {
        stopRequested = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested = false
        val requestStartMs = SystemClock.elapsedRealtime()

        val rawText = request.charSequenceText?.toString().orEmpty()
        val text = if (rawText.length > MAX_TEXT_LENGTH) {
            rawText.substring(0, MAX_TEXT_LENGTH)
        } else rawText

        AtlasTtsDiagnostics.record(applicationContext, "request chars=${text.length}")
        log.i { "Atlas timing: request received chars=${text.length}" }
        val runtime = try {
            SherpaTtsRuntime.get(applicationContext)
        } catch (t: Throwable) {
            log.e(t) { "Failed to initialise SherpaTtsRuntime" }
            AtlasTtsDiagnostics.record(applicationContext, "runtime initialisation failed after ${SystemClock.elapsedRealtime() - requestStartMs}ms: ${t.javaClass.simpleName}")
            callback.error()
            return
        }

        val requestVoiceName = request.voiceName ?: lastSelectedVoiceId
        val requestLanguageHint = buildBcp47(
            request.language,
            request.country,
            request.variant,
        ).ifBlank { null }
        val (selectedVoiceId, selectedSpeakerId) = parseVoiceName(requestVoiceName)
        val (fallbackVoiceId, _) = parseVoiceName(lastSelectedVoiceId)

        val candidates = runtime.listAvailableVoices()
        val voice = candidates.firstOrNull { it.voiceId == selectedVoiceId }
            ?: candidates.firstOrNull { it.voiceId == fallbackVoiceId }
            ?: candidates.firstOrNull { it.bundled }
            ?: candidates.firstOrNull()
            ?: run {
                log.e { "No voices available" }
                callback.error()
                return
            }

        if (text.isEmpty()) {
            if (callback.start(voice.sampleRateHz, AudioFormat.ENCODING_PCM_16BIT, 1) ==
                TextToSpeech.SUCCESS
            ) callback.done()
            return
        }

        val settingsStartMs = SystemClock.elapsedRealtime()
        val tuningRepo = runCatching { GlobalContext.get().get<VoiceTuningRepository>() }.getOrNull()
        val tuning = tuningRepo?.let { repo ->
            runCatching { runBlocking { repo.tuningFor(voice.voiceId).first() } }.getOrNull()
        } ?: VoiceTuning.Default

        val requestSpeed = (request.speechRate.toFloat() / 100f).coerceIn(0.5f, 2.0f)
        val speed = (requestSpeed * tuning.speed).coerceIn(0.5f, 2.0f)

        val settingsRepo = runCatching { GlobalContext.get().get<SettingsRepository>() }.getOrNull()
        val defaultSpeaker = settingsRepo?.let { repo ->
            runCatching { runBlocking { repo.defaultSpeakerByVoice.first()[voice.voiceId] } }.getOrNull()
        }

        val sid = selectedSpeakerId
            ?: request.params?.getString("speakerId")?.toIntOrNull()
            ?: defaultSpeaker
            ?: voice.speakers.firstOrNull()?.id
            ?: 0

        log.i { "Atlas timing: settings/voice selection=${SystemClock.elapsedRealtime() - settingsStartMs}ms sinceRequest=${SystemClock.elapsedRealtime() - requestStartMs}ms" }
        val modelPrepareStartMs = SystemClock.elapsedRealtime()
        val sampleRate = try {
            runtime.sampleRateOf(voice.voiceId, languageHint = requestLanguageHint)
        } catch (t: Throwable) {
            log.e(t) { "Could not load voice ${voice.voiceId}" }
            callback.error()
            return
        }

        AtlasTtsDiagnostics.record(applicationContext, "model/sample-rate ready after ${SystemClock.elapsedRealtime() - requestStartMs}ms")
        log.i { "Atlas timing: model/sample-rate lookup=${SystemClock.elapsedRealtime() - modelPrepareStartMs}ms sinceRequest=${SystemClock.elapsedRealtime() - requestStartMs}ms" }

        val callbackStartMs = SystemClock.elapsedRealtime()
        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, /* channelCount = */ 1)
            != TextToSpeech.SUCCESS
        ) {
            return
        }

        AtlasTtsDiagnostics.record(applicationContext, "Android synthesis start after ${SystemClock.elapsedRealtime() - requestStartMs}ms")
        log.i { "Atlas timing: Android callback.start=${SystemClock.elapsedRealtime() - callbackStartMs}ms sinceRequest=${SystemClock.elapsedRealtime() - requestStartMs}ms" }

        // Stable baseline: native generateWithCallback can stall without returning
        // a first chunk on this device. Use bounded buffered generation instead.
        val synthesisStartMs = SystemClock.elapsedRealtime()
        val cpuStartNs = android.os.Debug.threadCpuTimeNanos()
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalBefore = if (android.os.Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
        val activeThreads = runCatching {
            runBlocking { settingsRepo?.synthesisThreads?.first() }
        }.getOrNull() ?: 2
        val activeNnapi = runCatching {
            runBlocking { settingsRepo?.useNnapi?.first() }
        }.getOrNull() ?: false
        AtlasTtsDiagnostics.record(applicationContext, "benchmark config threads=$activeThreads nnapi=$activeNnapi voice=${voice.voiceId} chars=${text.length}")
        AtlasTtsDiagnostics.record(applicationContext, "buffered inference begin thermal=$thermalBefore thread=${Thread.currentThread().name}")
        try {
            val segments = firstAudioSegments(text)
            AtlasTtsDiagnostics.record(applicationContext,
                "experimental segments=${segments.size} lengths=${segments.map { it.length }}")
            var deliveredSamples = 0
            for ((segmentIndex, segment) in segments.withIndex()) {
                if (stopRequested) {
                    AtlasTtsDiagnostics.record(applicationContext,
                        "experimental segment cancelled index=$segmentIndex")
                    callback.error()
                    return
                }
                val segmentStartedMs = SystemClock.elapsedRealtime()
                val output = runtime.synthesize(
                    voiceId = voice.voiceId, text = segment, sid = sid, speed = speed,
                    pitch = tuning.pitch, lengthScale = tuning.lengthScale,
                    noiseScale = tuning.noiseScale, noiseScaleW = tuning.noiseScaleW,
                    languageHint = requestLanguageHint,
                )
                val generationMs = SystemClock.elapsedRealtime() - segmentStartedMs
                if (output.samples.isEmpty()) {
                    AtlasTtsDiagnostics.record(applicationContext,
                        "experimental segment empty index=$segmentIndex")
                    callback.error()
                    return
                }
                AtlasTtsDiagnostics.record(applicationContext,
                    "experimental segment generated index=$segmentIndex generationMs=$generationMs totalMs=${SystemClock.elapsedRealtime() - requestStartMs} samples=${output.samples.size}")
                if (!streamPcm(callback, output.samples)) {
                    AtlasTtsDiagnostics.record(applicationContext,
                        "experimental segment delivery failed index=$segmentIndex stopped=$stopRequested")
                    callback.error()
                    return
                }
                deliveredSamples += output.samples.size
                AtlasTtsDiagnostics.record(applicationContext,
                    "experimental segment delivered index=$segmentIndex totalMs=${SystemClock.elapsedRealtime() - requestStartMs}")
            }
            val cpuMs = (android.os.Debug.threadCpuTimeNanos() - cpuStartNs) / 1_000_000L
            val thermalAfter = if (android.os.Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
            AtlasTtsDiagnostics.record(applicationContext,
                "experimental speech generated segments=${segments.size} samples=$deliveredSamples cpuMs=$cpuMs thermal=$thermalBefore->$thermalAfter")
            val doneResult = callback.done()
            AtlasTtsDiagnostics.record(applicationContext, "speech completed total ${SystemClock.elapsedRealtime() - requestStartMs}ms; callbackDone=$doneResult; stopped=$stopRequested")
        } catch (t: Throwable) {
            AtlasTtsDiagnostics.record(applicationContext, "speech failed after ${SystemClock.elapsedRealtime() - requestStartMs}ms: ${t.javaClass.simpleName}")
            log.e(t) { "Atlas buffered synthesis failed" }
            callback.error()
        }
    }

    /**
     * Experimental first-audio split. Only split at an existing sentence
     * boundary, preserving the exact spoken words and punctuation.
     * Never cut a word or create an artificial phrase.
     */
    private fun firstAudioSegments(text: String): List<String> {
        if (text.length < 70) return listOf(text)
        val boundary = Regex("[.!?]\\s+").findAll(text)
            .map { it.range.last + 1 }
            .firstOrNull { it in 18..65 && text.length - it >= 12 }
            ?: return listOf(text)
        return listOf(text.substring(0, boundary).trim(), text.substring(boundary).trim())
    }

    private fun runtimeOrNull(): SherpaTtsRuntime? = runCatching {
        SherpaTtsRuntime.get(applicationContext)
    }.getOrNull()

    private fun InstalledVoice.toFrameworkVoice(): Voice {
        val locale = parseLocale(languages.firstOrNull() ?: "en-US")
        return Voice(
            voiceId,
            locale,
            Voice.QUALITY_NORMAL,
            Voice.LATENCY_NORMAL,
            /* requiresNetworkConnection = */ false,
            /* features = */ emptySet(),
        )
    }

    private fun InstalledVoice.matchesLocale(bcp47: String): Boolean = languages.any {
        it.equals(bcp47, ignoreCase = true)
    }

    private fun InstalledVoice.matchesLanguage(lang: String?): Boolean {
        if (lang.isNullOrEmpty()) return false
        val target = parseLocale(lang).language
        if (target.isEmpty()) return false
        return languages.any { parseLocale(it).language.equals(target, ignoreCase = true) }
    }

    /**
     * Best-effort BCP-47 builder for the (lang, country, variant) triple the
     * framework hands us. The framework uses ISO-639 alpha-3 for lang and
     * ISO-3166 alpha-3 for country; we convert via [Locale] so the comparison
     * with the catalog's BCP-47 tags (alpha-2) works.
     */
    private fun buildBcp47(lang: String?, country: String?, variant: String?): String {
        if (lang.isNullOrEmpty()) return ""
        // Locale(String) does not accept ISO-639 alpha-3 reliably; build via Builder.
        val locale = runCatching {
            val builder = Locale.Builder()
            // Try alpha-2 first; if the input is alpha-3, Locale.forLanguageTag handles it.
            val candidate = Locale.forLanguageTag(lang)
            if (candidate.language.isEmpty()) {
                builder.setLanguage(lang.lowercase()).build()
            } else {
                candidate
            }
        }.getOrNull() ?: return ""
        val final = if (!country.isNullOrEmpty()) {
            runCatching {
                Locale.Builder()
                    .setLanguage(locale.language)
                    .setRegion(country)
                    .build()
            }.getOrDefault(locale)
        } else locale
        return final.toLanguageTag()
    }

    /**
     * Convert sherpa-onnx's float samples (-1..1) to little-endian 16-bit PCM
     * and stream them through [SynthesisCallback.audioAvailable] in chunks no
     * larger than [SynthesisCallback.maxBufferSize].
     */
    private fun streamPcm(callback: SynthesisCallback, samples: FloatArray): Boolean {
        val maxBufferBytes = callback.maxBufferSize.coerceAtLeast(BYTES_PER_SAMPLE * 64)
        val chunkBytes = (maxBufferBytes and 0x7FFFFFFE)
        val samplesPerChunk = chunkBytes / BYTES_PER_SAMPLE
        val buffer = ByteArray(chunkBytes)

        var sampleCursor = 0
        while (sampleCursor < samples.size && !stopRequested) {
            val remaining = samples.size - sampleCursor
            val take = minOf(samplesPerChunk, remaining)
            var writeIdx = 0
            for (i in 0 until take) {
                val f = samples[sampleCursor + i]
                val clamped = when {
                    f >= 1.0f -> Short.MAX_VALUE.toInt()
                    f <= -1.0f -> Short.MIN_VALUE.toInt()
                    else -> (f * Short.MAX_VALUE).toInt()
                }
                buffer[writeIdx] = (clamped and 0xFF).toByte()
                buffer[writeIdx + 1] = ((clamped shr 8) and 0xFF).toByte()
                writeIdx += 2
            }
            val deliveryStartMs = SystemClock.elapsedRealtime()
            val result = callback.audioAvailable(buffer, 0, writeIdx)
            val deliveryMs = SystemClock.elapsedRealtime() - deliveryStartMs
            if (sampleCursor == 0) {
                AtlasTtsDiagnostics.record(applicationContext, "first PCM callback returned in ${deliveryMs}ms; bytes=$writeIdx; audioSamples=${samples.size}")
            }
            if (result != TextToSpeech.SUCCESS) {
                AtlasTtsDiagnostics.record(applicationContext,
                    "PCM callback rejected chunk offset=$sampleCursor of ${samples.size} samples result=$result elapsed=${deliveryMs}ms stopRequested=$stopRequested")
                return false
            }
            sampleCursor += take
        }
        return !stopRequested
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MAX_TEXT_LENGTH = 2000
    }
}
