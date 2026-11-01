package kavach.shared

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
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

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) { Log.e(TAG, "TTS init failed: $status"); return }
        hindiAvailable = tts.isLanguageAvailable(HINDI) >= TextToSpeech.LANG_AVAILABLE
        tts.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
        )
        tts.setSpeechRate(0.92f) // a little slower for elderly listeners
    }

    /** Interrupts anything being spoken: a STOP warning must never queue behind a CAUTION. */
    fun speak(text: String, lang: String, id: String = "kavach-${System.nanoTime()}") {
        if (!ready || text.isBlank()) return
        val loc = if (lang == "hi" && hindiAvailable) HINDI else ENGLISH_IN
        tts.language = loc
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
