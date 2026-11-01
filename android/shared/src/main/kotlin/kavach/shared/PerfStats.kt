package kavach.shared

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import java.io.File

/**
 * Numbers for the one-tap performance overlay and for bench logs: per-core clock, which
 * core each KAVACH thread last ran on (big vs LITTLE), memory, battery and thermal state.
 * Everything here is readable by a normal app; sysfs files that a vendor locks down are
 * simply reported as unavailable.
 */
object PerfStats {
    data class Snapshot(
        val coreFreqMHz: List<Int?>, val coreMaxMHz: List<Int?>, val threadCore: Map<String, Int>,
        val pssMB: Int, val nativeHeapMB: Int, val availMemMB: Long,
        val batteryPct: Int, val batteryTempC: Double, val currentNowMA: Int?, val energyCounterNWh: Long?,
        val thermalStatus: Int?, val charging: Boolean,
    ) {
        fun overlayText(llm: LlamaAdvisor.Stats?, asrRtf: Double?): String = buildString {
            append("CPU MHz: ").append(coreFreqMHz.mapIndexed { i, f -> "c$i:${f ?: "-"}" }.joinToString(" ")).append('\n')
            if (threadCore.isNotEmpty()) append("threads→core: ").append(threadCore.entries.joinToString(" ") { "${it.key}@c${it.value}" }).append('\n')
            append("RAM: PSS ${pssMB} MB, native ${nativeHeapMB} MB, free ${availMemMB} MB\n")
            append("Battery ${batteryPct}%  ${"%.1f".format(batteryTempC)}°C  ")
            currentNowMA?.let { append("${it} mA  ") }
            thermalStatus?.let { append("thermal=$it") }
            append('\n')
            llm?.let {
                append("Llama: TTFT ${it.ttftMs} ms, prefill ${"%.0f".format(it.prefillTokPerS)} tok/s, decode ${"%.1f".format(it.decodeTokPerS)} tok/s, ")
                append("${it.promptTokens}+${it.generatedTokens} tok, warm=${it.usedWarmPrefix}\n")
            }
            asrRtf?.let { append("ASR real-time factor ${"%.2f".format(it)}\n") }
        }
    }

    private fun readInt(path: String): Int? = try { File(path).readText().trim().toInt() } catch (_: Throwable) { null }

    fun cores(): Int = Runtime.getRuntime().availableProcessors()

    /** Core each named thread of this process last ran on (/proc/self/task/<tid>/stat field 39). */
    fun threadPlacement(prefix: String = "kavach"): Map<String, Int> {
        val out = HashMap<String, Int>()
        File("/proc/self/task").listFiles()?.forEach { t ->
            try {
                val stat = File(t, "stat").readText()
                val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                if (!name.startsWith(prefix)) return@forEach
                val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                out[name] = fields[36].toInt() // field 39 overall; 3 fields precede the split point
            } catch (_: Throwable) { }
        }
        return out
    }

    fun snapshot(context: Context): Snapshot {
        val n = cores()
        val freqs = (0 until n).map { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.div(1000) }
        val maxes = (0 until n).map { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq")?.div(1000) }
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ami = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val plugged = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).takeIf { it != Int.MIN_VALUE }?.let { it / 1000 }
        val energy = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER).takeIf { it != Long.MIN_VALUE && it > 0 }
        val thermal = if (Build.VERSION.SDK_INT >= 29) (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else null
        return Snapshot(freqs, maxes, threadPlacement(), mi.totalPss / 1024, (Debug.getNativeHeapAllocatedSize() / (1024 * 1024)).toInt(),
            ami.availMem / (1024 * 1024), bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), temp, cur, energy, thermal, plugged)
    }
}
