package kavach.app

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import kavach.core.MiniJson
import kavach.core.obj2

/**
 * App Inspector v0 (Phase-1 preview): static analysis of installed / newly installed APKs.
 *
 * Banking-trojan and fake-KYC apps share a recognisable permission profile: SMS read +
 * accessibility service + overlay, installed outside an app store, with a bank/KYC/challan
 * name. v0 scores these signals with transparent rules; Phase 2 replaces the score with a
 * gradient-boosted model trained on public malware datasets (models/classifiers/README.md),
 * keeping these reasons as the explanation.
 */
class AppInspector(private val ctx: Context) {
    data class Result(val packageName: String, val label: String, val score: Double, val level: String, val reasons: List<String>, val remoteAccess: Boolean)

    private val lists = MiniJson.parse(ctx.assets.open("apps.json").use { it.readBytes().toString(Charsets.UTF_8) }).obj2()
    @Suppress("UNCHECKED_CAST") private val remote = (lists["remote_access_apps"] as List<String>).toSet()
    @Suppress("UNCHECKED_CAST") private val trustedInstallers = (lists["trusted_installers"] as List<String>).toSet()
    @Suppress("UNCHECKED_CAST") private val lure = lists["lure_words"] as List<String>
    @Suppress("UNCHECKED_CAST") val paymentApps = (lists["payment_apps"] as List<String>).toSet()
    val remoteAccessApps: Set<String> get() = remote

    private fun info(pkg: String): PackageInfo? = try {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        if (Build.VERSION.SDK_INT >= 33) ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(pkg, flags)
    } catch (_: Throwable) { null }

    private fun installer(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
    } catch (_: Throwable) { null }

    fun inspect(pkg: String): Result? {
        val pi = info(pkg) ?: return null
        val ai = pi.applicationInfo ?: return null
        if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 || pkg == ctx.packageName) return null
        val label = ctx.packageManager.getApplicationLabel(ai).toString()
        val perms = pi.requestedPermissions?.toSet() ?: emptySet()
        val svcPerms = pi.services?.mapNotNull { (it as ServiceInfo).permission }?.toSet() ?: emptySet()
        val reasons = ArrayList<String>()
        var score = 0.0
        fun add(w: Double, why: String) { score += w; reasons.add(why) }

        val sms = Manifest.permission.READ_SMS in perms || Manifest.permission.RECEIVE_SMS in perms
        val access = Manifest.permission.BIND_ACCESSIBILITY_SERVICE in svcPerms
        if (sms) add(2.0, "reads your SMS (can steal OTPs)")
        if (access) add(2.5, "asks for Accessibility control (can tap and read the screen)")
        if (Manifest.permission.SYSTEM_ALERT_WINDOW in perms) add(1.0, "can draw over other apps (fake login screens)")
        if (Manifest.permission.REQUEST_INSTALL_PACKAGES in perms) add(1.0, "can install other apps")
        if (Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE in svcPerms) add(1.0, "reads all notifications")
        if (Manifest.permission.READ_CALL_LOG in perms) add(1.0, "reads call history")
        if (Manifest.permission.READ_CONTACTS in perms) add(0.5, "reads contacts")
        if (Manifest.permission.SEND_SMS in perms) add(1.0, "can send SMS")
        if (sms && access) add(2.0, "SMS + Accessibility together: typical banking-trojan profile")
        val inst = installer(pkg)
        val sideloaded = inst == null || inst !in trustedInstallers
        if (sideloaded) add(1.5, "installed from outside an app store" + (inst?.let { " ($it)" } ?: ""))
        if (System.currentTimeMillis() - pi.firstInstallTime < 24 * 3600 * 1000L) add(0.5, "installed in the last 24 hours")
        val low = label.lowercase()
        if (sideloaded && lure.any { low.contains(it) }) add(2.5, "named like a bank / KYC / government app but not from an app store")
        val isRemote = pkg in remote
        if (isRemote) reasons.add("remote screen-access app: never keep it while someone on a call guides you")
        val level = when { score >= 6.0 -> "dangerous"; score >= 3.5 || isRemote -> "suspicious"; else -> "safe" }
        return Result(pkg, label, score, level, reasons, isRemote)
    }

    fun scanAll(): List<Result> {
        val pm = ctx.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        return apps.mapNotNull { inspect(it.packageName) }.filter { it.level != "safe" }.sortedByDescending { it.score }
    }
}
