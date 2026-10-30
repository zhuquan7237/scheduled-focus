package com.zhuquan.scheduledfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdateCard(updates: AppUpdates, locked: Boolean) {
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf(updates.cached()) }
    var automatic by remember { mutableStateOf(updates.automatic) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("从 GitHub Release 获取新版本") }
    var download by remember { mutableStateOf(updates.downloadState()) }
    LaunchedEffect(Unit) { while (true) { download = updates.downloadState(); delay(1000) } }
    fun action(block: suspend () -> Unit) {
        scope.launch { busy = true; try { block() } catch (e: Exception) { message = e.message ?: "操作失败，请重试" } finally { busy = false } }
    }
    Surface(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("版本与更新", fontWeight = FontWeight.Bold)
            Text("当前版本 ${BuildConfig.VERSION_NAME}")
            Row {
                Column(Modifier.weight(1f)) { Text("自动检查更新"); Text("打开应用时检查，每 6 小时最多一次", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = automatic, onCheckedChange = { automatic = it; updates.automatic = it })
            }
            Text("仅更新检查会联网，不上传时间表或白名单。下载由你确认，安装由系统确认。", style = MaterialTheme.typography.bodySmall)
            Text(message, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { action {
                release = withContext(Dispatchers.IO) { updates.check() }
                message = if (release == null) "已是最新版本，或仓库暂无新版本" else "发现新版本 ${release!!.tag}"
            } }) { Text(if (busy) "处理中…" else "检查更新") }
            release?.let { item ->
                Text("新版本 ${item.tag} · ${"%.1f".format(item.size / 1048576.0)} MB", fontWeight = FontWeight.SemiBold)
                download?.let { state ->
                    Text(state.message)
                    state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                }
                if (download?.finished == true) {
                    Button(enabled = !busy && !locked, onClick = { action {
                        val file = withContext(Dispatchers.IO) { updates.validate() }
                        message = if (updates.install(file)) "已交给系统安装" else "请允许安装来源后返回，再点击安装"
                    } }) { Text(if (locked) "专注结束后安装" else "校验并安装更新") }
                } else {
                    Button(enabled = !busy && download?.progress == null, onClick = { action { updates.download(item); download = updates.downloadState(); message = "已开始下载，可离开此页面" } }) { Text("下载更新") }
                }
                if (item.notes.isNotBlank()) {
                    var expanded by remember(item.tag) { mutableStateOf(false) }
                    Text("更新说明", fontWeight = FontWeight.SemiBold)
                    Text(item.notes.replace(Regex("(?m)^#{1,6}[ ]+"), "").replace("`", ""),
                        style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起说明" else "展开完整说明") }
                }
            }
        }
    }
}
