package com.zhuquan.scheduledfocus

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.Layout
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

data class WarningStyle(
    val headline: String = "别让一时分心，偷走你的目标。",
    val reminder: String = "现在的每一次克制，都在靠近你想要的未来。",
    val theme: Int = 0, val font: Int = 0, val size: Int = 32, val seconds: Int = 6,
) {
    init { require(headline.isNotBlank() && headline.length <= 60 && reminder.length <= 140); require(theme in 0..2 && font in 0..2 && size in 24..44 && seconds in 4..10) }
}

object WarningViews {
    // Inlined constant (2); TextView supports balanced breaking since API 23.
    @SuppressLint("InlinedApi")
    fun create(context: Context, style: WarningStyle, detail: String): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val heightDp = context.resources.displayMetrics.heightPixels / dp
        val headlineLines = ((heightDp - 360) / (style.size * context.resources.configuration.fontScale * 1.25f)).toInt().coerceIn(2, 5)
        val accent = intArrayOf(Color.rgb(255, 216, 75), Color.rgb(255, 100, 100), Color.rgb(221, 245, 170))[style.theme]
        val bg = intArrayOf(Color.rgb(31, 27, 19), Color.rgb(48, 14, 22), Color.rgb(17, 49, 43))[style.theme]
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            background = GradientDrawable().apply { setColor(bg); cornerRadius = 24 * dp; setStroke((3 * dp).toInt(), accent) }
            fun label(text: String, size: Float, color: Int, bold: Boolean, max: Int) {
                addView(TextView(context).apply {
                    this.text = text; textSize = size; setTextColor(color); gravity = Gravity.CENTER
                    breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                    maxLines = max; ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create(arrayOf("sans-serif-black", "serif", "monospace")[style.font], if (bold) Typeface.BOLD else Typeface.NORMAL)
                    setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
                }, LinearLayout.LayoutParams(-1, -2))
            }
            label("!   专 注 警 报   /   STOP", 13f, accent, true, 1)
            label(style.headline, style.size.toFloat(), accent, true, headlineLines)
            if (style.reminder.isNotBlank()) label(style.reminder, 15f, Color.WHITE, false, if (heightDp < 700) 3 else 4)
            label(detail, 13f, Color.rgb(218, 216, 209), true, 4)
        }
    }
}
