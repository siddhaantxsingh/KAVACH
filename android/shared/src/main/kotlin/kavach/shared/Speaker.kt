package kavach.shared

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Spoken warnings through the phone's own offline TTS voices (Google / Samsung engines ship
 * offline hi-IN and en-IN voices; install them once in Settings > Text-to-speech).
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile var ready = false
        private set
    @Volatile var hindiAvailable = false
        private set
    @Volatile private var speaking = false
    @Volatile private var lastSpokeAt = 0L

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { Log.e(TAG, "TTS init failed: $status"); return }
        hindiAvailable = tts.isLanguageAvailable(HINDI) >= TextToSpeech.LANG_AVAILABLE
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        tts.setSpeechRate(0.92f) // a little slower for elderly listeners
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { speaking = true; lastSpokeAt = SystemClock.elapsedRealtime() }
            override fun onDone(id: String?) { speaking = false; lastSpokeAt = SystemClock.elapsedRealtime() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false; lastSpokeAt = SystemClock.elapsedRealtime() }
        })
    }

    /**
     * True while a warning is being spoken and for [tailMs] afterwards. The Guardian's own
     * microphone hears its warnings; this lets it ignore them instead of reacting to its own
     * words ("police", "arrest", "1930" …).
     */
    fun isSpeakingOrJustSpoke(tailMs: Long = 1200): Boolean =
        speaking || SystemClock.elapsedRealtime() - lastSpokeAt < tailMs

    /** Interrupts anything being spoken: a STOP warning must never queue behind a CAUTION. */
    /** what was last spoken, and when (lets the listener drop an echo of it) */
    @Volatile var lastSpokenText: String = ""
        private set
    @Volatile var lastSpokenAt = 0L
        private set

    fun speak(text: String, lang: String, id: String = "kavach-${System.nanoTime()}") {
        if (!ready || text.isBlank()) return
        lastSpokenText = text; lastSpokenAt = SystemClock.elapsedRealtime()
        val loc = if (lang == "hi" && hindiAvailable) HINDI else ENGLISH_IN
        tts.language = loc
        speaking = true
        lastSpokeAt = SystemClock.elapsedRealtime()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() { if (ready) tts.stop() }

    fun shutdown() { tts.shutdown() }

    companion object {
        private const val TAG = "Speaker"
        val HINDI: Locale = Locale("hi", "IN")
        val ENGLISH_IN: Locale = Locale("en", "IN")
    }
}
