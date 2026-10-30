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
        // A reboot cannot safely reuse elapsedRealtime. Restore conservatively with a cooldown.
        val restored = if (cycle.endsAt > 0 && cycle.clockToken != clockToken) WeChatCycle(now, clockToken) else cycle
        if (call) return WeChatDecision(restored, WeChatAccess.CALL)
        if (restored.endsAt > now) return WeChatDecision(restored, WeChatAccess.USING, restored.endsAt - now)
        if (restored.endsAt > 0 && now < restored.endsAt + REST_MS)
            return WeChatDecision(restored, WeChatAccess.COOLDOWN, restored.endsAt + REST_MS - now)
        if (!opened) return WeChatDecision(WeChatCycle(), WeChatAccess.READY)
        val next = WeChatCycle(now + USE_MS, clockToken)
        return WeChatDecision(next, WeChatAccess.USING, USE_MS)
    }
    // Exact call activity names only. Never infer call status from chat text or a whole package prefix.
    fun isCallActivity(name: String?) = name in setOf(
        "com.tencent.mm.plugin.voip.ui.VideoActivity",
        "com.tencent.mm.plugin.voip.ui.VoipVoiceActivity",
    )
}
