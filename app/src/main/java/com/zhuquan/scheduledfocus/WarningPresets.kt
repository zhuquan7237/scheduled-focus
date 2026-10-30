package com.zhuquan.scheduledfocus

data class WarningPreset(val name: String, val style: WarningStyle)

object WarningPresets {
    val all = listOf(
        WarningPreset("备考", WarningStyle("现在多专注一页，未来就多一分底气。", "先完成这一轮，再给自己休息。", theme = 0, size = 32)),
        WarningPreset("工作", WarningStyle("先完成重要的事，再回应热闹的世界。", "消息可以稍后处理，眼前的任务值得完整的注意力。", theme = 2, size = 30)),
        WarningPreset("戒拖延", WarningStyle("别把想做的事，又留给明天。", "不用等到状态完美，现在就做下一小步。", theme = 1, size = 34)),
        WarningPreset("长期目标", WarningStyle("你想要的未来，值得现在的专注。", "今天守住这一段时间，距离目标就更近一点。", theme = 0, font = 1, size = 32)),
        WarningPreset("温和提醒", WarningStyle("先把注意力，带回自己身边。", "不必苛责自己。放下手机，继续刚才的那一步就好。", theme = 2, font = 1, size = 30)),
        WarningPreset("强警醒", WarningStyle("停。现在打开它，真的有必要吗？", "别让一次随手点开，带走你为目标留下的时间。", theme = 1, size = 36, seconds = 8)),
    )
}
