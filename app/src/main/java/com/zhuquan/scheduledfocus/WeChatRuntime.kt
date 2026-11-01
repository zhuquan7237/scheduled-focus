package com.zhuquan.scheduledfocus

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

class WeChatRuntime(context: Context) {
    private val cycles = AppCycleRuntime(context)
    fun evaluate(opened: Boolean, call: Boolean = false) = cycles.evaluate(WeChatPolicy.PACKAGE, AppCycleRule(), opened, call)
}

class AppCycleRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong()
    fun evaluate(pkg: String, rule: AppCycleRule, opened: Boolean, call: Boolean = false): WeChatDecision = synchronized(lock) {
        // Keep the existing WeChat file so upgrades preserve an active use window or cooldown.
        val prefs = appContext.getSharedPreferences(if (pkg == WeChatPolicy.PACKAGE) "wechat_cycle" else "app_cycle_$pkg", Context.MODE_PRIVATE)
        val signature = "${rule.useMinutes}/${rule.restMinutes}"
        val changed = prefs.contains("rule") && prefs.getString("rule", null) != signature
        val before = WeChatCycle(prefs.getLong("ends", 0), prefs.getLong("boot", boot))
        val result = AppCyclePolicy.evaluate(rule, if (changed) WeChatCycle() else before, SystemClock.elapsedRealtime(), boot, opened, call)
        if (result.cycle != before || prefs.getString("rule", null) != signature)
            check(prefs.edit().putLong("ends", result.cycle.endsAt).putLong("boot", result.cycle.clockToken).putString("rule", signature).commit())
        result
    }
    fun reset(pkg: String) = synchronized(lock) {
        check(appContext.getSharedPreferences(if (pkg == WeChatPolicy.PACKAGE) "wechat_cycle" else "app_cycle_$pkg", Context.MODE_PRIVATE).edit().clear().commit())
    }
    companion object { private val lock = Any() }
}

fun durationText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
