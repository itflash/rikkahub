package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
fun AgentModeButton(
    assistant: Assistant,
    enabled: Boolean,
    onUpdate: (Assistant) -> Unit,
) {
    val mode = assistant.agentMode
    val maxSteps = assistant.agentMaxSteps
    var showPicker by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showPicker = false }
    TextButton(onClick = { showPicker = true }, enabled = enabled) {
        Text(mode.label)
    }
    if (showPicker && enabled) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Agent 模式", style = MaterialTheme.typography.titleLarge)
                Text(
                    "会话开始后，模式仅对当前会话生效。运行中请先停止，再切换模式。计划和执行模式均暂停自动记忆整理，不使用自定义请求体或无法走本地审批的服务端生成工具。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (mode != AgentMode.CHAT) {
                    CardGroup {
                        formItem(
                            headlineContent = { Text("任务步数上限：${maxSteps.coerceIn(1, 256)}") },
                            supportingContent = {
                                Text("主任务、压缩和子任务共享预算；审批等待不计执行时间。预算与能力开关仅对下一次新任务生效。")
                            },
                        ) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(8, 32, 64, 128).forEach { steps ->
                                    FilterChip(
                                        selected = maxSteps == steps,
                                        onClick = { onUpdate(assistant.copy(agentMaxSteps = steps)) },
                                        label = { Text("$steps 步") },
                                    )
                                }
                            }
                        }
                    }
                }
                if (mode != AgentMode.CHAT) {
                    CardGroup {
                        formItem(headlineContent = { Text("Token 预算：${assistant.agentMaxTokens}") },
                            supportingContent = { Text("按提供商用量累计；缺失用量时保守估算，不是账单金额或绝对费用上限。") }) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(50_000, 200_000, 1_000_000).forEach { tokens ->
                                    FilterChip(selected = assistant.agentMaxTokens == tokens,
                                        onClick = { onUpdate(assistant.copy(agentMaxTokens = tokens)) },
                                        label = { Text("${tokens / 1000}k") })
                                }
                            }
                        }
                        formItem(headlineContent = { Text("执行时间：${assistant.agentMaxMinutes} 分钟") }) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5, 15, 30, 60).forEach { minutes ->
                                    FilterChip(selected = assistant.agentMaxMinutes == minutes,
                                        onClick = { onUpdate(assistant.copy(agentMaxMinutes = minutes)) },
                                        label = { Text("$minutes 分钟") })
                                }
                            }
                        }
                        item(headlineContent = { Text("自动压缩上下文") },
                            supportingContent = { Text("接近上下文窗口时压缩较早内容，保留原始聊天记录；压缩也消耗共享预算。") },
                            trailingContent = { Switch(checked = assistant.agentAutoCompact,
                                onCheckedChange = { onUpdate(assistant.copy(agentAutoCompact = it)) }) })
                        item(headlineContent = { Text("只读子 Agent") },
                            supportingContent = { Text("允许委派独立的调查或审查任务；串行执行，不能写文件、运行 Shell、调用未知工具或再委派。") },
                            trailingContent = { Switch(checked = assistant.agentSubtasks,
                                onCheckedChange = { onUpdate(assistant.copy(agentSubtasks = it)) }) })
                    }
                }
                CardGroup {
                    AgentMode.entries.forEach { option ->
                        item(
                            onClick = {
                                onUpdate(assistant.copy(agentMode = option))
                                showPicker = false
                            },
                            headlineContent = { Text(option.label) },
                            supportingContent = { Text(option.description) },
                            trailingContent = {
                                RadioButton(selected = mode == option, onClick = null)
                            },
                        )
                    }
                }
            }
        }
    }
}

private val AgentMode.label: String
    get() = when (this) {
        AgentMode.CHAT -> "聊天"
        AgentMode.PLAN -> "计划"
        AgentMode.EXECUTE -> "执行"
    }

private val AgentMode.description: String
    get() = when (this) {
        AgentMode.CHAT -> "保持原有聊天行为，遵循现有工具审批设置。"
        AgentMode.PLAN -> "只调查和制定计划。仅开放已确认只读的工具，不开放 Shell、未知 MCP 工具或自定义请求体。"
        AgentMode.EXECUTE -> "连续调用工具完成任务。写入、命令执行及未知工具均需批准；只读工具保留原有审批要求。"
    }
