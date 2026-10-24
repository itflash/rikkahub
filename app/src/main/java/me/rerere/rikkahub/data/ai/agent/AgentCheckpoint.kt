package me.rerere.rikkahub.data.ai.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest

@Serializable
enum class AgentCheckpointPhase {
    READY, MODEL_REQUEST, TOOL_IN_FLIGHT, WAITING_USER, FINISHED,
}

/** The messages and this journal entry must commit in the same database transaction. */
@Serializable
data class AgentCheckpoint(
    val version: Int = 1,
    val run: AgentRun,
    val phase: AgentCheckpointPhase,
    val fingerprint: String,
) {
    fun recover(conversation: Conversation): AgentRun {
        if (version != 1 || run.steps !in 0..run.maxSteps || run.maxSteps !in 1..256 || run.toolCalls < 0) {
            return run.interrupted("检查点版本或计数无效，不能安全恢复。请重新发起任务。")
        }
        val count = run.contextMessageCount ?: conversation.currentMessages.size
        if (count !in 0..conversation.currentMessages.size) {
            return run.interrupted("检查点上下文范围无效，不能安全恢复。")
        }
        if (fingerprint != agentCheckpointFingerprint(conversation)) {
            return run.interrupted("消息、分支或运行配置已变化，旧任务不能继续。请重新发起任务。")
        }
        if (run.status.isTerminal) return run
        if (phase == AgentCheckpointPhase.TOOL_IN_FLIGHT || run.currentToolCallId != null) {
            return run.interrupted("工具执行期间发生中断，结果无法确认。为避免重复副作用，不会重放；请先核对工作区或外部结果。")
        }
        if (phase == AgentCheckpointPhase.FINISHED) return run.copy(status = AgentRunStatus.COMPLETED)
        val pending = conversation.currentMessages.take(count).lastOrNull()?.getTools().orEmpty().filter { it.isPending }
        val status = when {
            phase != AgentCheckpointPhase.MODEL_REQUEST && pending.isNotEmpty() ->
                if (pending.any { it.toolName == "ask_user" }) AgentRunStatus.WAITING_INPUT else AgentRunStatus.WAITING_APPROVAL
            else -> AgentRunStatus.PAUSED
        }
        return run.copy(status = status, currentTool = null, detail = null)
    }
}

private fun AgentRun.interrupted(reason: String) = copy(status = AgentRunStatus.INTERRUPTED, detail = reason)

fun restoreAgentCheckpoint(data: String?, conversation: Conversation): AgentRun? {
    if (data == null) return null
    return try {
        JsonInstant.decodeFromString<AgentCheckpoint>(data).recover(conversation)
    } catch (_: IllegalArgumentException) {
        AgentRun(mode = conversation.config?.agentMode ?: AgentMode.PLAN, maxSteps = 32)
            .interrupted("检查点已损坏，不能安全恢复。请重新发起任务。")
    }
}

/** Approval decisions may change; inputs, outputs, selected branch and workspace may not. */
fun agentCheckpointFingerprint(conversation: Conversation): String {
    val content = buildJsonObject {
        put("id", conversation.id.toString())
        put("assistant", conversation.assistantId.toString())
        put("config", JsonInstant.encodeToJsonElement(conversation.config?.copy(agentMode = AgentMode.CHAT, agentMaxSteps = 32)))
        put("cwd", conversation.workspaceCwd)
        put("system", conversation.customSystemPrompt)
        put("modes", JsonInstant.encodeToJsonElement(conversation.modeInjectionIds.sortedBy { it.toString() }))
        put("lorebooks", JsonInstant.encodeToJsonElement(conversation.lorebookIds.sortedBy { it.toString() }))
        put("messages", buildJsonArray {
            conversation.currentMessages.forEach { message ->
                add(buildJsonObject {
                    put("id", message.id.toString())
                    put("role", JsonPrimitive(message.role.name))
                    put("parts", JsonInstant.encodeToJsonElement(message.parts.map { part ->
                        if (part is UIMessagePart.Tool) part.copy(approvalState = ToolApprovalState.Auto) else part
                    }))
                })
            }
        })
    }
    return MessageDigest.getInstance("SHA-256").digest(content.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** A prior process's approval is not a new authorization for a changed tool/server environment. */
fun resetRecoveredApprovals(conversation: Conversation, messageCount: Int? = null): Conversation {
    val index = (messageCount ?: conversation.currentMessages.size) - 1
    val last = conversation.currentMessages.getOrNull(index) ?: return conversation
    val parts = last.parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted && part.approvalState is ToolApprovalState.Approved) {
            part.copy(approvalState = ToolApprovalState.Pending)
        } else part
    }
    if (parts == last.parts) return conversation
    return conversation.updateCurrentMessages(conversation.currentMessages.mapIndexed { i, message ->
        if (i == index) last.copy(parts = parts) else message
    })
}
