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
    private var banner: TextView? = null
    private var lastBlocked = ""
    private var lastBlockAt = 0L
    @Volatile internal var lastCheck = "waiting"
        private set
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
        connection = WeakReference(this)
        RuntimeSupport.refresh(this)
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (connected !== this) return
        // Inspect the actual focused application window, never the event's text or source nodes.
        recheck()
    }

    fun recheck() {
        handler.removeCallbacks(checkWindow)
        handler.postDelayed(checkWindow, 80)
    }

    private fun checkForeground() {
        if (connected !== this) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) { lastCheck = "keyguard"; clearBanner(); return }
        val settings = store.read()
        val state = ScheduleEngine.evaluate(settings, Instant.now(), ZoneId.systemDefault())
        if (!state.restricted) { lastCheck = "unrestricted"; clearBanner(); return }
        val applicationWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val focused = applicationWindows.firstOrNull { it.isFocused } ?: applicationWindows.firstOrNull { it.isActive }
        val foreground = (focused?.root ?: rootInActiveWindow)?.packageName?.toString()
        lastCheck = "restricted; windows=${applicationWindows.size}; package=$foreground"
        if (foreground == null) return
        if (foreground in settings.allowedPackages || foreground in EssentialApps.packages(this)) { lastCheck += "; allowed"; return }
        val now = SystemClock.elapsedRealtime()
        if (foreground == lastBlocked && now - lastBlockAt < 700) return
        lastBlocked = foreground
        lastBlockAt = now
        val returnedHome = performGlobalAction(GLOBAL_ACTION_HOME)
        lastCheck += "; home=$returnedHome"
        if (returnedHome) {
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(foreground, 0)).toString() }.getOrDefault("此应用")
            showBanner("$label 暂不可用\n" + (state.currentEnd?.let { "专注至 ${formatMoment(it)} · 仅白名单可用" } ?: "当前时间表连续限制 · 仅白名单可用"))
        }
    }

    private fun showBanner(message: String) {
        handler.removeCallbacks(removeBanner)
        val view = banner ?: TextView(this).also { text ->
            val density = resources.displayMetrics.density
            text.setTextColor(Color.WHITE)
            text.textSize = 16f
            text.setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
            text.background = GradientDrawable().apply { setColor(Color.rgb(23, 61, 54)); cornerRadius = 20 * density }
            val params = WindowManager.LayoutParams(
                resources.displayMetrics.widthPixels - (32 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (54 * density).toInt() }
            runCatching { getSystemService(WindowManager::class.java).addView(text, params); banner = text }
        }
        view.text = message
        handler.postDelayed(removeBanner, 4000)
    }

    private fun clearBanner() {
        banner?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        banner = null
    }

    override fun onInterrupt() { clearBanner() }
    override fun onUnbind(intent: Intent?): Boolean { disconnect(); return super.onUnbind(intent) }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        if (connected === this) connection.clear()
        handler.removeCallbacksAndMessages(null)
        clearBanner()
        RuntimeSupport.refresh(this)
    }

    companion object {
        @Volatile private var connection = WeakReference<FocusAccessibilityService>(null)
        val connected: FocusAccessibilityService? get() = connection.get()
    }
}
