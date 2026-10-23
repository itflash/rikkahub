package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.ui.components.ui.CardGroup

@Composable
fun AgentModeButton(
    mode: AgentMode,
    enabled: Boolean,
    onModeChange: (AgentMode) -> Unit,
    maxSteps: Int,
    onMaxStepsChange: (Int) -> Unit,
) {
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
                                Text("每轮模型生成计一步，网络重试仍算同一步；审批续跑累计计算。修改仅对下一次新任务生效。")
                            },
                        ) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf(8, 32, 64, 128).forEach { steps ->
                                    FilterChip(
                                        selected = maxSteps == steps,
                                        onClick = { onMaxStepsChange(steps) },
                                        label = { Text("$steps 步") },
                                    )
                                }
                            }
                        }
                    }
                }
                CardGroup {
                    AgentMode.entries.forEach { option ->
                        item(
                            onClick = {
                                onModeChange(option)
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
