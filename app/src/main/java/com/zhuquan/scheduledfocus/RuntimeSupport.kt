package com.zhuquan.scheduledfocus

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.*
import java.time.format.DateTimeFormatter

data class RuntimeStatus(val serviceConnected: Boolean, val exactAlarms: Boolean, val notifications: Boolean, val batteryExempt: Boolean) {
    val ready get() = serviceConnected && exactAlarms
}

object RuntimeSupport {
    fun status(context: Context) = RuntimeStatus(
        serviceConnected = FocusAccessibilityService.connected != null,
        exactAlarms = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(),
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        batteryExempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    )

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 10,
        Intent(context, ScheduleReceiver::class.java).setAction("com.zhuquan.scheduledfocus.BOUNDARY"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun refresh(context: Context) {
        val settings = FocusStore(context).read()
        val state = ScheduleEngine.evaluate(settings, Instant.now(), ZoneId.systemDefault())
        val alarm = context.getSystemService(AlarmManager::class.java)
        val operation = pending(context)
        alarm.cancel(operation)
        state.nextTransition?.let { next ->
            try {
                if (Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
                }
            } catch (_: SecurityException) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), operation)
            }
        }
        notification(context, settings, state)
        FocusAccessibilityService.connected?.recheck()
    }

    private fun notification(context: Context, settings: FocusSettings, state: FocusState) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("focus_status", "专注运行状态", NotificationManager.IMPORTANCE_LOW))
        if (!settings.enabled) { manager.cancel(1); return }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val ready = status(context).ready
        val title = if (!ready) "限制未就绪" else if (state.restricted) "专注时段 · 按应用规则执行" else "时间表已启用"
        val text = if (!ready) "请打开运行保障，检查无障碍服务和准时触发权限"
            else if (state.restricted) state.currentEnd?.let { "${formatMoment(it)} 结束，应用内无法提前解除" } ?: "时间表连续覆盖，当前没有空闲间隔"
            else state.nextTransition?.let { "下次开始：${formatMoment(it)}" } ?: "尚未设置限制时段"
        val contentIntent = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, NotificationCompat.Builder(context, "focus_status")
            .setSmallIcon(com.zhuquan.scheduledfocus.R.drawable.ic_focus).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(contentIntent)
            .setOngoing(true).setSilent(true).build())
    }

    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    fun appDetails(context: Context) = open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}")))
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                "com.zhuquan.scheduledfocus.BOUNDARY")) RuntimeSupport.refresh(context)
    }
}

fun formatMoment(instant: Instant): String = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
