package com.zhuquan.scheduledfocus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** One persisted snapshot: settings never become partially updated. All writes recheck the lock. */
class FocusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("focus_settings", Context.MODE_PRIVATE)

    fun read(): FocusSettings {
        val raw = prefs.getString("snapshot", null) ?: return FocusSettings()
        val json = JSONObject(raw)
        val rules = json.getJSONArray("rules")
        val allowed = json.getJSONArray("allowed")
        return FocusSettings(
            enabled = json.getBoolean("enabled"),
            onboardingStep = json.optInt("onboarding", 0),
            rules = (0 until rules.length()).map { index ->
                val row = rules.getJSONObject(index)
                val days = row.getJSONArray("days")
                FocusRule(row.getString("id"), row.getString("title"), row.getInt("start"), row.getInt("end"),
                    (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet())
            },
            allowedPackages = (0 until allowed.length()).map { allowed.getString(it) }.toSet(),
        )
    }

    fun change(confirmed: Boolean = false, update: (FocusSettings) -> FocusSettings): EditDecision = synchronized(lock) {
        val before = read()
        val after = update(before)
        val decision = EditPolicy.decide(before, after, Instant.now(), ZoneId.systemDefault(), confirmed)
        if (decision == EditDecision.SAVE) {
            val rules = JSONArray()
            after.rules.forEach { rule ->
                rules.put(JSONObject().put("id", rule.id).put("title", rule.title)
                    .put("start", rule.startMinute).put("end", rule.endMinute)
                    .put("days", JSONArray(rule.days.map { it.value }.sorted())))
            }
            val snapshot = JSONObject().put("enabled", after.enabled).put("onboarding", after.onboardingStep)
                .put("rules", rules).put("allowed", JSONArray(after.allowedPackages.sorted()))
            check(prefs.edit().putString("snapshot", snapshot.toString()).commit()) { "保存失败，请重试" }
        }
        decision
    }

    companion object { private val lock = Any() }
}
