package com.zhuquan.scheduledfocus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

enum class AppRuleMode { STRICT, CYCLE, ALLOWED }

@Composable
fun AppRuleEditor(label: String, pkg: String, allowed: Boolean, rule: AppCycleRule?, locked: Boolean, dismiss: () -> Unit, save: (AppRuleMode, AppCycleRule?) -> Unit) {
    var mode by remember(pkg) { mutableStateOf(if (allowed) AppRuleMode.ALLOWED else if (rule != null) AppRuleMode.CYCLE else AppRuleMode.STRICT) }
    var use by remember(pkg) { mutableStateOf((rule?.useMinutes ?: 2).toString()) }
    var rest by remember(pkg) { mutableStateOf((rule?.restMinutes ?: 5).toString()) }
    val useValue = use.toIntOrNull()
    val restValue = rest.toIntOrNull()
    val valid = useValue != null && restValue != null && useValue in 1..180 && restValue in 1..180
    AlertDialog(onDismissRequest = dismiss, title = { Text("$label 的使用规则") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (locked) "当前限制时段，仅可查看；结束后才能修改。" else "仅在时间表的限制时段内执行，空闲时正常使用。")
            listOf(AppRuleMode.STRICT to "完全限制", AppRuleMode.CYCLE to "使用后冷却", AppRuleMode.ALLOWED to "始终允许（白名单）").forEach { (value, title) ->
                Row(Modifier.fillMaxWidth().selectable(mode == value, enabled = !locked, role = Role.RadioButton, onClick = { mode = value }).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == value, onClick = null, enabled = !locked)
                    Text(title, Modifier.padding(start = 12.dp))
                }
            }
            if (mode == AppRuleMode.CYCLE) {
                OutlinedTextField(use, { use = it.filter(Char::isDigit).take(3) }, label = { Text("每轮可使用（分钟）") }, enabled = !locked, singleLine = true,
                    isError = useValue == null || useValue !in 1..180, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(rest, { rest = it.filter(Char::isDigit).take(3) }, label = { Text("到期冷却（分钟）") }, enabled = !locked, singleLine = true,
                    isError = restValue == null || restValue !in 1..180, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text(if (!valid) "两项均需填写 1–180 的整数。" else "打开后连续使用 $useValue 分钟，到期限制 $restValue 分钟。离开不暂停；冷却结束后，等下次打开才开始新一轮。", style = MaterialTheme.typography.bodySmall)
            }
            if (pkg == WeChatPolicy.PACKAGE) Text("微信已识别的通话页面仍单独放行；普通页面按此规则执行。真实来电兼容性需真机确认。", style = MaterialTheme.typography.bodySmall)
            if (!locked) Text("保存模式或时长变化后，该应用的旧额度与冷却会清空，下次打开按新规则开始。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !locked && (mode != AppRuleMode.CYCLE || valid), onClick = { save(mode, if (mode == AppRuleMode.CYCLE) AppCycleRule(useValue!!, restValue!!) else null) }) { Text("保存应用规则") } },
        dismissButton = { TextButton(onClick = dismiss) { Text(if (locked) "关闭" else "取消") } })
}
