package com.zhuquan.scheduledfocus

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

object EssentialApps {
    fun packages(context: Context): Set<String> {
        val homes = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            // Settings registers a low-priority FallbackHome for boot; it is not a usable launcher.
            .filter { it.priority >= 0 && it.activityInfo.packageName != "com.android.settings" }
            .map { it.activityInfo.packageName }
        val keyboards = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        val dialer = context.getSystemService(TelecomManager::class.java).defaultDialerPackage
        return setOfNotNull(context.packageName, dialer, "android", "com.android.systemui", "com.android.settings",
            "com.android.server.telecom", "com.android.emergency", "com.google.android.apps.safetyhub",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.miui.packageinstaller", "com.lbe.security.miui") + homes + keyboards
    }
}
