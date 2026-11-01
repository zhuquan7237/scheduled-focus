package com.zhuquan.scheduledfocus

/** Continuous 2-minute window from first foreground use, then 5 minutes of cooldown. */
data class WeChatCycle(val endsAt: Long = 0, val clockToken: Long = 0)
enum class WeChatAccess { READY, USING, COOLDOWN, CALL }
data class WeChatDecision(val cycle: WeChatCycle, val access: WeChatAccess, val remainingMs: Long = 0)

object WeChatPolicy {
    const val PACKAGE = "com.tencent.mm"
    const val USE_MS = 120_000L
    const val REST_MS = 300_000L
    fun evaluate(cycle: WeChatCycle, now: Long, clockToken: Long, opened: Boolean, call: Boolean = false): WeChatDecision {
        return AppCyclePolicy.evaluate(AppCycleRule(), cycle, now, clockToken, opened, call)
    }
    // Exact call activity names only. Never infer call status from chat text or a whole package prefix.
    fun isCallActivity(name: String?) = name in setOf(
        "com.tencent.mm.plugin.voip.ui.VideoActivity",
        "com.tencent.mm.plugin.voip.ui.VoipVoiceActivity",
    )
}
