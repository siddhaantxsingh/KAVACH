package kavach.shared

import android.content.Context
import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kavach.core.AssetSource
import kavach.core.KavachCore
import java.util.concurrent.Executors

/** Shared assets (lexicon, playbook, domain lists, linear models) packaged from the repo's assets/ folder. */
class AndroidAssets(private val am: AssetManager) : AssetSource {
    override fun text(name: String): String = am.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    override fun bytes(name: String): ByteArray = am.open(name).use { it.readBytes() }
}

/** Process-wide singleton holding the detection core. Loading takes ~150 ms; never on the main thread. */
object KavachRuntime {
    private const val TAG = "KavachRuntime"
    @Volatile var core: KavachCore? = null
        private set
    @Volatile var loadMillis: Long = 0
        private set
    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "kavach-load") }
    private val main = Handler(Looper.getMainLooper())

    fun ensureLoaded(context: Context, onReady: (KavachCore) -> Unit) {
        core?.let { c -> main.post { onReady(c) }; return }
        val app = context.applicationContext
        loader.execute {
            val c = core ?: run {
                val t0 = System.currentTimeMillis()
                val loaded = KavachCore(AndroidAssets(app.assets))
                loadMillis = System.currentTimeMillis() - t0
                Log.i(TAG, "core loaded in $loadMillis ms")
                core = loaded
                loaded
            }
            main.post { onReady(c) }
        }
    }
}
