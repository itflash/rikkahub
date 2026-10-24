package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.agent.AgentRun
import me.rerere.rikkahub.data.ai.agent.AgentRunTracker
import me.rerere.rikkahub.data.ai.agent.AgentRunStatus
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val IDLE_TIMEOUT_MS = 5_000L

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initializationMutex = Mutex()
    private val metadataMutex = Mutex()
    @Volatile
    private var initialized = false
    val messageQueue = MessageQueue()

    // 页面切换和 SSE 重连只加载一次；活跃 session 的内存状态始终优先。
    suspend fun initialize(load: suspend () -> Conversation) {
        initializationMutex.withLock {
            if (initialized) return
            val conversation = load()
            synchronized(this) {
                // 加载挂起期间可能已经通过保存或编辑写入了更新的状态。
                if (!initialized) updateConversation(conversation)
            }
        }
    }

    @Synchronized
    fun updateConversation(conversation: Conversation) {
        require(conversation.id == id)
        _state.value = conversation
        initialized = true
    }

    // 元数据先应用到最新内存状态；落库只更新对应列，不能用旧消息快照覆盖流式输出。
    internal suspend fun updateMetadata(
        update: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        metadataMutex.withLock {
            val updated = synchronized(this) {
                update(state.value).also(::updateConversation)
            }
            persist(updated)
        }
    }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(save: suspend (Conversation) -> Unit): Conversation =
        withContext(NonCancellable) {
            val current = state.value
            val conversation = current.copy(
                messageNodes = current.messageNodes.map { node ->
                    node.copy(messages = node.messages.map { it.finishReasoning() })
                },
                updateAt = Instant.now(),
            )
            updateConversation(conversation)
            save(conversation)
            conversation
        }

    // 从队列取出到写入会话历史之间，附件仍需作为有效引用保留。
    @Volatile
    var submittingMessage: QueuedMessage? = null
        internal set

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    private val _agentRun = MutableStateFlow<AgentRun?>(null)
    val agentRun: StateFlow<AgentRun?> = _agentRun.asStateFlow()
    private var agentTracker: AgentRunTracker? = null

    @Synchronized
    fun restoreAgentRun(run: AgentRun?) {
        if (agentTracker != null || run == null) return
        _agentRun.value = run
        agentTracker = AgentRunTracker.restore(run) { update ->
            _agentRun.update { current -> if (current?.id == update.id) update else current }
        }
    }

    @Synchronized
    fun beginAgentRun(mode: AgentMode, maxSteps: Int, resume: Boolean): AgentRunTracker? {
        if (mode == AgentMode.CHAT) {
            agentTracker?.fail(CancellationException("Switched to chat mode"))
            agentTracker = null
            _agentRun.value = null
            return null
        }
        if (resume) {
            val tracker = checkNotNull(agentTracker) {
                "当前没有可恢复的 Agent 任务，无法安全恢复旧的工具调用。请停止旧调用后重新发起任务。"
            }
            tracker.resume(mode)
            return tracker
        }
        agentTracker?.fail(CancellationException("Replaced by a new task"))
        val tracker = AgentRunTracker(mode, maxSteps) { update ->
            // A cancelled predecessor must not overwrite the next task's progress.
            _agentRun.update { current -> if (current?.id == update.id) update else current }
        }
        agentTracker = tracker
        _agentRun.value = tracker.snapshot
        return tracker
    }

    fun interruptAgentRun(reason: String) {
        agentTracker?.interrupt(reason)
    }

    @Synchronized
    fun cancelAgentRun() {
        agentTracker?.fail(CancellationException("Stopped by user"))
        if (refCount.get() <= 0 && !isGenerating) scheduleIdleCheck()
    }

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null ||
                messageQueue.state.value.messages.isNotEmpty() || agentRun.value?.status?.isWaiting == true ||
                agentRun.value?.status == AgentRunStatus.PAUSED

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // Cancel waiters first so a predecessor finishing cannot start the next approval.
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        cancelAgentRun()
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

/** Serialize approval saves without cancelling earlier decisions; stopping cancels the whole chain. */
internal suspend fun afterPreviousGeneration(previous: Job?, block: suspend () -> Unit) {
    try {
        previous?.join()
        block()
    } catch (e: CancellationException) {
        previous?.cancel()
        withContext(NonCancellable) { previous?.join() }
        throw e
    }
}
