package com.zhuquan.scheduledfocus

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF173D36)
private val Lime = Color(0xFFDDF5AA)
private val Paper = Color(0xFFF5F6F0)
private val Muted = Color(0xFF64736D)
private val DayNames = listOf("一", "二", "三", "四", "五", "六", "日")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyRecentVisibility()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        RuntimeSupport.refresh(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Ink, onPrimary = Color.White,
                primaryContainer = Lime, onPrimaryContainer = Ink, background = Paper, surface = Color.White,
                onSurface = Ink, onBackground = Ink, secondary = Muted,
                secondaryContainer = Lime, onSecondaryContainer = Ink, surfaceContainer = Color.White,
                surfaceContainerHigh = Color.White, surfaceVariant = Color(0xFFE8EEDF),
                outline = Muted)) { FocusApp() }
        }
    }

    override fun onResume() { super.onResume(); applyRecentVisibility(); RuntimeSupport.refresh(this) }

    private fun applyRecentVisibility() {
        val hidden = FocusStore(this).read().hideRecentTask
        getSystemService(android.app.ActivityManager::class.java).appTasks.forEach { it.setExcludeFromRecents(hidden) }
    }

    @Composable
    private fun FocusApp() {
        val store = remember { FocusStore(this) }
        var settings by remember { mutableStateOf(store.read()) }
        var now by remember { mutableStateOf(Instant.now()) }
        var status by remember { mutableStateOf(RuntimeSupport.status(this)) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var message by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableStateOf<((FocusSettings) -> FocusSettings)?>(null) }
        var warningEditor by remember { mutableStateOf(false) }
        val updates = remember { AppUpdates(this) }
        LaunchedEffect(Unit) {
            if (updates.due()) runCatching { withContext(Dispatchers.IO) { updates.check() } }
                .getOrNull()?.let { message = "发现新版本 ${it.tag}，可在运行保障中查看说明并下载。" }
        }
        val zone = ZoneId.systemDefault()
        val state = ScheduleEngine.evaluate(settings, now, zone)
        val locked = state.restricted
        val onboarding = settings.onboardingStep < 4
        val visibleTab = if (onboarding) listOf(1, 2, 3, 0)[settings.onboardingStep.coerceIn(0, 3)] else tab

        LaunchedEffect(Unit) {
            while (true) {
                now = Instant.now()
                settings = store.read()
                status = RuntimeSupport.status(this@MainActivity)
                delay(1000)
            }
        }

        fun change(confirmed: Boolean = false, transform: (FocusSettings) -> FocusSettings) {
            try {
                when (store.change(confirmed, transform)) {
                    EditDecision.LOCKED -> message = "当前处于限制时段，结束后才能修改设置。"
                    EditDecision.CONFIRM_IMMEDIATE -> pending = transform
                    EditDecision.SAVE -> {
                        settings = store.read()
                        applyRecentVisibility()
                        RuntimeSupport.refresh(this@MainActivity)
                        status = RuntimeSupport.status(this@MainActivity)
                    }
                }
            } catch (_: Exception) { message = "保存失败，请重试；原有规则仍然保留。" }
        }

        BackHandler(enabled = (onboarding && settings.onboardingStep > 0) || (!onboarding && tab != 0)) {
            if (onboarding) change { it.copy(onboardingStep = it.onboardingStep - 1) } else tab = 0
        }
        Scaffold(containerColor = Paper, bottomBar = {
            if (onboarding) {
                Surface(shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        val canNext = when (settings.onboardingStep) {
                            0 -> settings.rules.isNotEmpty()
                            2, 3 -> status.ready
                            else -> true
                        }
                        Button(onClick = {
                            change { old ->
                                if (old.onboardingStep == 3) old.copy(onboardingStep = 4, enabled = true)
                                else old.copy(onboardingStep = old.onboardingStep + 1)
                            }
                        }, enabled = canNext, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text(if (settings.onboardingStep == 3) "确认并启用时间表" else "下一步")
                        }
                        if (settings.onboardingStep > 0) TextButton(onClick = { change { it.copy(onboardingStep = it.onboardingStep - 1) } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("上一步") }
                    }
                }
            } else NavigationBar(containerColor = Color.White) {
                listOf("今日", "时间表", "应用规则", "运行保障").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { FocusNavIcon(index) }, label = { Text(title) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
                    Text(if (onboarding) "首次设置 · ${settings.onboardingStep + 1} / 4" else "定时专注 / DAILY FOCUS", color = Muted, fontSize = 12.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(if (onboarding && settings.onboardingStep == 3) "准备好，开始专注" else listOf("把时间留给重要的事", "你的每周时间表", "只留下必要应用", "让限制准时发生")[visibleTab], fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                if (locked) Note("限制期间，时间表、应用规则和总开关已锁定。", Modifier.padding(horizontal = 20.dp))
                if (onboarding && settings.onboardingStep == 3 && !status.ready) Note("限制未就绪，请返回上一步完成授权后再启用。", Modifier.padding(horizontal = 20.dp))
                when (visibleTab) {
                    0 -> TodayScreen(settings, state, now, zone, status, onboarding,
                        onToggle = { value ->
                            if (value && !status.ready) message = "请先在运行保障中完成无障碍和准时触发授权。"
                            else change { it.copy(enabled = value) }
                        }, onStatus = { tab = 3 }, onWarning = { warningEditor = true }, onApps = { tab = 2 })
                    1 -> ScheduleScreen(settings.rules, locked, onSave = { rule ->
                        change { old -> old.copy(rules = if (old.rules.any { it.id == rule.id }) old.rules.map { if (it.id == rule.id) rule else it } else old.rules + rule) }
                    }, onDelete = { id -> change { old -> old.copy(rules = old.rules.filterNot { it.id == id }) } })
                    2 -> AllowlistScreen(settings.allowedPackages, settings.appCycles, locked, onRule = { pkg, mode, rule ->
                        change { old -> old.copy(
                            allowedPackages = if (mode == AppRuleMode.ALLOWED) old.allowedPackages + pkg else old.allowedPackages - pkg,
                            appCycles = when { rule != null -> old.appCycles + (pkg to rule); mode == AppRuleMode.ALLOWED -> old.appCycles; else -> old.appCycles - pkg }) }
                    }, onToggle = { pkg, checked ->
                        change { old -> old.copy(allowedPackages = if (checked) old.allowedPackages + pkg else old.allowedPackages - pkg) }
                    })
                    3 -> RuntimeScreen(status, settings.hideRecentTask, locked, onRecent = { hidden -> change { it.copy(hideRecentTask = hidden) } }, onMessage = { message = it })
                }
            }
        }
        if (warningEditor) WarningEditor(settings.warning, locked, dismiss = { warningEditor = false }, save = { style -> change { it.copy(warning = style) }; warningEditor = false })
        if (pending != null) AlertDialog(onDismissRequest = { pending = null },
            title = { Text("保存后将立即进入限制") },
            text = {
                val proposal = pending?.invoke(settings)
                val nextState = proposal?.let { ScheduleEngine.evaluate(it, Instant.now(), ZoneId.systemDefault()) }
                Text(if (nextState?.restricted == true && nextState.currentEnd == null)
                    "新时间表连续覆盖整周，没有空闲间隔。确认后，应用内将一直无法修改或关闭限制。请确认这确实是你的安排。"
                else "新时间表覆盖当前时间。确认后，各应用将按完全限制、使用后冷却或白名单规则执行；直到该连续时段结束，无法在应用内修改或关闭限制。")
            },
            confirmButton = { TextButton(onClick = { val action = pending; pending = null; action?.let { change(true, it) } }) { Text("确认并开始限制") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("返回检查") } })
        message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("提示") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } }) }
    }

    @Composable
    private fun TodayScreen(settings: FocusSettings, state: FocusState, now: Instant, zone: ZoneId, status: RuntimeStatus, onboarding: Boolean, onToggle: (Boolean) -> Unit, onStatus: () -> Unit, onWarning: () -> Unit, onApps: () -> Unit) {
        val date = now.atZone(zone).toLocalDate()
        val rows = ScheduleEngine.today(settings.rules, date, zone)
        val cycleRuntime = remember { AppCycleRuntime(this@MainActivity) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(color = Ink, shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.fillMaxWidth().padding(25.dp)) {
                        Text(date.format(DateTimeFormatter.ofPattern("M 月 d 日 · EEEE", Locale.CHINA)), color = Lime, fontSize = 13.sp)
                        Spacer(Modifier.height(24.dp))
                        Text(when { !settings.enabled -> "时间表未启用"; !status.ready -> "限制未就绪"; state.restricted -> "正在专注"; else -> "现在是自由时间" }, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        val target = if (state.restricted) state.currentEnd else state.nextTransition
                        Text(when {
                            !settings.enabled -> if (onboarding) "确认后，将按你设定的时间自动执行。" else "启用后，按每周时间表自动开始。"
                            state.restricted && target == null -> "时段连续覆盖，当前没有空闲间隔"
                            target != null -> "距离${if (state.restricted) "结束" else "下次开始"} ${countdown(now, target)}"
                            else -> "添加时段，安排你的专注时间"
                        }, color = Lime, fontSize = 18.sp)
                        if (target != null && settings.enabled) Text(formatMoment(target), color = Color.White.copy(alpha = .7f), modifier = Modifier.padding(top = 8.dp))
                        if (settings.enabled && !status.ready) Text("当前无法保证拦截，请检查运行保障。", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            if (!onboarding) item {
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("每周自动执行", fontWeight = FontWeight.SemiBold); Text(if (state.restricted) "限制结束后才能关闭" else "空闲时可以调整安排", color = Muted, fontSize = 12.sp) }
                        Switch(checked = settings.enabled, onCheckedChange = onToggle, enabled = !state.restricted)
                    }
                }
            }
            if (!onboarding) item {
                Surface(shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("弹性使用 · 每个应用独立配置", fontWeight = FontWeight.Bold)
                    val rule = settings.appCycles[WeChatPolicy.PACKAGE]
                    val cycle = rule?.let { cycleRuntime.evaluate(WeChatPolicy.PACKAGE, it, opened = false) }
                    Text(when {
                        !state.restricted -> "空闲时正常使用；限制时段内按各应用规则执行"
                        WeChatPolicy.PACKAGE in settings.allowedPackages -> "微信已在白名单，无需临时额度"
                        rule == null -> "微信普通页面完全限制；可在空闲时调整应用规则"
                        cycle?.access == WeChatAccess.USING -> "微信本轮剩余 ${durationText(cycle.remainingMs)}，离开不会暂停"
                        cycle?.access == WeChatAccess.COOLDOWN -> "微信冷却剩余 ${durationText(cycle.remainingMs)}"
                        else -> "微信${rule.summary}；打开普通页面才计时"
                    }, color = Muted, fontSize = 13.sp)
                    Text("已配置 ${settings.appCycles.keys.count { it !in settings.allowedPackages }} 个循环规则。微信已识别通话页面仍单独放行。", color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = onApps, modifier = Modifier.fillMaxWidth()) { Text("配置单个应用的使用与冷却") }
                    OutlinedButton(onClick = onWarning, modifier = Modifier.fillMaxWidth()) { Text(if (state.restricted) "预览专注警示" else "自定义标语与警示样式") }
                } }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("今日安排", fontSize = 19.sp, fontWeight = FontWeight.Bold); Text("${rows.size} 个时段", color = Muted) } }
            if (rows.isEmpty()) item { Note("今天没有安排限制时段。") }
            items(rows, key = { it.rule.id + it.span.start.toString() }) { row ->
                val active = settings.enabled && now >= row.span.start && now < row.span.end
                Surface(shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (active) Ink else Color(0xFFC5CEC3), CircleShape))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(row.rule.title, fontWeight = FontWeight.SemiBold)
                            Text("${minuteText(row.rule.startMinute)} — ${minuteText(row.rule.endMinute)}${if (row.rule.endMinute < row.rule.startMinute) " 次日" else ""}", color = Muted, modifier = Modifier.padding(top = 5.dp))
                            if (row.span.start.atZone(zone).toLocalDate() < date) Text("从昨日延续", color = Muted, fontSize = 12.sp)
                        }
                        Text(when { now >= row.span.end -> "已结束"; active -> "限制中"; !settings.enabled -> "未启用"; else -> "待开始" }, color = Muted, fontSize = 12.sp)
                    }
                }
            }
            item { Note("白名单已选 ${settings.allowedPackages.size} 个应用。电话、桌面和输入法等必要系统交互自动保留。") }
            if (!onboarding && !status.ready) item { OutlinedButton(onClick = onStatus, modifier = Modifier.fillMaxWidth()) { Text("前往运行保障") } }
            item { Text("按时段限制应用使用，不会停止其他应用的后台运行。", color = Muted, fontSize = 12.sp) }
        }
    }

    @Composable
    private fun ScheduleScreen(rules: List<FocusRule>, locked: Boolean, onSave: (FocusRule) -> Unit, onDelete: (String) -> Unit) {
        var editing by remember { mutableStateOf<FocusRule?>(null) }
        var deleting by remember { mutableStateOf<FocusRule?>(null) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("到点自动限制，结束自动恢复。跨午夜时段按开始日重复；重叠和相邻时段会连续执行。", color = Muted, fontSize = 14.sp) }
            items(rules.sortedBy { it.startMinute }, key = { it.id }) { rule ->
                Surface(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(rule.title, fontWeight = FontWeight.SemiBold)
                        Text("${minuteText(rule.startMinute)} — ${minuteText(rule.endMinute)}${if (rule.endMinute < rule.startMinute) " 次日" else ""}", fontSize = 25.sp, modifier = Modifier.padding(vertical = 10.dp))
                        Text(daysText(rule.days), color = Muted, fontSize = 13.sp)
                        Row(Modifier.align(Alignment.End)) {
                            TextButton(onClick = { deleting = rule }, enabled = !locked) { Text("删除") }
                            TextButton(onClick = { editing = rule }, enabled = !locked) { Text("编辑") }
                        }
                    }
                }
            }
            item { Button(onClick = { editing = FocusRule(title = "专注时段", startMinute = 540, endMinute = 600, days = DayOfWeek.entries.take(5).toSet()) }, enabled = !locked, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("＋ 添加时段") } }
            if (rules.isEmpty()) item { Note("尚无时间表。添加至少一个时段后再启用。") }
        }
        editing?.let { rule -> RuleEditor(rule, locked, onDismiss = { editing = null }, onSave = { editing = null; onSave(it) }) }
        deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这个时段？") }, text = { Text("${rule.title} · ${minuteText(rule.startMinute)}–${minuteText(rule.endMinute)}") }, confirmButton = { TextButton(enabled = !locked, onClick = { deleting = null; onDelete(rule.id) }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    }

    @Composable
    private fun RuleEditor(rule: FocusRule, locked: Boolean, onDismiss: () -> Unit, onSave: (FocusRule) -> Unit) {
        var title by remember(rule.id) { mutableStateOf(rule.title) }
        var start by remember(rule.id) { mutableIntStateOf(rule.startMinute) }
        var end by remember(rule.id) { mutableIntStateOf(rule.endMinute) }
        var days by remember(rule.id) { mutableStateOf(rule.days) }
        AlertDialog(onDismissRequest = onDismiss, title = { Text("设置专注时段") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(24) }, label = { Text("名称") }, singleLine = true, enabled = !locked)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { TimePickerDialog(this@MainActivity, { _, h, m -> start = h * 60 + m }, start / 60, start % 60, true).show() }, enabled = !locked, modifier = Modifier.weight(1f)) { Text("开始 ${minuteText(start)}") }
                    OutlinedButton(onClick = { TimePickerDialog(this@MainActivity, { _, h, m -> end = h * 60 + m }, end / 60, end % 60, true).show() }, enabled = !locked, modifier = Modifier.weight(1f)) { Text("结束 ${minuteText(end)}") }
                }
                if (end < start) Text("结束时间为次日 ${minuteText(end)}", color = Muted)
                Text("每周重复", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        val selected = day in days
                        Surface(color = if (selected) Ink else Paper, contentColor = if (selected) Color.White else Ink, shape = CircleShape, modifier = Modifier.weight(1f).toggleable(value = selected, enabled = !locked, role = Role.Checkbox, onValueChange = { days = if (it) days + day else days - day })) {
                            Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) { Text(DayNames[day.value - 1], fontSize = 12.sp) }
                        }
                    }
                }
                if (days.isEmpty() || start == end) Text(if (days.isEmpty()) "至少选择一天" else "开始和结束时间不能相同", color = MaterialTheme.colorScheme.error)
                if (title.isBlank()) Text("请填写时段名称", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !locked && days.isNotEmpty() && start != end && title.isNotBlank(), onClick = { onSave(rule.copy(title = title.trim(), startMinute = start, endMinute = end, days = days)) }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
    }

    @Composable
    private fun AllowlistScreen(allowed: Set<String>, cycles: Map<String, AppCycleRule>, locked: Boolean, onRule: (String, AppRuleMode, AppCycleRule?) -> Unit, onToggle: (String, Boolean) -> Unit) {
        var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var loaded by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        var filter by rememberSaveable { mutableIntStateOf(0) }
        var editing by remember { mutableStateOf<AppEntry?>(null) }
        val essentials = remember { EssentialApps.packages(this) }
        LaunchedEffect(Unit) {
            apps = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance(Locale.CHINA)
                packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
                    .distinctBy { it.pkg }.filter { it.pkg != packageName }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
            loaded = true
        }
        Column(Modifier.fillMaxSize()) {
            Note("点应用配置完全限制、使用后冷却或白名单。请保留短信、支付、地图等必需应用。", Modifier.padding(horizontal = 20.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text("搜索应用名称或包名") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == 0, onClick = { filter = 0 }, label = { Text("全部") })
                FilterChip(selected = filter == 1, onClick = { filter = 1 }, label = { Text("已允许 ${allowed.size}") })
                FilterChip(selected = filter == 2, onClick = { filter = 2 }, label = { Text("循环 ${cycles.keys.count { it !in allowed }}") })
            }
            val visible = apps.filter { (when (filter) { 1 -> it.pkg in allowed || it.pkg in essentials; 2 -> it.pkg in cycles && it.pkg !in allowed && it.pkg !in essentials; else -> true }) && (it.label.contains(query, true) || it.pkg.contains(query, true)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("勾选表示始终允许 · 必要系统应用自动保留", color = Muted, fontSize = 12.sp) }
                if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (loaded && visible.isEmpty()) item { Text("没有找到匹配的应用", color = Muted) }
                items(visible, key = { it.pkg }) { app ->
                    val essential = app.pkg in essentials
                    val checked = essential || app.pkg in allowed
                    Surface(shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !essential) { editing = app }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Paper, shape = RoundedCornerShape(12.dp)) { Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text(app.label.take(1), fontWeight = FontWeight.Bold) } }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(app.label, fontWeight = FontWeight.Medium)
                                Text(when { essential -> "必要系统交互 · 自动保留"; checked -> "白名单 · 始终允许"; cycles[app.pkg] != null -> cycles.getValue(app.pkg).summary; else -> "完全限制 · 点此配置" }, fontSize = 12.sp, color = Muted)
                                if (!essential) Text(app.pkg, fontSize = 10.sp, color = Muted)
                            }
                            Checkbox(checked = checked, onCheckedChange = { onToggle(app.pkg, it) }, enabled = !locked && !essential)
                        }
                    }
                }
            }
        }
        editing?.let { app -> AppRuleEditor(app.label, app.pkg, app.pkg in allowed, cycles[app.pkg], locked, dismiss = { editing = null }, save = { mode, rule -> onRule(app.pkg, mode, rule); editing = null }) }
    }

    @Composable
    private fun RuntimeScreen(status: RuntimeStatus, hideRecentTask: Boolean, locked: Boolean, onRecent: (Boolean) -> Unit, onMessage: (String) -> Unit) {
        var disclosure by remember { mutableStateOf(false) }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { RuntimeSupport.refresh(this) }
        fun open(intent: Intent) { if (!RuntimeSupport.open(this, intent)) { RuntimeSupport.appDetails(this); onMessage("此系统没有提供该设置入口，已尝试打开应用详情，请手动设置。") } }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Note(if (status.ready) "基础条件已就绪。请在目标手机验证后台清理、息屏和重启后的实际拦截。" else "限制未就绪：需要无障碍服务实际连接，并允许准时触发。") }
            item { OutlinedButton(onClick = { scope.launch { listState.animateScrollToItem(8) } }, modifier = Modifier.fillMaxWidth()) { Text("版本与更新 · ${BuildConfig.VERSION_NAME}") } }
            item { PermissionCard("01", "无障碍应用拦截", if (status.serviceConnected) "服务已连接" else "未连接 · 仅打开权限开关不代表服务在运行", status.serviceConnected, "去授权") {
                disclosure = true
            } }
            item { PermissionCard("02", "准时触发", if (status.exactAlarms) "已允许闹钟与提醒" else "需要允许在时段边界准时触发", status.exactAlarms, "去设置") {
                if (Build.VERSION.SDK_INT >= 31) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } }
            item { PermissionCard("03", "运行状态通知", if (status.notifications) "已允许" else "可选 · 显示限制状态与下一次切换时间", status.notifications, "开启通知") {
                if (Build.VERSION.SDK_INT >= 33 && !status.notifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } }
            item { PermissionCard("04", "后台与省电设置", if (status.batteryExempt) "已加入系统电池优化例外，仍需检查厂商设置" else "建议设置无限制，并开启自启动", status.batteryExempt, "打开设置") { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
            item {
                Surface(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("小米 / Redmi · HyperOS", fontWeight = FontWeight.Bold)
                        Text("在应用详情中检查自启动、后台运行及省电策略；按系统提供的选项设为允许或无限制。必要时在最近任务中锁定本应用。不同版本的菜单名称可能不同。", color = Muted, fontSize = 13.sp)
                        OutlinedButton(onClick = { RuntimeSupport.appDetails(this@MainActivity) }) { Text("打开本应用详情") }
                    }
                }
            }
            item { Text("能力边界：应用内不提供提前解除。系统强行停止、撤权、卸载或修改手机时间可能影响限制；本工具无法阻止这些系统操作。重启后需要首次解锁才能恢复本机规则。", color = Muted, fontSize = 12.sp) }
            item { UpdateCard(remember { AppUpdates(this@MainActivity) }, ScheduleEngine.evaluate(FocusStore(this@MainActivity).read(), Instant.now(), ZoneId.systemDefault()).restricted) }
            item { Surface(shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row { Text("从最近任务隐藏卡片", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold); Switch(hideRecentTask, onCheckedChange = onRecent, enabled = !locked) }
                Text("默认开启，减少从最近任务划掉本应用的机会。桌面图标、运行通知和系统权限入口保留。无障碍服务独立于页面运行；HyperOS 清理策略仍需真机检查，系统强停、撤权和卸载仍能解除限制。", color = Muted, fontSize = 13.sp)
            } } }
        }
        if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("开启应用拦截权限") },
            text = { Text("无障碍权限用于识别前台应用并返回桌面。不读取页面文字，不上传数据。\n\n继续后，请在无障碍 → 已下载的应用中开启「定时专注」。若系统提示受限设置，请在应用详情中允许受限设置后重试。") },
            confirmButton = { TextButton(onClick = { disclosure = false; open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("同意并前往授权") } },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("暂不开启") } })
    }
}

private data class AppEntry(val pkg: String, val label: String)

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFE8EEDF), shape = RoundedCornerShape(16.dp), modifier = modifier.fillMaxWidth()) {
        Text(text, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(15.dp))
    }
}

@Composable
private fun PermissionCard(number: String, title: String, detail: String, okay: Boolean, action: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(number, color = Muted, fontSize = 12.sp); Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 12.dp)); Text(if (okay) "✓" else "待设置", color = if (okay) Ink else Color(0xFFA65C2C), fontSize = 12.sp) }
            Text(detail, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

private fun daysText(days: Set<DayOfWeek>) = if (days.size == 7) "每天重复" else "每周" + days.sortedBy { it.value }.joinToString("、") { DayNames[it.value - 1] }
private fun countdown(now: Instant, target: Instant): String {
    val seconds = Duration.between(now, target).seconds.coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
}
