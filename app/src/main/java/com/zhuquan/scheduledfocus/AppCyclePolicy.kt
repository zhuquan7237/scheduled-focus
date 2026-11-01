package com.zhuquan.scheduledfocus

/** Each app has an independent continuous use window and cooldown. */
data class AppCycleRule(val useMinutes: Int = 2, val restMinutes: Int = 5) {
    init { require(useMinutes in 1..180 && restMinutes in 1..180) }
    val useMs get() = useMinutes * 60_000L
    val restMs get() = restMinutes * 60_000L
    val summary get() = "使用 $useMinutes 分钟 · 冷却 $restMinutes 分钟"
}

object AppCyclePolicy {
    fun evaluate(rule: AppCycleRule, cycle: WeChatCycle, now: Long, clockToken: Long, opened: Boolean, call: Boolean = false): WeChatDecision {
        val restored = if (cycle.endsAt > 0 && cycle.clockToken != clockToken) WeChatCycle(now, clockToken) else cycle
        if (call) return WeChatDecision(restored, WeChatAccess.CALL)
        if (restored.endsAt > now) return WeChatDecision(restored, WeChatAccess.USING, restored.endsAt - now)
        if (restored.endsAt > 0 && now < restored.endsAt + rule.restMs)
            return WeChatDecision(restored, WeChatAccess.COOLDOWN, restored.endsAt + rule.restMs - now)
        if (!opened) return WeChatDecision(WeChatCycle(), WeChatAccess.READY)
        return WeChatDecision(WeChatCycle(now + rule.useMs, clockToken), WeChatAccess.USING, rule.useMs)
    }
}
