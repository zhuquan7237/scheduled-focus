package com.zhuquan.scheduledfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun WarningEditor(initial: WarningStyle, locked: Boolean, dismiss: () -> Unit, save: (WarningStyle) -> Unit) {
    var headline by remember { mutableStateOf(initial.headline) }
    var reminder by remember { mutableStateOf(initial.reminder) }
    var theme by remember { mutableIntStateOf(initial.theme) }
    var font by remember { mutableIntStateOf(initial.font) }
    var size by remember { mutableFloatStateOf(initial.size.toFloat()) }
    var seconds by remember { mutableFloatStateOf(initial.seconds.toFloat()) }
    val style = WarningStyle(headline.ifBlank { WarningStyle().headline }, reminder, theme, font, size.toInt(), seconds.toInt())
    AlertDialog(onDismissRequest = dismiss, title = { Text("你的专注警示") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (locked) "专注期间仅可预览，结束后才能修改。" else "写下目标，给分心时的自己一个清楚的提醒。")
            Text("一键套用 · 不必从空白开始")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WarningPresets.all.forEach { preset ->
                    FilterChip(selected = style == preset.style, enabled = !locked, onClick = {
                        headline = preset.style.headline; reminder = preset.style.reminder
                        theme = preset.style.theme; font = preset.style.font
                        size = preset.style.size.toFloat(); seconds = preset.style.seconds.toFloat()
                    }, label = { Text(preset.name) })
                }
            }
            OutlinedTextField(headline, { headline = it.take(60) }, label = { Text("目标标语 · 最多 60 字") }, enabled = !locked, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reminder, { reminder = it.take(140) }, label = { Text("补充提醒 · 可留空") }, enabled = !locked, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("警示配色")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("黑金", "警戒红", "森林绿").forEachIndexed { i, label -> FilterChip(theme == i, { theme = i }, enabled = !locked, label = { Text(label) }) } }
            Text("字体风格")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("力量黑体", "衬线", "等宽").forEachIndexed { i, label -> FilterChip(font == i, { font = i }, enabled = !locked, label = { Text(label) }) } }
            Text("标语字号 ${size.toInt()} sp")
            Slider(size, { size = it }, enabled = !locked, valueRange = 24f..44f, steps = 19)
            Text("提示停留 ${seconds.toInt()} 秒")
            Slider(seconds, { seconds = it }, enabled = !locked, valueRange = 4f..10f, steps = 5)
            Text("实时预览 · 不拦截触摸，识别到通话页面时会移除")
            key(style) { AndroidView(factory = { WarningViews.create(it, style, "此应用暂不可用 · 请回到你的目标") }, modifier = Modifier.fillMaxWidth()) }
            TextButton(enabled = !locked, onClick = { val defaults = WarningStyle(); headline = defaults.headline; reminder = defaults.reminder; theme = defaults.theme; font = defaults.font; size = defaults.size.toFloat(); seconds = defaults.seconds.toFloat() }) { Text("恢复默认警示") }
        }
    }, confirmButton = { TextButton(enabled = !locked && headline.isNotBlank(), onClick = { save(style) }) { Text("保存警示") } }, dismissButton = { TextButton(onClick = dismiss) { Text(if (locked) "关闭预览" else "取消") } })
}
