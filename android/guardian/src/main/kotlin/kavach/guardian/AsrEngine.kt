package kavach.guardian

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Speaker-phone audio -> streaming ASR (sherpa-onnx, Arm CPU, int8) with Silero VAD gating.
 *
 * Hindi/Hinglish: AI4Bharat IndicConformer Hindi (120M, CTC, int8) when its files are on the phone
 *   (models/asr-hi-ic): Silero VAD cuts the audio into sentences and each sentence is recognised as
 *   a whole (far more accurate than the streaming model, docs/technical_report.md section 7.5; a
 *   partial result is refreshed every 2 s while someone is speaking). Otherwise the streaming
 *   Zipformer2-CTC model (models/asr-hi).
 * English: NVIDIA Parakeet TDT-CTC 110M (NeMo CTC, int8) sentence by sentence when its files are on
 *   the phone (models/asr-en-big); otherwise the streaming Zipformer transducer (models/asr-en).
 * Audio is processed in 100 ms chunks and never stored. VAD skips decoding during long
 * silence (the stream still receives the audio, so nothing is lost when speech resumes).
 */
class AsrEngine(
    private val modelsDir: File,
    private val lang: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (text: String, tSeconds: Double) -> Unit,
    /** placement check: speech-to-noise ratio of the last 5 s, every second (no audio kept) */
    private val onPlacement: ((snrDb: Double) -> Unit)? = null,
    /** OEM/system mode: the app holds CAPTURE_AUDIO_OUTPUT (privileged system app), so it may
     *  read the call audio itself (VOICE_CALL = both sides, VOICE_DOWNLINK = the caller). */
    private val callAudio: Boolean = false,
    /** true while KAVACH's own spoken warning is playing: that audio is replaced by silence so the
     *  recogniser never transcribes our own voice */
    private val isMuted: (() -> Boolean)? = null,
    /** "auto" only: chooses English or Hindi for every sentence (both big models must be on the phone) */
    private val langPick: kavach.core.LangPick? = null,
    /** "auto" only: the language chosen for each final sentence */
    private val onLang: ((String) -> Unit)? = null,
) {
    /** which microphone / call source is actually in use (shown on the Guardian screen) */
    @Volatile var source: String = "mic"
        private set
    /** AudioRecord session of the running recogniser (to ask Android whether it is silenced) */
    @Volatile var audioSession = 0
        private set
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile var rtf: Double? = null
        private set
    @Volatile var speechRatio: Double = 0.0
        private set
    private var t0 = 0L
    private val placement = kavach.core.AudioCheck.Live(SAMPLE_RATE)

    fun missingFiles(): List<String> = requiredFiles().filter { !it.exists() }.map { it.path }

    /** The bigger whole-sentence model for this language is on the phone: sentence-by-sentence recognition */
    val sentenceMode: Boolean get() = when (lang) {
        "hi" -> hasIndicConformer(modelsDir)
        "auto" -> autoReady(modelsDir)
        else -> hasEnglishBig(modelsDir)
    }

    private fun requiredFiles(): List<File> = if (lang == "auto")
        listOf(File(modelsDir, IC_MODEL), File(modelsDir, IC_TOKENS), File(modelsDir, EN_BIG_MODEL), File(modelsDir, EN_BIG_TOKENS), File(modelsDir, "silero_vad.onnx"))
    else if (sentenceMode && lang == "hi")
        listOf(File(modelsDir, IC_MODEL), File(modelsDir, IC_TOKENS), File(modelsDir, "silero_vad.onnx"))
    else if (sentenceMode)
        listOf(File(modelsDir, EN_BIG_MODEL), File(modelsDir, EN_BIG_TOKENS), File(modelsDir, "silero_vad.onnx"))
    else if (lang == "hi")
        listOf(File(modelsDir, "asr-hi/model.int8.onnx"), File(modelsDir, "asr-hi/tokens.txt"), File(modelsDir, "silero_vad.onnx"))
    else
        listOf(File(modelsDir, "asr-en/encoder.onnx"), File(modelsDir, "asr-en/decoder.onnx"), File(modelsDir, "asr-en/joiner.onnx"),
            File(modelsDir, "asr-en/tokens.txt"), File(modelsDir, "silero_vad.onnx"))

    private fun recognizer(): OnlineRecognizer = buildRecognizer(modelsDir, lang, 2)

    private fun vad(): Vad = Vad(assetManager = null, config = VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = File(modelsDir, "silero_vad.onnx").path, threshold = 0.5f,
            minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = VAD_WINDOW),
        sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu"))

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked by GuardianService before start()
    fun start() {
        if (running) return
        if (sentenceMode) { startSentenceMode(); return }
        running = true
        t0 = SystemClock.elapsedRealtime()
        thread = Thread({
            // ASR is steady, light work: keep it at normal priority so the scheduler can keep it on
            // efficiency cores; Llama bursts get the urgent priority (see LlamaAdvisor).
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val rec = recognizer()
            val vad = vad()
            var stream: OnlineStream = rec.createStream("")
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = openAudio(maxOf(minBuf, SAMPLE_RATE / 5 * 2)).also { audioSession = it.audioSessionId }
            val chunk = ShortArray(SAMPLE_RATE / 10)
            val vadBuf = FloatArray(VAD_WINDOW)
            var vadFill = 0
            var lastSpeechAt = 0.0
            var audioSeconds = 0.0
            var computeMs = 0L
            var speechChunks = 0; var chunks = 0
            var lastPartial = ""
            try {
                audio.startRecording()
                while (running) {
                    val n = audio.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val muted = isMuted?.invoke() == true
                    val samples = if (muted) FloatArray(n) else FloatArray(n) { chunk[it] / 32768f }
                    if (!muted) placement.add(samples)
                    audioSeconds += n.toDouble() / SAMPLE_RATE
                    val c0 = SystemClock.elapsedRealtime()
                    for (s in samples) {
                        vadBuf[vadFill++] = s
                        if (vadFill == VAD_WINDOW) {
                            vad.acceptWaveform(vadBuf.copyOf())
                            if (vad.isSpeechDetected()) lastSpeechAt = audioSeconds
                            while (!vad.empty()) vad.pop()
                            vadFill = 0
                        }
                    }
                    chunks++
                    val inSpeech = audioSeconds - lastSpeechAt < 1.5
                    if (inSpeech) speechChunks++
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    if (inSpeech) while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    if (text.isNotEmpty() && text != lastPartial) { lastPartial = text; onPartial(text) }
                    if (rec.isEndpoint(stream)) {
                        if (text.isNotEmpty()) onFinal(text, (SystemClock.elapsedRealtime() - t0) / 1000.0)
                        rec.reset(stream)
                        lastPartial = ""
                    }
                    computeMs += SystemClock.elapsedRealtime() - c0
                    if (chunks % 10 == 0 && placement.ready()) onPlacement?.invoke(placement.snrDb())
                    if (chunks % 50 == 0) { rtf = computeMs / 1000.0 / audioSeconds; speechRatio = speechChunks.toDouble() / chunks }
                }
                // flush the last words
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                rec.getResult(stream).text.trim().takeIf { it.isNotEmpty() }?.let { onFinal(it, (SystemClock.elapsedRealtime() - t0) / 1000.0) }
            } catch (t: Throwable) {
                Log.e(TAG, "ASR loop failed", t)
            } finally {
                try { audio.stop() } catch (_: Throwable) { }
                audio.release(); stream.release(); rec.release(); vad.release()
            }
        }, "kavach-asr").also { it.start() }
    }

    /** Hindi with IndicConformer: VAD sentences -> offline CTC recognition, one final result each. */
    @SuppressLint("MissingPermission")
    private fun startSentenceMode() {
        running = true
        t0 = SystemClock.elapsedRealtime()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val auto = lang == "auto"
            // Auto: both models stay loaded; every sentence is recognised by both and LangPick keeps one
            val rec = buildOffline(modelsDir, if (auto) "hi" else lang, 2)
            val recEn = if (auto) buildOffline(modelsDir, "en", 2) else null
            val vad = Vad(assetManager = null, config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = File(modelsDir, "silero_vad.onnx").path, threshold = 0.5f,
                    minSilenceDuration = 0.5f, minSpeechDuration = 0.25f, windowSize = VAD_WINDOW, maxSpeechDuration = 12.0f),
                sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu"))
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = openAudio(maxOf(minBuf, SAMPLE_RATE / 5 * 2)).also { audioSession = it.audioSessionId }
            val chunk = ShortArray(SAMPLE_RATE / 10)
            val vadBuf = FloatArray(VAD_WINDOW)
            var vadFill = 0
            var audioSeconds = 0.0
            var computeMs = 0L
            var chunks = 0; var speechChunks = 0
            // the sentence being spoken right now, for the 2-second partial refresh (at most 12 s)
            val current = FloatArray(SAMPLE_RATE * 12)
            var curLen = 0
            var lastPartialAt = 0.0
            fun decodeWith(r: OfflineRecognizer, samples: FloatArray, n: Int): String {
                val st = r.createStream()
                st.acceptWaveform(if (n == samples.size) samples else samples.copyOf(n), SAMPLE_RATE)
                r.decode(st)
                val text = r.getResult(st).text.trim()
                st.release()
                return text
            }
            var partialLang = "hi"
            /** final sentence: in Auto both models, LangPick chooses; reports the language */
            fun decodeFinal(samples: FloatArray, n: Int): String {
                if (recEn == null) return decodeWith(rec, samples, n)
                val hiText = decodeWith(rec, samples, n)
                val enText = decodeWith(recEn, samples, n)
                val l = langPick?.pick(enText, hiText) ?: "hi"
                partialLang = l
                val text = if (l == "en") enText else hiText
                if (text.isNotEmpty()) onLang?.invoke(l)
                return text
            }
            /** partial (every 2 s while speaking): only the language of the last sentence, to save work */
            fun decode(samples: FloatArray, n: Int): String =
                if (recEn != null && partialLang == "en") decodeWith(recEn, samples, n) else decodeWith(rec, samples, n)
            try {
                audio.startRecording()
                while (running) {
                    val n = audio.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val muted = isMuted?.invoke() == true
                    val samples = if (muted) FloatArray(n) else FloatArray(n) { chunk[it] / 32768f }
                    if (!muted) placement.add(samples)
                    audioSeconds += n.toDouble() / SAMPLE_RATE
                    val c0 = SystemClock.elapsedRealtime()
                    var speaking = false
                    for (x in samples) {
                        vadBuf[vadFill++] = x
                        if (vadFill == VAD_WINDOW) {
                            vad.acceptWaveform(vadBuf.copyOf())
                            vadFill = 0
                            if (vad.isSpeechDetected()) speaking = true
                            while (!vad.empty()) {
                                val seg = vad.front()
                                vad.pop()
                                val text = decodeFinal(seg.samples, seg.samples.size)
                                if (text.isNotEmpty()) onFinal(text, (SystemClock.elapsedRealtime() - t0) / 1000.0)
                                curLen = 0
                            }
                        }
                    }
                    chunks++
                    if (speaking) {
                        speechChunks++
                        val k = minOf(samples.size, current.size - curLen)
                        System.arraycopy(samples, 0, current, curLen, k); curLen += k
                        if (audioSeconds - lastPartialAt >= 2.0 && curLen >= SAMPLE_RATE) {
                            lastPartialAt = audioSeconds
                            decode(current, curLen).takeIf { it.isNotEmpty() }?.let { onPartial(it) }
                        }
                    }
                    computeMs += SystemClock.elapsedRealtime() - c0
                    if (chunks % 10 == 0 && placement.ready()) onPlacement?.invoke(placement.snrDb())
                    if (chunks % 50 == 0) { rtf = computeMs / 1000.0 / audioSeconds; speechRatio = speechChunks.toDouble() / chunks }
                }
                // flush the last sentence
                vad.flush()
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    decodeFinal(seg.samples, seg.samples.size).takeIf { it.isNotEmpty() }?.let { onFinal(it, (SystemClock.elapsedRealtime() - t0) / 1000.0) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "ASR (sentence mode) failed", t)
            } finally {
                try { audio.stop() } catch (_: Throwable) { }
                audio.release(); rec.release(); recEn?.release(); vad.release()
            }
        }, "kavach-asr").also { it.start() }
    }

    /** Call audio first in OEM/system mode (falls back if this phone's audio HAL refuses it);
     *  otherwise VOICE_RECOGNITION, which disables AGC/noise suppression that smear speaker-phone audio. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun openAudio(buf: Int): AudioRecord {
        val tries = if (callAudio) listOf(MediaRecorder.AudioSource.VOICE_CALL to "call (both sides)",
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "call (caller)", MediaRecorder.AudioSource.VOICE_RECOGNITION to "mic")
        else listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION to "mic")
        for ((src, name) in tries) {
            val a = try { AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf) } catch (t: Throwable) { null }
            if (a != null && a.state == AudioRecord.STATE_INITIALIZED) { source = name; return a }
            a?.release()
        }
        throw IllegalStateException("no audio source available")
    }

    /** Seconds since start(): the guardian's call clock. */
    fun now(): Double = (SystemClock.elapsedRealtime() - t0) / 1000.0

    fun stop() { running = false; thread?.join(1500); thread = null }

    companion object {
        private const val TAG = "AsrEngine"
        const val SAMPLE_RATE = 16000
        const val VAD_WINDOW = 512

        const val IC_MODEL = "asr-hi-ic/model.onnx"
        const val IC_TOKENS = "asr-hi-ic/tokens.txt"

        fun hasIndicConformer(modelsDir: File) = File(modelsDir, IC_MODEL).isFile && File(modelsDir, IC_TOKENS).isFile

        const val EN_BIG_MODEL = "asr-en-big/model.onnx"
        const val EN_BIG_TOKENS = "asr-en-big/tokens.txt"

        /** Auto language needs both whole-sentence models (Hindi IndicConformer + English Parakeet). */
        fun autoReady(modelsDir: File) = hasIndicConformer(modelsDir) && hasEnglishBig(modelsDir)

        fun hasEnglishBig(modelsDir: File) = File(modelsDir, EN_BIG_MODEL).isFile && File(modelsDir, EN_BIG_TOKENS).isFile

        /** Whole-sentence NeMo CTC model (also used by the benchmark): AI4Bharat IndicConformer for
         *  Hindi, NVIDIA Parakeet TDT-CTC 110M for English. */
        fun buildOffline(modelsDir: File, lang: String, threads: Int): OfflineRecognizer {
            val (m, tk) = if (lang == "hi") IC_MODEL to IC_TOKENS else EN_BIG_MODEL to EN_BIG_TOKENS
            return OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(nemo = OfflineNemoEncDecCtcModelConfig(model = File(modelsDir, m).path),
                    tokens = File(modelsDir, tk).path, numThreads = threads, provider = "cpu"),
                decodingMethod = "greedy_search"))
        }

        fun buildOfflineHindi(modelsDir: File, threads: Int): OfflineRecognizer = buildOffline(modelsDir, "hi", threads)

        /** The streaming recogniser for one language (also used by the performance benchmark). */
        fun buildRecognizer(modelsDir: File, lang: String, threads: Int): OnlineRecognizer {
            val model = if (lang == "hi") OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = File(modelsDir, "asr-hi/model.int8.onnx").path),
                tokens = File(modelsDir, "asr-hi/tokens.txt").path, numThreads = threads, provider = "cpu",
            ) else OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelsDir, "asr-en/encoder.onnx").path, decoder = File(modelsDir, "asr-en/decoder.onnx").path,
                    joiner = File(modelsDir, "asr-en/joiner.onnx").path,
                ),
                tokens = File(modelsDir, "asr-en/tokens.txt").path, numThreads = threads, provider = "cpu",
            )
            // Endpointing: a segment ends after 0.8 s of trailing silence, or after 12 s of speech,
            // so the guardian sees a final segment at least every few seconds on a monologue.
            val endpoint = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 0.8f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 12.0f),
            )
            val cfg = OnlineRecognizerConfig(featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80), modelConfig = model,
                endpointConfig = endpoint, enableEndpoint = true, decodingMethod = "greedy_search")
            return OnlineRecognizer(assetManager = null, config = cfg)
        }
    }
}
