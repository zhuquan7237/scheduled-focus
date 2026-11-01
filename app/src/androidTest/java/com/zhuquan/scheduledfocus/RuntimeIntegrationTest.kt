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
    private val blockedPackage get() = "${context.packageName}.test"
    private fun openBlockedApp() = shell("am start -W -n $blockedPackage/com.zhuquan.scheduledfocus.BlockedTestActivity")
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
        context.getSharedPreferences("wechat_cycle", Context.MODE_PRIVATE).edit().clear().commit()
        AppCycleRuntime(context).reset(blockedPackage)
        AppCycleRuntime(context).reset("com.google.android.deskclock")
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1200)
        await { foreground() != WeChatPolicy.PACKAGE }
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

    @Test fun perAppRulesPersistMigrateAndLock() {
        assertEquals(AppCycleRule(), store.read().appCycles[WeChatPolicy.PACKAGE])
        val rules = mapOf(blockedPackage to AppCycleRule(7, 12), "second.app" to AppCycleRule(1, 3))
        assertEquals(EditDecision.SAVE, store.change { it.copy(appCycles = rules) })
        assertEquals(rules, FocusStore(context).read().appCycles)
        assertFalse(WeChatPolicy.PACKAGE in store.read().appCycles)
        enableActiveTestSchedule()
        assertEquals(EditDecision.LOCKED, store.change { it.copy(appCycles = emptyMap()) })
        assertEquals(rules, store.read().appCycles)
    }

    @Test fun cycleChangesResetOnlyEditedAppAndLegacyWeChatStateSurvives() {
        val runtime = AppCycleRuntime(context)
        val rule = AppCycleRule(2, 5)
        store.change { it.copy(appCycles = it.appCycles + (blockedPackage to rule) + ("com.google.android.deskclock" to rule)) }
        val first = runtime.evaluate(blockedPackage, rule, true)
        val second = runtime.evaluate("com.google.android.deskclock", rule, true)
        store.change { it.copy(appCycles = it.appCycles + (blockedPackage to AppCycleRule(7, 12))) }
        assertEquals(WeChatAccess.READY, runtime.evaluate(blockedPackage, AppCycleRule(7, 12), false).access)
        assertEquals(second.cycle, AppCycleRuntime(context).evaluate("com.google.android.deskclock", rule, false).cycle)
        val now = SystemClock.elapsedRealtime()
        context.getSharedPreferences("wechat_cycle", 0).edit().clear().putLong("ends", now + 45000)
            .putLong("boot", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0).toLong()).commit()
        val migrated = runtime.evaluate(WeChatPolicy.PACKAGE, rule, false)
        assertEquals(WeChatAccess.USING, migrated.access)
        assertEquals(now + 45000, migrated.cycle.endsAt)
        assertTrue(first.cycle.endsAt > 0)
    }

    @Test fun appRuleEditorSavesCustomDurationsAndRejectsZero() {
        store.change { it.copy(onboardingStep = 4) }
        shell("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity")
        await { node("应用规则") != null }
        click("应用规则")
        await { editableNodes().size == 1 }
        setEditable(0, "com.google.android.deskclock")
        val label = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo("com.google.android.deskclock", 0)).toString()
        click(label)
        click("使用后冷却")
        await { editableNodes().size == 2 }
        setEditable(0, "0")
        shell("screencap -p /sdcard/focus-app-rule-invalid.png")
        await { !clickableNode(node("保存应用规则")!!).isEnabled }
        setEditable(0, "7")
        setEditable(1, "12")
        shell("screencap -p /sdcard/focus-app-rule-editor.png")
        click("保存应用规则")
        await { store.read().appCycles["com.google.android.deskclock"] == AppCycleRule(7, 12) }
        assertFalse("com.google.android.deskclock" in store.read().allowedPackages)
        shell("screencap -p /sdcard/focus-app-rules-list.png")
        click(label)
        click("始终允许（白名单）")
        click("保存应用规则")
        await { "com.google.android.deskclock" in store.read().allowedPackages }
        assertEquals("Whitelist override preserves the remembered cycle for later", AppCycleRule(7, 12), store.read().appCycles["com.google.android.deskclock"])
        click(label)
        click("完全限制")
        click("保存应用规则")
        await { "com.google.android.deskclock" !in store.read().allowedPackages && "com.google.android.deskclock" !in store.read().appCycles }
    }

    private fun editableNodes(): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable) nodes += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(automation.rootInActiveWindow)
        return nodes
    }
    private fun setEditable(index: Int, text: String) {
        assertTrue(editableNodes()[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
        SystemClock.sleep(350)
    }

    @Test fun independentAppsRespectCustomCyclesAcrossRuntimeRecreation() {
        val clock = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.google.android.deskclock").lineSequence().last { it.contains('/') }.trim()
        store.change { it.copy(appCycles = mapOf(blockedPackage to AppCycleRule(1, 1), "com.google.android.deskclock" to AppCycleRule(4, 3))) }
        enableActiveTestSchedule()
        openBlockedApp()
        await { FocusAccessibilityService.connected?.lastCheck?.contains("cycle=USING") == true }
        val a = AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false)
        shell("am start -W -n $clock")
        await { foreground() == "com.google.android.deskclock" }
        await { AppCycleRuntime(context).evaluate("com.google.android.deskclock", AppCycleRule(4, 3), false).access == WeChatAccess.USING }
        val b = AppCycleRuntime(context).evaluate("com.google.android.deskclock", AppCycleRule(4, 3), false)
        assertTrue(b.remainingMs > 230000)
        assertEquals(a.cycle, AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), true).cycle)
        seedAppCycleEnd(blockedPackage, SystemClock.elapsedRealtime() - 1000)
        openBlockedApp()
        await { foreground() != blockedPackage }
        assertEquals(WeChatAccess.COOLDOWN, AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access)
        SystemClock.sleep(1200)
        shell("am start -W -n $clock")
        await { foreground() == "com.google.android.deskclock" }
        SystemClock.sleep(1000)
        assertEquals("com.google.android.deskclock", foreground())
        seedAppCycleEnd(blockedPackage, SystemClock.elapsedRealtime() - 61000)
        assertEquals(WeChatAccess.READY, AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access)
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(1200)
        openBlockedApp()
        await { foreground() == blockedPackage && AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access == WeChatAccess.USING }
        assertTrue(AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).remainingMs > 55000)
    }

    @Test fun fullCustomOneMinuteUseAndOneMinuteCooldown() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("fullAppCycle") == "true")
        store.change { it.copy(appCycles = mapOf(blockedPackage to AppCycleRule(1, 1))) }
        enableActiveTestSchedule()
        openBlockedApp()
        await { AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access == WeChatAccess.USING }
        val cycle = AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).cycle
        while (SystemClock.elapsedRealtime() < cycle.endsAt + 1500) SystemClock.sleep(500)
        await { foreground() != blockedPackage }
        SystemClock.sleep(1200)
        openBlockedApp()
        await { foreground() != blockedPackage }
        while (SystemClock.elapsedRealtime() < cycle.endsAt + 61500) SystemClock.sleep(500)
        assertEquals(WeChatAccess.READY, AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access)
        SystemClock.sleep(2000)
        assertEquals(WeChatAccess.READY, AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access)
        openBlockedApp()
        await { AppCycleRuntime(context).evaluate(blockedPackage, AppCycleRule(1, 1), false).access == WeChatAccess.USING }
    }

    private fun seedAppCycleEnd(pkg: String, ends: Long) {
        context.getSharedPreferences("app_cycle_$pkg", 0).edit().putLong("ends", ends)
            .putLong("boot", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0).toLong()).commit()
    }

    @Test fun oldSettingsMigrateAndWarningStylePersists() {
        context.getSharedPreferences("focus_settings", 0).edit().putString("snapshot",
            "{\"enabled\":false,\"onboarding\":4,\"rules\":[],\"allowed\":[]}").commit()
        assertEquals(WarningStyle(), store.read().warning)
        assertTrue(store.read().hideRecentTask)
        val style = WarningStyle("记住你的目标", "一次专注，一次靠近", theme = 1, font = 2, size = 40, seconds = 9)
        assertEquals(EditDecision.SAVE, store.change { it.copy(warning = style, hideRecentTask = false) })
        assertEquals(style, FocusStore(context).read().warning)
        assertFalse(FocusStore(context).read().hideRecentTask)
    }

    @Test fun hiddenRecentTaskRemovalDoesNotDisconnectService() {
        enableActiveTestSchedule()
        shell("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity")
        await { foreground() == context.packageName }
        val tasks = context.getSystemService(android.app.ActivityManager::class.java).appTasks
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.any { it.taskInfo.baseIntent.flags and android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0 })
        tasks.forEach { it.finishAndRemoveTask() }
        SystemClock.sleep(1000)
        assertNotNull("Accessibility binding must survive removal of the UI task", FocusAccessibilityService.connected)
        openBlockedApp()
        await { foreground() != blockedPackage }
    }

    @Test fun customWarningAppearsInActualOverlay() {
        val headline = "去兑现你写下的目标。"
        store.change { it.copy(warning = WarningStyle(headline, "专注这一刻，为未来的自己负责。", theme = 1, size = 34, seconds = 10)) }
        enableActiveTestSchedule()
        openBlockedApp()
        await { foreground() != blockedPackage }
        await { FocusAccessibilityService.connected?.warningView != null }
        instrumentation.runOnMainSync {
            val view = FocusAccessibilityService.connected!!.warningView as android.view.ViewGroup
            assertTrue(view.isAttachedToWindow)
            assertTrue((0 until view.childCount).any { (view.getChildAt(it) as? android.widget.TextView)?.text?.toString() == headline })
        }
        shell("screencap -p /sdcard/focus-warning-custom.png")
    }

    @Test fun warningPresetCanBeSelectedAndSavedWithoutTyping() {
        store.change { it.copy(onboardingStep = 4) }
        shell("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity")
        await { foreground() == context.packageName }
        await { node("把时间留给重要的事") != null }
        for (attempt in 0..10) {
            if (node("自定义标语与警示样式")?.isVisibleToUser == true) break
            shell("input swipe 500 950 500 680 300")
            SystemClock.sleep(300)
        }
        click("自定义标语与警示样式")
        click("强警醒")
        shell("screencap -p /sdcard/focus-warning-presets.png")
        click("保存警示")
        await { FocusStore(context).read().warning == WarningPresets.all.first { it.name == "强警醒" }.style }
    }

    @Test fun weChatCallExemptionAndPersistentCooldown() {
        requireWeChatFixture()
        enableActiveTestSchedule()
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.plugin.voip.ui.VideoActivity")
        await { FocusAccessibilityService.connected?.lastCheck?.contains("wechat=CALL") == true }
        assertEquals(WeChatAccess.READY, WeChatRuntime(context).evaluate(false).access)
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { FocusAccessibilityService.connected?.lastCheck?.contains("wechat=USING") == true }
        val cycle = WeChatRuntime(context).evaluate(false)
        assertTrue(cycle.remainingMs in 115_000..120_000)
        assertEquals(cycle.cycle, WeChatRuntime(context).evaluate(true).cycle)
        seedWeChatEnd(SystemClock.elapsedRealtime() - 1000)
        await { foreground() != WeChatPolicy.PACKAGE }
        assertEquals(WeChatAccess.COOLDOWN, WeChatRuntime(context).evaluate(false).access)
        // Finish the HOME animation before simulating the next independent incoming call.
        SystemClock.sleep(1200)
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.plugin.voip.ui.VideoActivity")
        await { FocusAccessibilityService.connected?.lastCheck?.contains("wechat=CALL") == true }
        SystemClock.sleep(1000)
        assertEquals(WeChatPolicy.PACKAGE, foreground())
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { foreground() != WeChatPolicy.PACKAGE }
        seedWeChatEnd(SystemClock.elapsedRealtime() - WeChatPolicy.REST_MS - 1000)
        assertEquals(WeChatAccess.READY, WeChatRuntime(context).evaluate(false).access)
        SystemClock.sleep(1000)
        assertEquals(WeChatAccess.READY, WeChatRuntime(context).evaluate(false).access)
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { FocusAccessibilityService.connected?.lastCheck?.contains("wechat=USING") == true }
        assertTrue(WeChatRuntime(context).evaluate(false).remainingMs > 115_000)
    }

    @Test fun fullWeChatTwoPlusFiveMinuteCycle() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("fullWeChatCycle") == "true")
        requireWeChatFixture()
        enableActiveTestSchedule()
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { WeChatRuntime(context).evaluate(false).access == WeChatAccess.USING }
        val initial = WeChatRuntime(context).evaluate(false).cycle
        while (SystemClock.elapsedRealtime() < initial.endsAt + 1500) SystemClock.sleep(500)
        await { foreground() != WeChatPolicy.PACKAGE }
        assertEquals(WeChatAccess.COOLDOWN, WeChatRuntime(context).evaluate(false).access)
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { foreground() != WeChatPolicy.PACKAGE }
        while (SystemClock.elapsedRealtime() < initial.endsAt + WeChatPolicy.REST_MS + 1500) SystemClock.sleep(500)
        assertEquals(WeChatAccess.READY, WeChatRuntime(context).evaluate(false).access)
        SystemClock.sleep(3000)
        assertEquals(WeChatAccess.READY, WeChatRuntime(context).evaluate(false).access)
        shell("am start -W -f 0x14000000 -n com.tencent.mm/.ui.LauncherUI")
        await { FocusAccessibilityService.connected?.lastCheck?.contains("wechat=USING") == true }
        assertTrue(WeChatRuntime(context).evaluate(false).remainingMs > 115_000)
    }

    private fun requireWeChatFixture() {
        val info = runCatching { context.packageManager.getApplicationInfo(WeChatPolicy.PACKAGE, 0) }.getOrNull()
        Assume.assumeTrue("Install the explicit test-only fixture on the emulator first", info != null && info.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY != 0)
    }
    private fun seedWeChatEnd(end: Long) {
        context.getSharedPreferences("wechat_cycle", 0).edit().putLong("ends", end)
            .putLong("boot", android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0).toLong()).commit()
    }
    private fun enableActiveTestSchedule() {
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        store.change(true) { it.copy(enabled = true, onboardingStep = 4, rules = listOf(FocusRule(
            title = "runtime test", startMinute = (minute + 1439) % 1440, endMinute = (minute + 30) % 1440, days = DayOfWeek.entries.toSet()))) }
        shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        shell("settings put secure enabled_accessibility_services ${context.packageName}/.FocusAccessibilityService")
        shell("settings put secure accessibility_enabled 1")
        await(10000) { FocusAccessibilityService.connected != null }
    }

    @Test fun onboardingEditorAndWhitelistHaveWorkingNavigation() {
        shell("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity")
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
    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo = if (node.isClickable || !node.isEnabled || node.parent == null) node else clickableNode(node.parent)
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
        assertEquals(EditDecision.LOCKED, store.change(true) { it.copy(warning = WarningStyle(headline = "修改")) })
        assertEquals(EditDecision.LOCKED, store.change(true) { it.copy(hideRecentTask = false) })
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

        openBlockedApp()
        await { foreground() == blockedPackage }
        waitUntil(start.toInstant().plusSeconds(3))
        await { foreground() != blockedPackage }
        assertTrue(ScheduleEngine.evaluate(store.read(), Instant.now(), zone).restricted)

        openBlockedApp()
        await { foreground() != blockedPackage }
        // Let HOME's transition finish before launching the next task, like a launcher tap.
        SystemClock.sleep(1000)
        shell("am start -W -n $clockLauncher")
        await { foreground() == clockPkg }
        SystemClock.sleep(1500)
        assertEquals(clockPkg, foreground())
        shell("am start -W -a android.settings.SETTINGS")
        await { foreground() == "com.android.settings" }
        SystemClock.sleep(1000)
        assertEquals("System permission management must remain accessible", "com.android.settings", foreground())
        assertEquals(EditDecision.LOCKED, store.change { it.copy(enabled = false) })

        shell("am start -W -f 0x10008000 -n ${context.packageName}/.MainActivity")
        await { foreground() == context.packageName }
        SystemClock.sleep(1500)
        assertEquals(context.packageName, foreground())
        waitUntil(end.toInstant().plusSeconds(3))
        openBlockedApp()
        await { foreground() == blockedPackage }
        assertFalse(ScheduleEngine.evaluate(store.read(), Instant.now(), zone).restricted)
        assertEquals(EditDecision.SAVE, store.change { it.copy(enabled = false) })

        val others = previousServices.split(':').filter { it.isNotBlank() && !it.startsWith("${context.packageName}/") }.joinToString(":")
        if (others.isBlank()) shell("settings delete secure enabled_accessibility_services")
        else shell("settings put secure enabled_accessibility_services $others")
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
        openBlockedApp()
        await { foreground() != blockedPackage }
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
