package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.AgentMode
import kotlin.uuid.Uuid

enum class AgentRunStatus {
    PREPARING, GENERATING, EXECUTING_TOOL, WAITING_APPROVAL, WAITING_INPUT,
    COMPLETED, FAILED, CANCELLED, BUDGET_EXHAUSTED;

    val isWaiting: Boolean get() = this == WAITING_APPROVAL || this == WAITING_INPUT
    val isTerminal: Boolean get() = when (this) {
        COMPLETED, FAILED, CANCELLED, BUDGET_EXHAUSTED -> true
        else -> false
    }
}

/** In-memory execution snapshot. It is not a durable recovery checkpoint. */
data class AgentRun(
    val id: Uuid = Uuid.random(),
    val mode: AgentMode,
    val maxSteps: Int,
    val status: AgentRunStatus = AgentRunStatus.PREPARING,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    val currentTool: String? = null,
    val detail: String? = null,
)

class AgentStepBudgetExceeded(maxSteps: Int) : IllegalStateException(
    "Agent 已达到 $maxSteps 步上限，任务已停止。请检查已有结果后重新发起任务。",
)

/** One tracker per run: late callbacks cannot mutate a successor's state. */
class AgentRunTracker(
    mode: AgentMode,
    maxSteps: Int,
    private val onUpdate: (AgentRun) -> Unit = {},
) {
    @Volatile
    var snapshot = AgentRun(mode = mode, maxSteps = maxSteps.coerceIn(1, 256))
        private set

    private fun publish(value: AgentRun) {
        snapshot = value
        onUpdate(value)
    }

    @Synchronized
    fun resume(mode: AgentMode) {
        check(snapshot.status.isWaiting) { "Agent 任务已结束，不能继续旧的工具调用，请重新发起任务。" }
        // Permissions follow the current conversation; the run's original budget stays fixed.
        publish(snapshot.copy(mode = mode, status = AgentRunStatus.PREPARING, detail = null))
    }

    @Synchronized
    fun beginStep() {
        check(!snapshot.status.isTerminal) { "Agent task is no longer active" }
        if (snapshot.steps >= snapshot.maxSteps) {
            publish(snapshot.copy(status = AgentRunStatus.BUDGET_EXHAUSTED, currentTool = null))
            throw AgentStepBudgetExceeded(snapshot.maxSteps)
        }
        publish(snapshot.copy(status = AgentRunStatus.GENERATING, steps = snapshot.steps + 1, currentTool = null))
    }

    @Synchronized
    fun beginTool(name: String) {
        check(!snapshot.status.isTerminal) { "Agent task is no longer active" }
        publish(snapshot.copy(status = AgentRunStatus.EXECUTING_TOOL, currentTool = name,
            toolCalls = snapshot.toolCalls + 1))
    }

    @Synchronized
    fun waitForUser(needsAnswer: Boolean) {
        if (snapshot.status.isTerminal) return
        publish(snapshot.copy(
            status = if (needsAnswer) AgentRunStatus.WAITING_INPUT else AgentRunStatus.WAITING_APPROVAL,
            currentTool = null,
        ))
    }

    @Synchronized
    fun complete() {
        if (snapshot.status.isTerminal || snapshot.status.isWaiting) return
        publish(snapshot.copy(status = AgentRunStatus.COMPLETED, currentTool = null))
    }

    @Synchronized
    fun fail(error: Throwable) {
        if (snapshot.status.isTerminal) return
        publish(snapshot.copy(
            status = if (error is CancellationException) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,
            currentTool = null,
            detail = if (error is CancellationException) null else error.message?.take(300),
        ))
    }
}
