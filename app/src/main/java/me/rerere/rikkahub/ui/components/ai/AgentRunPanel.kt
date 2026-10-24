package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.ai.agent.AgentRun
import me.rerere.rikkahub.data.ai.agent.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentMode

@Composable
fun AgentRunPanel(run: AgentRun, onStop: () -> Unit, onResume: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${if (run.mode == AgentMode.PLAN) "计划" else "执行"} · ${run.status.label}",
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
                Text(
                    "模型步骤 ${run.steps}/${run.maxSteps} · 工具调用 ${run.toolCalls}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                run.currentTool?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (run.status == AgentRunStatus.BUDGET_EXHAUSTED) {
                    Text("已保留现有结果。检查后可重新发起任务。", style = MaterialTheme.typography.bodySmall)
                }
                run.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3) }
            }
            if (run.status == AgentRunStatus.PAUSED) {
                TextButton(onClick = onResume) { Text("继续") }
            }
            if (!run.status.isTerminal) {
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}

private val AgentRunStatus.label: String
    get() = when (this) {
        AgentRunStatus.PAUSED -> "已暂停 · 可恢复"
        AgentRunStatus.INTERRUPTED -> "已中断 · 需核对"
        AgentRunStatus.PREPARING -> "准备中"
        AgentRunStatus.GENERATING -> "生成中"
        AgentRunStatus.EXECUTING_TOOL -> "调用工具"
        AgentRunStatus.WAITING_APPROVAL -> "等待批准"
        AgentRunStatus.WAITING_INPUT -> "等待回答"
        AgentRunStatus.COMPLETED -> "已完成"
        AgentRunStatus.FAILED -> "失败"
        AgentRunStatus.CANCELLED -> "已取消"
        AgentRunStatus.BUDGET_EXHAUSTED -> "步数用尽"
    }
