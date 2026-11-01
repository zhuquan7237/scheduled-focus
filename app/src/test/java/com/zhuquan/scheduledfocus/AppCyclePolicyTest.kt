package com.zhuquan.scheduledfocus

import org.junit.Assert.*
import org.junit.Test

class AppCyclePolicyTest {
    @Test fun customValuesDefineExactBoundaries() {
        val rule = AppCycleRule(7, 12)
        val started = AppCyclePolicy.evaluate(rule, WeChatCycle(), 1000, 1, true)
        assertEquals(421000L, started.cycle.endsAt)
        assertEquals(WeChatAccess.USING, AppCyclePolicy.evaluate(rule, started.cycle, 420999, 1, true).access)
        val rest = AppCyclePolicy.evaluate(rule, started.cycle, 421000, 1, true)
        assertEquals(WeChatAccess.COOLDOWN, rest.access)
        assertEquals(720000L, rest.remainingMs)
        assertEquals(WeChatAccess.COOLDOWN, AppCyclePolicy.evaluate(rule, started.cycle, 1140999, 1, true).access)
        assertEquals(WeChatAccess.READY, AppCyclePolicy.evaluate(rule, started.cycle, 1141000, 1, false).access)
    }
    @Test fun eligibleButUnopenedNeverStarts() {
        val rule = AppCycleRule(1, 30)
        val ready = AppCyclePolicy.evaluate(rule, WeChatCycle(60000, 1), 1860000, 1, false)
        assertEquals(WeChatCycle(), ready.cycle)
        assertEquals(WeChatCycle(), AppCyclePolicy.evaluate(rule, ready.cycle, 9000000, 1, false).cycle)
        assertEquals(9060000L, AppCyclePolicy.evaluate(rule, ready.cycle, 9000000, 1, true).cycle.endsAt)
    }
    @Test fun appsHaveIndependentDeadlinesAndReopensDoNotReset() {
        val a = AppCyclePolicy.evaluate(AppCycleRule(1, 2), WeChatCycle(), 100, 1, true)
        val b = AppCyclePolicy.evaluate(AppCycleRule(8, 3), WeChatCycle(), 1000, 1, true)
        assertEquals(WeChatAccess.COOLDOWN, AppCyclePolicy.evaluate(AppCycleRule(1, 2), a.cycle, 60100, 1, true).access)
        assertEquals(WeChatAccess.USING, AppCyclePolicy.evaluate(AppCycleRule(8, 3), b.cycle, 60100, 1, true).access)
        assertEquals(b.cycle, AppCyclePolicy.evaluate(AppCycleRule(8, 3), b.cycle, 90000, 1, true).cycle)
    }
    @Test fun rebootUsesConfiguredCooldown() {
        val state = AppCyclePolicy.evaluate(AppCycleRule(3, 17), WeChatCycle(999999, 1), 9000, 2, true)
        assertEquals(WeChatAccess.COOLDOWN, state.access)
        assertEquals(1020000L, state.remainingMs)
    }
    @Test fun callsStillDoNotStartOrExtendQuota() {
        val rule = AppCycleRule(5, 8)
        assertEquals(WeChatCycle(), AppCyclePolicy.evaluate(rule, WeChatCycle(), 100, 1, true, true).cycle)
        val active = WeChatCycle(301000, 1)
        assertEquals(active, AppCyclePolicy.evaluate(rule, active, 400000, 1, true, true).cycle)
        assertEquals(WeChatAccess.COOLDOWN, AppCyclePolicy.evaluate(rule, active, 400001, 1, true).access)
    }
    @Test fun minAndMaxAreSupported() {
        assertEquals(60000L, AppCycleRule(1, 1).useMs)
        assertEquals(10800000L, AppCycleRule(180, 180).restMs)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroUse() { AppCycleRule(0, 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsZeroRest() { AppCycleRule(2, 0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsExcessiveUse() { AppCycleRule(181, 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeRest() { AppCycleRule(2, -1) }
}
