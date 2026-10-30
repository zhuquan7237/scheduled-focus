package com.zhuquan.scheduledfocus

import org.junit.Assert.*
import org.junit.Test

class UpdateVersionTest {
    @Test fun numericOrderingHandlesTwoDigitVersions() {
        assertTrue(AppUpdates.newer("v0.10.0", "0.2.0"))
        assertTrue(AppUpdates.newer("1.0.0", "0.99.99"))
        assertFalse(AppUpdates.newer("v0.2.0", "0.2.0"))
        assertFalse(AppUpdates.newer("v0.1.9", "0.2.0"))
    }
    @Test fun malformedOrPrereleaseTagsAreNotInstalled() {
        for (tag in listOf("v0.3.0-beta", "latest", "0.3", "99999999999999999999999.0.0"))
            assertFalse(tag, AppUpdates.newer(tag, "0.2.0"))
    }
}
