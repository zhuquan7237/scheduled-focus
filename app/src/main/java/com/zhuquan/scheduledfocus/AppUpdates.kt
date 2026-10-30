package com.zhuquan.scheduledfocus

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

data class AppRelease(val tag: String, val notes: String, val url: String, val size: Long, val digest: String)
data class DownloadState(val message: String, val finished: Boolean = false, val progress: Float? = null)

/** GitHub is contacted only for updates. No schedules, whitelist or usage data leave the device. */
class AppUpdates(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(DownloadManager::class.java)
    var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(value) { prefs.edit().putBoolean("automatic", value).apply() }
    fun due() = automatic && System.currentTimeMillis() - prefs.getLong("checked", 0) > 6 * 60 * 60 * 1000
    fun cached(): AppRelease? = prefs.getString("release", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
    fun check(): AppRelease? {
        // A public API quota is shared by everyone using the same internet gateway.
        // The workflow also publishes metadata without the REST API quota.
        val json = runCatching { fetch("https://raw.githubusercontent.com/$REPOSITORY/main/updates/latest.json") }.getOrNull()
            ?: fetch("https://api.github.com/repos/$REPOSITORY/releases/latest")
        val release = json?.let { decode(it) }
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply {
            if (json == null) remove("release") else putString("release", json.toString())
        }.apply()
        return release
    }
    private fun fetch(url: String): JSONObject? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ScheduledFocus/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode == 404) return null
            check(connection.responseCode == 200) { "更新服务暂不可用（${connection.responseCode}），请稍后重试" }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun decode(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.getString("tag_name")
        if (!newer(tag, BuildConfig.VERSION_NAME)) return null
        val assets = json.getJSONArray("assets")
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == "scheduled-focus-$tag.apk" } ?: return null
        val url = asset.getString("browser_download_url")
        require(url.startsWith("https://github.com/$REPOSITORY/releases/download/")) { "更新地址不受信任" }
        return AppRelease(tag, json.optString("body").take(12000), url, asset.getLong("size"), asset.optString("digest"))
    }
    fun download(release: AppRelease) {
        val old = prefs.getLong("download", -1)
        if (old != -1L) manager.remove(old)
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        destination.delete()
        val id = manager.enqueue(DownloadManager.Request(Uri.parse(release.url))
            .setTitle("定时专注 ${release.tag}").setDescription("下载完成后，请返回应用确认安装")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination)))
        prefs.edit().putLong("download", id).putString("download_tag", release.tag)
            .putString("digest", release.digest).putLong("size", release.size).apply()
    }
    fun downloadState(expectedTag: String? = null): DownloadState? {
        if (expectedTag != null && expectedTag != prefs.getString("download_tag", null)) return null
        val id = prefs.getLong("download", -1)
        if (id == -1L) return null
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return DownloadState("下载记录已移除，请重新下载")
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState("下载完成 · 安装前将校验身份", true)
                DownloadManager.STATUS_FAILED -> DownloadState("下载失败，请检查网络并重试")
                DownloadManager.STATUS_PAUSED -> DownloadState("下载已暂停，等待网络恢复")
                else -> DownloadState("正在下载${if (total > 0) " · ${done * 100 / total}%" else ""}", progress = if (total > 0) done.toFloat() / total else null)
            }
        }
        return null
    }
    @Suppress("DEPRECATION")
    fun validate(): File {
        check(downloadState()?.finished == true) { "请等待下载完成" }
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "update.apk")
        check(file.length() == prefs.getLong("size", -1)) { "文件不完整，请重新下载" }
        val digest = prefs.getString("digest", "").orEmpty()
        if (digest.startsWith("sha256:")) {
            val hasher = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; hasher.update(bytes, 0, n) } }
            check(hasher.digest().joinToString("") { "%02x".format(it) } == digest.removePrefix("sha256:")) { "文件校验失败，请重新下载" }
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: error("无法读取安装包")
        check(archive.versionName == prefs.getString("download_tag", "")?.removePrefix("v")) { "安装包版本与下载记录不符，请重新下载" }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(archive.packageName == context.packageName && archive.signatures?.toSet() == installed.signatures?.toSet()) { "安装包身份或签名不匹配，已拒绝安装" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(archiveVersion > BuildConfig.VERSION_CODE) { "下载的版本不比当前版本新" }
        return file
    }
    fun install(file: File): Boolean {
        check(!ScheduleEngine.evaluate(FocusStore(context).read(), Instant.now(), ZoneId.systemDefault()).restricted) { "专注期间无法安装更新，请结束后再试" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }
    companion object {
        const val REPOSITORY = "zhuquan7237/scheduled-focus"
        fun newer(candidate: String, current: String): Boolean {
            fun parts(value: String): List<Long>? = value.removePrefix("v").takeIf { it.matches(Regex("[0-9]+[.][0-9]+[.][0-9]+")) }?.split('.')?.map { it.toLongOrNull() ?: return null }
            val a = parts(candidate) ?: return false; val b = parts(current) ?: return false
            for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
            return false
        }
    }
}
