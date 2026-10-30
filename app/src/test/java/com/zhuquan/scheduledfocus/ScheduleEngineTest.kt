package com.zhuquan.scheduledfocus

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleEngineTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun instant(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant()
    private fun settings(vararg rules: FocusRule) = FocusSettings(enabled = true, rules = rules.toList())
    private fun rule(start: Int, end: Int, days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) =
        FocusRule(title = "test", startMinute = start, endMinute = end, days = days)
    private fun state(settings: FocusSettings, value: String) = ScheduleEngine.evaluate(settings, instant(value), zone)

    @Test fun sampleScheduleStartsAtEightAndEndsAtNoon() {
        val settings = FocusSettings(enabled = true)
        assertFalse(state(settings, "2026-10-30T07:59:59").restricted)
        val start = state(settings, "2026-10-30T08:00:00")
        assertTrue(start.restricted)
        assertEquals(instant("2026-10-30T12:00:00"), start.currentEnd)
        val noon = state(settings, "2026-10-30T12:00:00")
        assertFalse(noon.restricted)
        assertEquals(instant("2026-10-30T14:00:00"), noon.nextTransition)
    }

    @Test fun sampleAfternoonAndEveningHaveCorrectBoundaries() {
        val settings = FocusSettings(enabled = true)
        for ((time, expected) in listOf("13:59:59" to false, "14:00:00" to true, "17:59:59" to true, "18:00:00" to false,
            "19:59:59" to false, "20:00:00" to true, "22:59:59" to true, "23:00:00" to false)) {
            assertEquals(time, expected, state(settings, "2026-10-30T$time").restricted)
        }
    }

    @Test fun overnightBelongsToStartDayEvenAtWeekBoundary() {
        val s = settings(rule(23 * 60, 60, setOf(DayOfWeek.SUNDAY)))
        assertTrue(state(s, "2026-11-02T00:30:00").restricted)
        assertFalse(state(s, "2026-11-02T01:00:00").restricted)
        assertFalse(state(s, "2026-11-03T00:30:00").restricted)
    }

    @Test fun overlapAndAdjacencyAreOneContinuousInterval() {
        val s = settings(rule(480, 600), rule(540, 660), rule(660, 720))
        assertEquals(instant("2026-10-30T12:00:00"), state(s, "2026-10-30T08:30:00").currentEnd)
        assertTrue(state(s, "2026-10-30T11:00:00").restricted)
    }

    @Test fun overnightAndFollowingDayMerge() {
        val s = settings(rule(1380, 60, setOf(DayOfWeek.FRIDAY)), rule(60, 180, setOf(DayOfWeek.SATURDAY)))
        assertEquals(instant("2026-10-31T03:00:00"), state(s, "2026-10-30T23:00:00").currentEnd)
    }

    @Test fun weeklySearchFindsNextWeek() {
        val s = settings(rule(480, 720, setOf(DayOfWeek.MONDAY)))
        assertEquals(instant("2026-11-02T08:00:00"), state(s, "2026-10-30T13:00:00").nextTransition)
    }

    @Test fun allWeekContinuousHasNoFakeUnlockTime() {
        val s = settings(rule(0, 720), rule(720, 0))
        val state = state(s, "2026-10-30T10:00:00")
        assertTrue(state.restricted)
        assertNull(state.currentEnd)
        assertNull(state.nextTransition)
    }

    @Test fun aWeeklyGapStillHasAnEnd() {
        val s = settings(rule(0, 720), rule(720, 0, DayOfWeek.entries.filterNot { it == DayOfWeek.SUNDAY }.toSet()))
        assertEquals(instant("2026-11-01T12:00:00"), state(s, "2026-10-30T10:00:00").currentEnd)
    }

    @Test fun disabledOrEmptyScheduleIsUnrestricted() {
        assertEquals(FocusState(false), state(FocusSettings(), "2026-10-30T10:00:00"))
        assertEquals(FocusState(false), state(settings(), "2026-10-30T10:00:00"))
    }

    @Test fun todayIncludesYesterdayOvernightButExcludesTomorrow() {
        val r = rule(1380, 60)
        val rows = ScheduleEngine.today(listOf(r), LocalDate.parse("2026-10-30"), zone)
        assertEquals(2, rows.size)
        assertEquals(instant("2026-10-29T23:00:00"), rows[0].span.start)
        assertEquals(instant("2026-10-30T23:00:00"), rows[1].span.start)
    }

    @Test fun midnightEndingDoesNotAppearOnNextDay() {
        val rows = ScheduleEngine.today(listOf(rule(1380, 0, setOf(DayOfWeek.THURSDAY))), LocalDate.parse("2026-10-30"), zone)
        assertTrue(rows.isEmpty())
    }

    @Test fun lockRejectsEverySettingsMutationEvenWithConfirmation() {
        val before = FocusSettings(enabled = true)
        val now = instant("2026-10-30T09:00:00")
        for (after in listOf(before.copy(enabled = false), before.copy(rules = emptyList()), before.copy(allowedPackages = setOf("bypass")))) {
            assertEquals(EditDecision.LOCKED, EditPolicy.decide(before, after, now, zone, true))
        }
    }

    @Test fun saveAtBoundaryRechecksCurrentLock() {
        val before = FocusSettings(enabled = true)
        val after = before.copy(enabled = false)
        assertEquals(EditDecision.SAVE, EditPolicy.decide(before, after, instant("2026-10-30T07:59:59"), zone, false))
        assertEquals(EditDecision.LOCKED, EditPolicy.decide(before, after, instant("2026-10-30T08:00:00"), zone, false))
    }

    @Test fun immediateActivationNeedsConfirmation() {
        val before = FocusSettings()
        val after = before.copy(enabled = true)
        val now = instant("2026-10-30T09:00:00")
        assertEquals(EditDecision.CONFIRM_IMMEDIATE, EditPolicy.decide(before, after, now, zone, false))
        assertEquals(EditDecision.SAVE, EditPolicy.decide(before, after, now, zone, true))
    }

    @Test fun editingIdleScheduleToCoverNowNeedsConfirmation() {
        val before = settings(rule(480, 720))
        val after = before.copy(rules = before.rules + rule(720, 840))
        assertEquals(EditDecision.CONFIRM_IMMEDIATE, EditPolicy.decide(before, after, instant("2026-10-30T12:30:00"), zone, false))
    }

    @Test fun lunchBreakEditTakesEffectToday() {
        val before = FocusSettings(enabled = true)
        val after = before.copy(rules = listOf(rule(900, 1080)))
        val now = instant("2026-10-30T12:30:00")
        assertEquals(EditDecision.SAVE, EditPolicy.decide(before, after, now, zone, false))
        assertEquals(instant("2026-10-30T15:00:00"), ScheduleEngine.evaluate(after, now, zone).nextTransition)
    }

    @Test fun timezoneChangeUsesLocalWallClock() {
        val now = instant("2026-10-30T08:00:00")
        val s = FocusSettings(enabled = true)
        assertTrue(ScheduleEngine.evaluate(s, now, zone).restricted)
        assertFalse(ScheduleEngine.evaluate(s, now, ZoneId.of("UTC")).restricted)
    }

    @Test fun daylightSavingSpringGapShiftsToValidLocalTime() {
        val z = ZoneId.of("America/New_York")
        val r = rule(150, 240)
        val span = ScheduleEngine.occurrence(r, LocalDate.parse("2026-03-08"), z)!!.span
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), span.start)
        assertEquals(Instant.parse("2026-03-08T08:00:00Z"), span.end)
    }

    @Test fun daylightSavingFallOverlapUsesEarlierOffset() {
        val z = ZoneId.of("America/New_York")
        val span = ScheduleEngine.occurrence(rule(90, 150), LocalDate.parse("2026-11-01"), z)!!.span
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), span.start)
        assertEquals(Instant.parse("2026-11-01T07:30:00Z"), span.end)
    }

    @Test fun clockJumpRecomputesInsteadOfKeepingStaleState() {
        val s = FocusSettings(enabled = true)
        assertTrue(state(s, "2026-10-30T09:00:00").restricted)
        assertFalse(state(s, "2026-10-30T12:30:00").restricted)
        assertTrue(state(s, "2026-10-30T09:00:00").restricted)
    }

    @Test(expected = IllegalArgumentException::class) fun equalEndpointsAreRejected() { rule(480, 480) }
    @Test(expected = IllegalArgumentException::class) fun noWeekdaysIsRejected() { rule(480, 720, emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun invalidMinuteIsRejected() { rule(-1, 720) }
}
