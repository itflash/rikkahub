package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.ai.core.TokenUsage
import kotlin.uuid.Uuid

@Serializable
enum class AgentRunStatus {
    PREPARING, GENERATING, EXECUTING_TOOL, WAITING_APPROVAL, WAITING_INPUT,
    COMPLETED, FAILED, CANCELLED, BUDGET_EXHAUSTED, PAUSED, INTERRUPTED;

    val isWaiting: Boolean get() = this == WAITING_APPROVAL || this == WAITING_INPUT
    val isTerminal: Boolean get() = when (this) {
        COMPLETED, FAILED, CANCELLED, BUDGET_EXHAUSTED, INTERRUPTED -> true
        else -> false
    }
}

/** Execution snapshot; durable boundaries are recorded in AgentCheckpoint. */
@Serializable
data class AgentRun(
    val id: Uuid = Uuid.random(),
    val mode: AgentMode,
    val maxSteps: Int,
    val maxTokens: Int = 200_000,
    val maxActiveMillis: Long = 15 * 60_000L,
    val autoCompact: Boolean = true,
    val subtasksEnabled: Boolean = true,
    val usedTokens: Long = 0,
    val reservedTokens: Int = 0,
    val usageEstimated: Boolean = false,
    val activeMillis: Long = 0,
    val activeStartedAtMillis: Long? = null,
    val contextSummary: String? = null,
    val summarizedMessageCount: Int = 0,
    val toolResultSummary: String? = null,
    val summarizedToolCallIds: Set<String> = emptySet(),
    val compactions: Int = 0,
    val subtasks: List<AgentSubtaskRecord> = emptyList(),
    val status: AgentRunStatus = AgentRunStatus.PREPARING,
    val steps: Int = 0,
    val toolCalls: Int = 0,
    val currentTool: String? = null,
    val currentToolCallId: String? = null,
    // The generation may target a historical branch prefix rather than the full conversation.
    val contextMessageCount: Int? = null,
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
    maxTokens: Int = 200_000,
    maxActiveMillis: Long = 15 * 60_000L,
    autoCompact: Boolean = true,
    subtasksEnabled: Boolean = true,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    companion object {
        fun restore(run: AgentRun, onUpdate: (AgentRun) -> Unit): AgentRunTracker =
            AgentRunTracker(run.mode, run.maxSteps, onUpdate, run.maxTokens, run.maxActiveMillis, run.autoCompact, run.subtasksEnabled).also { it.snapshot = run }
    }

    @Volatile
    var snapshot = AgentRun(mode = mode, maxSteps = maxSteps.coerceIn(1, 256),
        maxTokens = maxTokens.coerceIn(1_024, 2_000_000), maxActiveMillis = maxActiveMillis.coerceIn(1_000, 120 * 60_000L),
        autoCompact = autoCompact, subtasksEnabled = subtasksEnabled)
        private set

    private fun publish(value: AgentRun) {
        snapshot = value
        onUpdate(value)
    }

    @Synchronized
    fun startClock() {
        if (snapshot.activeStartedAtMillis == null) publish(snapshot.copy(activeStartedAtMillis = nowMillis()))
    }

    @Synchronized
    fun pauseClock() {
        val started = snapshot.activeStartedAtMillis ?: return
        publish(snapshot.copy(activeMillis = snapshot.activeMillis + (nowMillis() - started).coerceAtLeast(0), activeStartedAtMillis = null))
    }

    @Synchronized
    fun remainingMillis(): Long = (snapshot.maxActiveMillis - snapshot.activeMillis -
        (snapshot.activeStartedAtMillis?.let { (nowMillis() - it).coerceAtLeast(0) } ?: 0)).coerceAtLeast(0)

    @Synchronized
    fun reserveTokens(inputEstimate: Int, requestedOutput: Int): Int {
        check(snapshot.reservedTokens == 0) { "Unsettled model request" }
        val remaining = snapshot.maxTokens.toLong() - snapshot.usedTokens - inputEstimate
        if (remaining < 256) exhaust("Token 预算不足，任务已停止。")
        val output = minOf(requestedOutput.coerceAtLeast(1), remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        publish(snapshot.copy(reservedTokens = inputEstimate + output))
        return output
    }

    @Synchronized
    fun settleTokens(usage: TokenUsage?) {
        val reported = usage?.let { maxOf(it.totalTokens.toLong(), it.promptTokens.toLong() + it.completionTokens) }
            ?.takeIf { it > 0 }
        val charged = reported ?: snapshot.reservedTokens.toLong()
        publish(snapshot.copy(usedTokens = snapshot.usedTokens + charged, reservedTokens = 0,
            usageEstimated = snapshot.usageEstimated || reported == null))
    }

    @Synchronized
    fun checkResourceBudget() {
        if (snapshot.usedTokens >= snapshot.maxTokens) exhaust("Token 预算已用尽，任务已停止。")
        if (remainingMillis() <= 0) exhaust("执行时间预算已用尽，任务已停止。")
    }

    @Synchronized
    fun exhaust(reason: String): Nothing {
        pauseClock()
        publish(snapshot.copy(status = AgentRunStatus.BUDGET_EXHAUSTED, detail = reason))
        throw AgentResourceBudgetExceeded(reason)
    }

    @Synchronized
    fun saveSummary(summary: String, through: Int) {
        require(summary.isNotBlank() && through >= snapshot.summarizedMessageCount)
        publish(snapshot.copy(contextSummary = summary, summarizedMessageCount = through, compactions = snapshot.compactions + 1))
    }

    @Synchronized
    fun saveToolSummary(summary: String, callIds: Set<String>) {
        require(summary.isNotBlank() && callIds.isNotEmpty())
        publish(snapshot.copy(toolResultSummary = summary,
            summarizedToolCallIds = snapshot.summarizedToolCallIds + callIds, compactions = snapshot.compactions + 1))
    }

    @Synchronized
    fun subtaskUpdate(record: AgentSubtaskRecord) {
        publish(snapshot.copy(subtasks = (snapshot.subtasks.filterNot { it.id == record.id } + record).takeLast(8)))
    }

    @Synchronized
    fun recordChildTool() { publish(snapshot.copy(toolCalls = snapshot.toolCalls + 1)) }

    @Synchronized
    fun resume(mode: AgentMode) {
        check(snapshot.status.isWaiting || snapshot.status == AgentRunStatus.PAUSED) { "Agent 任务已结束，不能继续旧的工具调用，请重新发起任务。" }
        // Permissions follow the current conversation; the run's original budget stays fixed.
        publish(snapshot.copy(mode = mode, status = AgentRunStatus.PREPARING, detail = null))
    }

    @Synchronized
    fun recordContextSize(count: Int) {
        require(count >= 0)
        if (snapshot.status.isTerminal) return
        publish(snapshot.copy(contextMessageCount = count))
    }

    @Synchronized
    fun beginStep() {
        check(!snapshot.status.isTerminal) { "Agent task is no longer active" }
        checkResourceBudget()
        if (snapshot.steps >= snapshot.maxSteps) {
            publish(snapshot.copy(status = AgentRunStatus.BUDGET_EXHAUSTED, currentTool = null))
            throw AgentStepBudgetExceeded(snapshot.maxSteps)
        }
        publish(snapshot.copy(status = AgentRunStatus.GENERATING, steps = snapshot.steps + 1, currentTool = null))
    }

    @Synchronized
    fun beginTool(name: String, callId: String? = null) {
        check(!snapshot.status.isTerminal) { "Agent task is no longer active" }
        publish(snapshot.copy(status = AgentRunStatus.EXECUTING_TOOL, currentTool = name,
            toolCalls = snapshot.toolCalls + 1, currentToolCallId = callId))
    }

    @Synchronized
    fun finishTool() {
        if (snapshot.status.isTerminal) return
        publish(snapshot.copy(status = AgentRunStatus.PREPARING, currentTool = null, currentToolCallId = null))
    }

    @Synchronized
    fun waitForUser(needsAnswer: Boolean) {
        if (snapshot.status.isTerminal) return
        pauseClock()
        publish(snapshot.copy(
            status = if (needsAnswer) AgentRunStatus.WAITING_INPUT else AgentRunStatus.WAITING_APPROVAL,
            currentTool = null,
        ))
    }

    @Synchronized
    fun complete() {
        if (snapshot.status.isTerminal || snapshot.status.isWaiting || snapshot.status == AgentRunStatus.PAUSED) return
        pauseClock()
        publish(snapshot.copy(status = AgentRunStatus.COMPLETED, currentTool = null))
    }

    @Synchronized
    fun interrupt(reason: String) {
        if (snapshot.status.isTerminal) return
        pauseClock()
        publish(snapshot.copy(status = AgentRunStatus.INTERRUPTED, detail = reason.take(300)))
    }

    @Synchronized
    fun fail(error: Throwable) {
        if (snapshot.status.isTerminal) return
        pauseClock()
        if (snapshot.currentToolCallId != null) {
            interrupt("工具调用已中断，结果无法确认；请核对实际结果后再发起新任务。${error.message.orEmpty()}")
            return
        }
        publish(snapshot.copy(
            status = if (error is CancellationException) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,
            currentTool = null,
            detail = if (error is CancellationException) null else error.message?.take(300),
        ))
    }
}

class AgentResourceBudgetExceeded(message: String) : IllegalStateException(message)
