package com.zhuquan.scheduledfocus

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

class WeChatRuntime(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wechat_cycle", Context.MODE_PRIVATE)
    private val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0).toLong()
    fun evaluate(opened: Boolean, call: Boolean = false): WeChatDecision = synchronized(lock) {
        val before = WeChatCycle(prefs.getLong("ends", 0), prefs.getLong("boot", boot))
        val result = WeChatPolicy.evaluate(before, SystemClock.elapsedRealtime(), boot, opened, call)
        if (result.cycle != before) check(prefs.edit().putLong("ends", result.cycle.endsAt).putLong("boot", result.cycle.clockToken).commit())
        result
    }
    companion object { private val lock = Any() }
}

fun durationText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
