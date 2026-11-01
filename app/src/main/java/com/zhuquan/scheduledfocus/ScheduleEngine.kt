package com.zhuquan.scheduledfocus

import java.time.*
import java.util.UUID

data class FocusRule(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val startMinute: Int,
    val endMinute: Int,
    val days: Set<DayOfWeek>,
) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439)
        require(startMinute != endMinute) { "开始和结束时间不能相同" }
        require(days.isNotEmpty()) { "至少选择一天" }
    }
}

data class FocusSettings(
    val enabled: Boolean = false,
    val onboardingStep: Int = 0,
    val rules: List<FocusRule> = defaults(),
    val allowedPackages: Set<String> = emptySet(),
    val warning: WarningStyle = WarningStyle(),
    val hideRecentTask: Boolean = true,
    val appCycles: Map<String, AppCycleRule> = mapOf(WeChatPolicy.PACKAGE to AppCycleRule()),
) {
    companion object {
        fun defaults() = listOf(
            FocusRule(id = "morning", title = "上午专注", startMinute = 480, endMinute = 720, days = DayOfWeek.entries.toSet()),
            FocusRule(id = "afternoon", title = "下午专注", startMinute = 840, endMinute = 1080, days = DayOfWeek.entries.toSet()),
            FocusRule(id = "evening", title = "晚间专注", startMinute = 1200, endMinute = 1380, days = DayOfWeek.entries.toSet()),
        )
    }
}

data class TimeSpan(val start: Instant, val end: Instant)
data class RuleOccurrence(val rule: FocusRule, val span: TimeSpan)
data class FocusState(
    val restricted: Boolean,
    // null while restricted means the repeating schedule has no free interval.
    val currentEnd: Instant? = null,
    val nextTransition: Instant? = null,
)

object ScheduleEngine {
    fun occurrence(rule: FocusRule, date: LocalDate, zone: ZoneId): RuleOccurrence? {
        if (date.dayOfWeek !in rule.days) return null
        val start = date.atTime(rule.startMinute / 60, rule.startMinute % 60).atZone(zone).toInstant()
        val endDate = if (rule.endMinute < rule.startMinute) date.plusDays(1) else date
        val end = endDate.atTime(rule.endMinute / 60, rule.endMinute % 60).atZone(zone).toInstant()
        return if (end > start) RuleOccurrence(rule, TimeSpan(start, end)) else null
    }

    fun merged(spans: List<TimeSpan>): List<TimeSpan> {
        val result = mutableListOf<TimeSpan>()
        for (span in spans.sortedBy { it.start }) {
            val previous = result.lastOrNull()
            if (previous != null && span.start <= previous.end) {
                result[result.lastIndex] = TimeSpan(previous.start, maxOf(previous.end, span.end))
            } else result += span
        }
        return result
    }

    fun evaluate(settings: FocusSettings, now: Instant, zone: ZoneId): FocusState {
        if (!settings.enabled || settings.rules.isEmpty()) return FocusState(false)
        val date = now.atZone(zone).toLocalDate()
        val spans = merged((-8L..15L).flatMap { offset ->
            settings.rules.mapNotNull { occurrence(it, date.plusDays(offset), zone)?.span }
        })
        val active = spans.firstOrNull { now >= it.start && now < it.end }
        if (active != null) {
            val end = active.end.takeIf { it < now.plus(Duration.ofDays(8)) }
            return FocusState(true, end, end)
        }
        return FocusState(false, nextTransition = spans.firstOrNull { it.start > now }?.start)
    }

    fun today(rules: List<FocusRule>, date: LocalDate, zone: ZoneId): List<RuleOccurrence> {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        return listOf(date.minusDays(1), date).flatMap { startDate ->
            rules.mapNotNull { occurrence(it, startDate, zone) }
        }.filter { it.span.start < dayEnd && it.span.end > dayStart }.sortedBy { it.span.start }
    }
}

enum class EditDecision { SAVE, LOCKED, CONFIRM_IMMEDIATE }
object EditPolicy {
    fun decide(before: FocusSettings, after: FocusSettings, now: Instant, zone: ZoneId, confirmed: Boolean): EditDecision {
        if (ScheduleEngine.evaluate(before, now, zone).restricted) return EditDecision.LOCKED
        if (!confirmed && ScheduleEngine.evaluate(after, now, zone).restricted) return EditDecision.CONFIRM_IMMEDIATE
        return EditDecision.SAVE
    }
}

fun minuteText(value: Int) = "%02d:%02d".format(value / 60, value % 60)
