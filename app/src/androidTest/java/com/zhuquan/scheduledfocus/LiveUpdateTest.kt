package com.zhuquan.scheduledfocus

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.URL

/** Opt-in only: consumes a real public release and downloads it through Android DownloadManager. */
class LiveUpdateTest {
    @Test fun githubReleaseDownloadsAndPassesIdentityChecks() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdates") == "true")
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FocusStore(context).change { FocusSettings(onboardingStep = 4) }
        val updates = AppUpdates(context)
        val candidate = InstrumentationRegistry.getArguments().getString("candidate")
        val release = if (candidate == null) updates.check() else {
            // Explicit emulator-only candidate validation; production still rejects prereleases.
            require(candidate.matches(Regex("v[0-9]+[.][0-9]+[.][0-9]+")))
            val json = JSONObject(URL("https://raw.githubusercontent.com/${AppUpdates.REPOSITORY}/main/updates/candidates/$candidate.json").readText())
            val tag = json.getString("tag_name")
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.getString("name") == "scheduled-focus-$tag.apk" }
            context.getSharedPreferences("app_updates", 0).edit()
                .putLong("checked", System.currentTimeMillis())
                .putString("release", JSONObject(json.toString()).put("prerelease", false).toString()).commit()
            AppRelease(tag, json.optString("body"), asset.getString("browser_download_url"), asset.getLong("size"), asset.optString("digest"))
        }
        assertNotNull("Run with a fixture version older than the public GitHub release", release)
        updates.download(release!!)
        val deadline = SystemClock.elapsedRealtime() + 180000
        while (SystemClock.elapsedRealtime() < deadline && updates.downloadState()?.finished != true) {
            assertFalse(updates.downloadState().toString(), updates.downloadState()?.message?.contains("失败") == true)
            SystemClock.sleep(500)
        }
        assertTrue(updates.downloadState().toString(), updates.downloadState()?.finished == true)
        assertTrue(updates.validate().isFile)
        assertNull("A completed old download must not appear ready for another release", updates.downloadState("v999.0.0"))
        val prefs = context.getSharedPreferences("app_updates", 0)
        val digest = prefs.getString("digest", "")
        prefs.edit().putString("digest", "sha256:invalid").commit()
        try {
            updates.validate()
            fail("Corrupt digest must be rejected")
        } catch (_: IllegalStateException) {
            // Expected: integrity failure must never reach the installer.
        } finally { prefs.edit().putString("digest", digest).commit() }
        assertTrue(updates.validate().isFile)
    }
}
