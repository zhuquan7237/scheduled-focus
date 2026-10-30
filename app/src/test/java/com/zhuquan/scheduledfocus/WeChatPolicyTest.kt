package com.zhuquan.scheduledfocus

import org.junit.Assert.*
import org.junit.Test

class WeChatPolicyTest {
    private val boot = 8L
    @Test fun unopenedQuotaDoesNotStart() {
        assertEquals(WeChatAccess.READY, WeChatPolicy.evaluate(WeChatCycle(), 50_000, boot, false).access)
        assertEquals(WeChatCycle(), WeChatPolicy.evaluate(WeChatCycle(), 999_999, boot, false).cycle)
    }
    @Test fun firstOpenStartsExactlyTwoMinutes() {
        val start = WeChatPolicy.evaluate(WeChatCycle(), 1000, boot, true)
        assertEquals(121_000, start.cycle.endsAt)
        assertEquals(120_000, start.remainingMs)
        assertEquals(WeChatAccess.USING, start.access)
    }
    @Test fun leavingAndReopeningDoesNotPauseOrRestart() {
        val cycle = WeChatCycle(121_000, boot)
        assertEquals(61_000, WeChatPolicy.evaluate(cycle, 60_000, boot, false).remainingMs)
        assertEquals(cycle, WeChatPolicy.evaluate(cycle, 90_000, boot, true).cycle)
        assertEquals(31_000, WeChatPolicy.evaluate(cycle, 90_000, boot, true).remainingMs)
    }
    @Test fun exactEndBeginsFullFiveMinuteCooldown() {
        val state = WeChatPolicy.evaluate(WeChatCycle(121_000, boot), 121_000, boot, true)
        assertEquals(WeChatAccess.COOLDOWN, state.access)
        assertEquals(300_000, state.remainingMs)
    }
    @Test fun repeatedCooldownAttemptsDoNotChangeDeadline() {
        val cycle = WeChatCycle(121_000, boot)
        assertEquals(cycle, WeChatPolicy.evaluate(cycle, 200_000, boot, true).cycle)
        assertEquals(221_000, WeChatPolicy.evaluate(cycle, 200_000, boot, true).remainingMs)
        assertEquals(WeChatAccess.COOLDOWN, WeChatPolicy.evaluate(cycle, 420_999, boot, true).access)
    }
    @Test fun cooldownCompletionOnlyRestoresUnstartedEligibility() {
        val ready = WeChatPolicy.evaluate(WeChatCycle(121_000, boot), 421_000, boot, false)
        assertEquals(WeChatAccess.READY, ready.access)
        assertEquals(WeChatCycle(), ready.cycle)
        assertEquals(WeChatAccess.READY, WeChatPolicy.evaluate(ready.cycle, 999_999, boot, false).access)
        assertEquals(1_119_999, WeChatPolicy.evaluate(ready.cycle, 999_999, boot, true).cycle.endsAt)
    }
    @Test fun directOpenAfterCooldownStartsFreshQuota() {
        val state = WeChatPolicy.evaluate(WeChatCycle(121_000, boot), 421_000, boot, true)
        assertEquals(WeChatAccess.USING, state.access)
        assertEquals(120_000, state.remainingMs)
    }
    @Test fun callsDoNotSpendUnusedQuotaAndBypassCooldownOnlyInCallWindow() {
        val call = WeChatPolicy.evaluate(WeChatCycle(), 1000, boot, true, true)
        assertEquals(WeChatAccess.CALL, call.access)
        assertEquals(WeChatCycle(), call.cycle)
        val cycle = WeChatCycle(1000, boot)
        assertEquals(WeChatAccess.CALL, WeChatPolicy.evaluate(cycle, 5000, boot, true, true).access)
        assertEquals(WeChatAccess.COOLDOWN, WeChatPolicy.evaluate(cycle, 6000, boot, true).access)
    }
    @Test fun callsDoNotExtendAnAlreadyRunningQuota() {
        val cycle = WeChatCycle(121_000, boot)
        assertEquals(cycle, WeChatPolicy.evaluate(cycle, 100_000, boot, true, true).cycle)
        assertEquals(WeChatAccess.COOLDOWN, WeChatPolicy.evaluate(cycle, 125_000, boot, true).access)
    }
    @Test fun rebootRestoresCooldownRatherThanFreshQuota() {
        val restored = WeChatPolicy.evaluate(WeChatCycle(121_000, boot), 9000, boot + 1, true)
        assertEquals(WeChatAccess.COOLDOWN, restored.access)
        assertEquals(300_000, restored.remainingMs)
        assertEquals(WeChatAccess.COOLDOWN, WeChatPolicy.evaluate(restored.cycle, 10_000, boot + 1, true).access)
    }
    @Test fun onlyExactKnownCallWindowsAreExempt() {
        assertTrue(WeChatPolicy.isCallActivity("com.tencent.mm.plugin.voip.ui.VideoActivity"))
        for (name in listOf(null, "com.tencent.mm.ui.LauncherUI", "com.tencent.mm.plugin.voip.ui.SettingsUI", "VideoActivity")) assertFalse(WeChatPolicy.isCallActivity(name))
    }
}
