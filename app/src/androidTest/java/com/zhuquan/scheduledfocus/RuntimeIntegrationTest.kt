package com.zhuquan.scheduledfocus

import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** Destructive test setup is confined to this test app's prefs and an Android emulator. */
class RuntimeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = FocusStore(context)
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var previousServices = ""
    private var previousAccessibility = "0"

    @Before fun setup() {
        Assume.assumeTrue("Only run automatic system-setting changes on an emulator", Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        previousServices = shell("settings get secure enabled_accessibility_services").trim().let { if (it == "null") "" else it }
        previousAccessibility = shell("settings get secure accessibility_enabled").trim().let { if (it == "1") "1" else "0" }
        // A reinstalled package can leave a stale enabled component in system settings.
        // Force a real disable/enable edge before testing a fresh binding.
        val others = previousServices.split(':').filter { it.isNotBlank() && !it.startsWith("${context.packageName}/") }.joinToString(":")
        if (others.isBlank()) {
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure accessibility_enabled 0")
        } else shell("settings put secure enabled_accessibility_services $others")
        await { FocusAccessibilityService.connected == null }
        context.getSharedPreferences("focus_settings", Context.MODE_PRIVATE).edit().clear().commit()
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After fun cleanup() {
        if (!(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))) return
        context.getSharedPreferences("focus_settings", Context.MODE_PRIVATE).edit().clear().commit()
        restoreServices()
        shell("settings put secure accessibility_enabled $previousAccessibility")
        RuntimeSupport.refresh(context)
    }

    @Test fun snapshotPersistsRulesWeekdaysAndWhitelist() {
        val rule = FocusRule(title = "夜读", startMinute = 1380, endMinute = 60, days = setOf(DayOfWeek.FRIDAY))
        val desired = FocusSettings(rules = listOf(rule), allowedPackages = setOf("one", "two"), onboardingStep = 2)
        assertEquals(EditDecision.SAVE, store.change { desired })
        assertEquals(desired, FocusStore(context).read())
    }

    @Test fun onboardingEditorAndWhitelistHaveWorkingNavigation() {
        shell("am start -W -n ${context.packageName}/.MainActivity")
        await { node("你的每周时间表") != null }
        click("编辑")
        await { node("设置专注时段") != null }
        click("取消")
        click("下一步")
        await { node("只留下必要应用") != null }
        shell("input keyevent KEYCODE_BACK")
        await { node("你的每周时间表") != null }
        click("下一步")
        click("下一步")
        await { node("让限制准时发生") != null }
        assertFalse("Missing permissions must disable progression", clickableNode(node("下一步")!!).isEnabled)
        assertEquals(2, store.read().onboardingStep)
    }

    private fun node(text: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString() == text) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(automation.rootInActiveWindow)
    }
    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo = if (node.isClickable || node.parent == null) node else clickableNode(node.parent)
    private fun click(text: String) {
        await { node(text) != null }
        assertTrue(clickableNode(node(text)!!).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350)
    }

    @Test fun blockedChangesCannotReachDisk() {
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        val active = FocusSettings(enabled = true, onboardingStep = 4, rules = listOf(FocusRule(
            title = "test lock", startMinute = (minute + 1439) % 1440, endMinute = (minute + 3) % 1440, days = DayOfWeek.entries.toSet())))
        assertEquals(EditDecision.CONFIRM_IMMEDIATE, store.change { active })
        assertFalse(store.read().enabled)
        assertEquals(EditDecision.SAVE, store.change(true) { active })
        assertEquals(EditDecision.LOCKED, store.change(true) { it.copy(enabled = false) })
        assertEquals(EditDecision.LOCKED, store.change(true) { it.copy(allowedPackages = setOf("bypass")) })
        assertEquals(EditDecision.LOCKED, store.change(true) { it.copy(rules = emptyList()) })
        assertEquals(active, store.read())
    }

    @Test fun serviceBlocksAtBoundaryAllowsWhitelistAndRestoresAtEnd() {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val start = now.plusMinutes(if (now.second > 40) 2 else 1).withSecond(0).withNano(0)
        val end = start.plusMinutes(1)
        val clockComponent = shell("cmd package resolve-activity --brief -a android.intent.action.SHOW_ALARMS").lineSequence().last { it.contains('/') }.trim()
        val clockPkg = clockComponent.substringBefore('/')
        val clockLauncher = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $clockPkg").lineSequence().last { it.contains('/') }.trim()
        val rule = FocusRule(title = "boundary test", startMinute = start.hour * 60 + start.minute,
            endMinute = end.hour * 60 + end.minute, days = setOf(start.dayOfWeek))
        assertEquals(EditDecision.SAVE, store.change { FocusSettings(enabled = true, onboardingStep = 4, rules = listOf(rule), allowedPackages = setOf(clockPkg)) })
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        val component = "${context.packageName}/.FocusAccessibilityService"
        val services = (previousServices.split(':').filter { it.isNotBlank() } + component).distinct().joinToString(":")
        shell("settings put secure enabled_accessibility_services $services")
        shell("settings put secure accessibility_enabled 1")
        await(10000) { FocusAccessibilityService.connected != null }
        assertTrue(RuntimeSupport.status(context).ready)
        RuntimeSupport.refresh(context)

        shell("am start -W -a android.settings.SETTINGS")
        await { foreground() == "com.android.settings" }
        waitUntil(start.toInstant().plusSeconds(3))
        await { foreground() != "com.android.settings" }
        assertTrue(ScheduleEngine.evaluate(store.read(), Instant.now(), zone).restricted)

        shell("am start -W -a android.settings.SETTINGS")
        await { foreground() != "com.android.settings" }
        // Let HOME's transition finish before launching the next task, like a launcher tap.
        SystemClock.sleep(1000)
        shell("am start -W -n $clockLauncher")
        await { foreground() == clockPkg }
        SystemClock.sleep(1500)
        assertEquals(clockPkg, foreground())
        assertEquals(EditDecision.LOCKED, store.change { it.copy(enabled = false) })

        shell("am start -W -n ${context.packageName}/.MainActivity")
        await { foreground() == context.packageName }
        SystemClock.sleep(1500)
        assertEquals(context.packageName, foreground())
        waitUntil(end.toInstant().plusSeconds(3))
        shell("am start -W -a android.settings.SETTINGS")
        await { foreground() == "com.android.settings" }
        assertFalse(ScheduleEngine.evaluate(store.read(), Instant.now(), zone).restricted)
        assertEquals(EditDecision.SAVE, store.change { it.copy(enabled = false) })

        restoreServices()
        await { FocusAccessibilityService.connected == null }
        assertFalse(RuntimeSupport.status(context).ready)
    }

    @Test fun alreadyActiveScheduleInterceptsForegroundApp() {
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        store.change(true) { FocusSettings(enabled = true, onboardingStep = 4, rules = listOf(FocusRule(
            title = "immediate test", startMinute = (minute + 1439) % 1440, endMinute = (minute + 5) % 1440, days = DayOfWeek.entries.toSet()))) }
        shell("settings put secure enabled_accessibility_services ${context.packageName}/.FocusAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        await(10000) { FocusAccessibilityService.connected != null }
        shell("am start -W -a android.settings.SETTINGS")
        await { foreground() != "com.android.settings" }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    // UiAutomation executes tokenized arguments, not a shell: quotes would become literal setting data.
    private fun restoreServices() {
        if (previousServices.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $previousServices")
    }
    private fun foreground(): String? = Regex("(?:topResumedActivity|mResumedActivity)[^\n]*? u[0-9]+ ([A-Za-z0-9_.]+)/")
        .find(shell("dumpsys activity activities"))?.groupValues?.get(1)
    private fun waitUntil(target: Instant) {
        while (Instant.now() < target) SystemClock.sleep(minOf(500, Duration.between(Instant.now(), target).toMillis().coerceAtLeast(1)))
    }
    private fun await(timeout: Long = 6000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(150) }
        assertTrue("Condition not met within $timeout ms; foreground=${foreground()}; service=${FocusAccessibilityService.connected?.lastCheck}; state=${ScheduleEngine.evaluate(store.read(), Instant.now(), ZoneId.systemDefault())}", condition())
    }
}
