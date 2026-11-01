package kavach.guardian

import android.content.Context
import java.io.File

/**
 * Large models live outside the APK, in the app's external files dir, so the APK stays small
 * and models can be swapped for benchmarks without reinstalling:
 *   /sdcard/Android/data/io.kavach.guardian/files/models/
 *     asr-hi/model.int8.onnx, asr-hi/tokens.txt        Hindi/Hinglish streaming Zipformer2-CTC
 *     asr-en/{encoder,decoder,joiner}.onnx, tokens.txt  English streaming Zipformer transducer
 *     silero_vad.onnx
 *     llama/kavach_llama3_2_1b.pte, llama/tokenizer.model
 * scripts/push_models.sh copies them with adb.
 */
object ModelFiles {
    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "models").apply { mkdirs() }
    fun llamaPte(ctx: Context): File = File(dir(ctx), "llama/kavach_llama3_2_1b.pte")
    fun llamaTokenizer(ctx: Context): File = File(dir(ctx), "llama/tokenizer.model")
}
