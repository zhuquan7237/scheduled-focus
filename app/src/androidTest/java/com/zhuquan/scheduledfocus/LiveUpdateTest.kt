package com.zhuquan.scheduledfocus

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume
import org.junit.Assert.*
import org.junit.Test

/** Opt-in only: consumes a real public release and downloads it through Android DownloadManager. */
class LiveUpdateTest {
    @Test fun githubReleaseDownloadsAndPassesIdentityChecks() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveUpdates") == "true")
        Assume.assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        FocusStore(context).change { FocusSettings(onboardingStep = 4) }
        val updates = AppUpdates(context)
        val release = updates.check()
        assertNotNull("Run with a fixture version older than the public GitHub release", release)
        updates.download(release!!)
        val deadline = SystemClock.elapsedRealtime() + 180000
        while (SystemClock.elapsedRealtime() < deadline && updates.downloadState()?.finished != true) {
            assertFalse(updates.downloadState().toString(), updates.downloadState()?.message?.contains("失败") == true)
            SystemClock.sleep(500)
        }
        assertTrue(updates.downloadState().toString(), updates.downloadState()?.finished == true)
        assertTrue(updates.validate().isFile)
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
