package com.zhuquan.scheduledfocus

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.lang.ref.WeakReference

class FocusAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: FocusStore
    private var previousState: FocusState? = null
    private var banner: View? = null
    private var timerBadge: TextView? = null
    private lateinit var weChat: WeChatRuntime
    private val weChatWindows = mutableMapOf<Int, String>()
    private var unknownWeChatWindow = -1
    private var unknownWeChatSince = 0L
    private var observedPackage: String? = null
    private var weChatEntryAt = 0L
    private var lastBlocked = ""
    private var lastBlockAt = 0L
    @Volatile internal var lastCheck = "waiting"
        private set
    internal val warningView: View? get() = banner
    private val removeBanner = Runnable { clearBanner() }
    private val checkWindow = Runnable { checkForeground() }
    private val heartbeat = object : Runnable {
        override fun run() {
            val state = ScheduleEngine.evaluate(store.read(), Instant.now(), ZoneId.systemDefault())
            if (state != previousState) {
                previousState = state
                RuntimeSupport.refresh(this@FocusAccessibilityService)
            }
            if (state.restricted) checkForeground() else clearBanner()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        store = FocusStore(this)
        weChat = WeChatRuntime(this)
        weChatWindows.clear()
        observedPackage = null
        unknownWeChatWindow = -1
        connection = WeakReference(this)
        RuntimeSupport.refresh(this)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (connected !== this) return
        // Only window class metadata is inspected for WeChat calls. Never read notification or chat text.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.packageName?.toString() == WeChatPolicy.PACKAGE) {
            val name = event.className?.toString().orEmpty()
            if (name.startsWith("com.tencent.mm.") && (name.endsWith("Activity") || name.endsWith("UI"))) {
                if (weChatWindows.size > 20) weChatWindows.clear()
                weChatWindows[event.windowId] = name
            }
        }
        recheck()
    }

    fun recheck() {
        handler.removeCallbacks(checkWindow)
        handler.postDelayed(checkWindow, 80)
    }

    private fun checkForeground() {
        if (connected !== this) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { lastCheck = "keyguard"; clearBanner(); clearTimer(); return }
        val settings = store.read()
        val state = ScheduleEngine.evaluate(settings, Instant.now(), ZoneId.systemDefault())
        if (!state.restricted) { lastCheck = "unrestricted"; clearBanner(); clearTimer(); return }
        val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val focused = applicationWindows.firstOrNull { it.isFocused } ?: applicationWindows.firstOrNull { it.isActive }
        val root = focused?.root ?: rootInActiveWindow
        val foreground = root?.packageName?.toString()
        lastCheck = "restricted; windows=${applicationWindows.size}; package=$foreground"
        if (foreground == null) { clearTimer(); return }
        if (foreground != observedPackage) {
            if (foreground == WeChatPolicy.PACKAGE) weChatEntryAt = SystemClock.elapsedRealtime()
            observedPackage = foreground
        }
        if (foreground in settings.allowedPackages || foreground in EssentialApps.packages(this)) { clearTimer(); lastCheck += "; allowed"; return }
        var detail = state.currentEnd?.let { "专注至 ${formatMoment(it)} · 仅白名单可用" } ?: "当前时间表连续限制 · 仅白名单可用"
        if (foreground == WeChatPolicy.PACKAGE) {
            val screen = root?.let { weChatWindows[it.windowId] ?: it.className?.toString() }
            // Give an incoming call's activity event time to replace the underlying chat window.
            // No use window is created during this short transition.
            if (!WeChatPolicy.isCallActivity(screen) && SystemClock.elapsedRealtime() - weChatEntryAt < 600) {
                clearTimer(); lastCheck += "; waiting for WeChat transition"; return
            }
            if (screen?.startsWith("com.tencent.mm.") != true) {
                val id = root?.windowId ?: -1
                if (id != unknownWeChatWindow) { unknownWeChatWindow = id; unknownWeChatSince = SystemClock.elapsedRealtime() }
                if (SystemClock.elapsedRealtime() - unknownWeChatSince < 500) { lastCheck += "; waiting for window metadata"; return }
            } else unknownWeChatWindow = -1
            val decision = weChat.evaluate(opened = true, call = WeChatPolicy.isCallActivity(screen))
            lastCheck += "; screen=$screen; wechat=${decision.access}"
            when (decision.access) {
                WeChatAccess.CALL -> { clearBanner(); clearTimer(); return }
                WeChatAccess.USING -> { clearBanner(); showTimer(decision.remainingMs); return }
                WeChatAccess.COOLDOWN -> detail = "微信 2 分钟已用完 · 冷却剩余 ${durationText(decision.remainingMs)}\n冷却后等你下次打开才开始计时，通话页面例外"
                WeChatAccess.READY -> Unit
            }
        }
        clearTimer()
        val now = SystemClock.elapsedRealtime()
        if (foreground == lastBlocked && now - lastBlockAt < 700) return
        lastBlocked = foreground
        lastBlockAt = now
        val returnedHome = performGlobalAction(GLOBAL_ACTION_HOME)
        lastCheck += "; home=$returnedHome"
        if (returnedHome) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(foreground, 0)).toString() }.getOrDefault("此应用")
            showBanner(settings.warning, "$label 暂不可用\n$detail")
        }
    }

    private fun showBanner(style: WarningStyle, detail: String) {
        handler.removeCallbacks(removeBanner)
        clearBanner()
        val view = WarningViews.create(this, style, detail)
        val density = resources.displayMetrics.density
        val params = overlayParams(resources.displayMetrics.widthPixels - (32 * density).toInt()).apply { gravity = Gravity.CENTER }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params); banner = view }
        handler.postDelayed(removeBanner, style.seconds * 1000L)
    }

    private fun overlayParams(width: Int) = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)

    private fun showTimer(ms: Long) {
        val view = timerBadge ?: TextView(this).apply {
            textSize = 14f; setTextColor(Color.rgb(255, 216, 75)); setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply { setColor(Color.rgb(31, 27, 19)); cornerRadius = 24f }
        }.also { text ->
            val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (48 * resources.displayMetrics.density).toInt() }
            runCatching { getSystemService(WindowManager::class.java).addView(text, params); timerBadge = text }
        }
        view.text = "微信临时使用 ${durationText(ms)} · 离开不暂停"
    }

    private fun clearTimer() {
        timerBadge?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; timerBadge = null
    }

    private fun clearBanner() {
        banner?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        banner = null
    }

    override fun onInterrupt() { clearBanner(); clearTimer() }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (connected === this) connection.clear()
        handler.removeCallbacksAndMessages(null)
        clearBanner()
        clearTimer()
        RuntimeSupport.refresh(this)
    }

    companion object {
        @Volatile private var connection = WeakReference<FocusAccessibilityService>(null)
        val connected: FocusAccessibilityService? get() = connection.get()
    }
}
