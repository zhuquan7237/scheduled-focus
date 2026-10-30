package com.zhuquan.scheduledfocus

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** A separate installed test package, intentionally absent from the whitelist. */
class BlockedTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Ordinary app interception fixture" })
    }
}
