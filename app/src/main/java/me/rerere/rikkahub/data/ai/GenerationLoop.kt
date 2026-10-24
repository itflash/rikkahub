package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.agent.AgentResourceBudgetExceeded
import me.rerere.rikkahub.data.ai.agent.AgentStepBudgetExceeded
import me.rerere.rikkahub.data.ai.agent.agentInputEstimate
import me.rerere.rikkahub.data.ai.agent.agentContextMessages
import me.rerere.rikkahub.data.ai.agent.agentCompactionCut
import me.rerere.rikkahub.data.ai.agent.agentCompactionPrompt
import me.rerere.rikkahub.data.ai.agent.agentToolCompactionIds
import me.rerere.rikkahub.data.ai.agent.agentToolCompactionPrompt
import me.rerere.rikkahub.data.ai.agent.createAgentSubtaskTool
import me.rerere.rikkahub.data.ai.agent.AgentRunTracker
import me.rerere.rikkahub.data.ai.agent.AgentCheckpointPhase
import me.rerere.rikkahub.data.ai.agent.AgentPolicy
import me.rerere.rikkahub.data.ai.agent.prepareAgentToolCalls
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L

private class AgentCheckpointWriteException(cause: Throwable) : RuntimeException(cause)

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

// An empty result must still mark the call as executed; otherwise a tool-only iteration
// could repeat its side effects without consuming another model step.
internal fun completedToolOutput(output: List<UIMessagePart>): List<UIMessagePart> =
    output.ifEmpty { listOf(UIMessagePart.Text("Tool completed without output.")) }

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        agentRun: AgentRunTracker? = null,
        onAgentCheckpoint: suspend (List<UIMessage>, AgentCheckpointPhase) -> Unit = { _, _ -> },
    ): Flow<GenerationChunk> = flow {
        agentRun?.startClock()
        try {
            val finishedInTime = withTimeoutOrNull(agentRun?.remainingMillis()?.coerceAtLeast(1) ?: Long.MAX_VALUE) {
                val policy = AgentPolicy(assistant.agentMode)
                val model = policy.model(model)
                var tools = policy.tools(tools)
                val provider = model.findProvider(settings.providers) ?: error("Provider not found")
                val providerImpl = providerManager.getProviderByType(provider)

                var messages: List<UIMessage> = messages
                suspend fun checkpoint(phase: AgentCheckpointPhase) {
                    val tracker = agentRun ?: return
                    tracker.recordContextSize(messages.size)
                    try { onAgentCheckpoint(messages, phase) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { throw AgentCheckpointWriteException(error) }
                }

                if (agentRun != null && agentRun.snapshot.subtasksEnabled) {
                    tools = tools + createAgentSubtaskTool(agentRun, tools,
                        beforeWork = { checkpoint(AgentCheckpointPhase.TOOL_IN_FLIGHT) },
                        request = { childMessages, childTools, outputLimit ->
                            check(agentInputEstimate(childMessages, childTools).toLong() + outputLimit <= model.contextLength) {
                                "子任务上下文超过模型窗口。"
                            }
                            providerImpl.generateText(provider, childMessages, TextGenerationParams(
                                model = model.copy(tools = emptySet()), tools = childTools, maxTokens = outputLimit,
                                temperature = assistant.temperature, reasoningLevel = assistant.reasoningLevel,
                                customHeaders = assistant.customHeaders + model.customHeaders,
                            ))
                        },
                        contextLength = model.contextLength,
                    )
                }
                val iterationLimit = if (agentRun != null) agentRun.snapshot.maxSteps + 1 else maxSteps
                for (stepIndex in 0 until iterationLimit) {
                    currentCoroutineContext().ensureActive()
                    Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

                    // Unfinished calls must pass policy checks before another provider request.
                    val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                        !it.isExecuted
                    } ?: emptyList()

                    val toolsToProcess: List<UIMessagePart.Tool>

                    // Also handles a pending-only batch without sending it back to the provider.
                    if (pendingTools.isEmpty()) {
                        if (agentRun != null && agentRun.snapshot.autoCompact) {
                            val cut = agentCompactionCut(messages, agentRun.snapshot, model.contextLength, tools)
                            val toolIds = if (cut == null) agentToolCompactionIds(messages, agentRun.snapshot, model.contextLength, tools) else emptySet()
                            if (cut != null || toolIds.isNotEmpty()) {
                                agentRun.beginStep()
                                val summaryMessages = listOf(UIMessage.user(if (cut != null) {
                                    agentCompactionPrompt(messages, agentRun.snapshot, cut)
                                } else agentToolCompactionPrompt(messages, agentRun.snapshot, toolIds)))
                                val summaryEstimate = agentInputEstimate(summaryMessages)
                                val summaryOutput = minOf(2048, model.contextLength - summaryEstimate)
                                check(summaryOutput >= 256) {
                                    "压缩输入仍超过模型窗口，已保留原始记录；请手动压缩或选择更大的上下文模型。"
                                }
                                val outputLimit = agentRun.reserveTokens(summaryEstimate, summaryOutput)
                                val result = try {
                                    checkpoint(AgentCheckpointPhase.MODEL_REQUEST)
                                    providerImpl.generateText(provider, summaryMessages, TextGenerationParams(
                                        model = model.copy(tools = emptySet()), maxTokens = outputLimit,
                                        customHeaders = assistant.customHeaders + model.customHeaders,
                                    ))
                                } catch (error: Throwable) { agentRun.settleTokens(null); throw error }
                                agentRun.settleTokens(result.usage)
                                val summary = result.message.toText().trim()
                                check(summary.isNotEmpty()) { "自动压缩没有返回摘要，已停止以免丢失上下文。" }
                                if (cut != null) agentRun.saveSummary(summary.take(32_000), cut)
                                else agentRun.saveToolSummary(summary.take(32_000), toolIds)
                                checkpoint(AgentCheckpointPhase.READY)
                            }
                        }
                        agentRun?.beginStep()
                        generateInternal(
                            assistant = assistant,
                            settings = settings,
                            messages = messages,
                            agentRun = agentRun,
                            beforeRequest = { checkpoint(AgentCheckpointPhase.MODEL_REQUEST) },
                            onUpdateMessages = {
                                messages = it.transforms(
                                    transformers = outputTransformers,
                                    context = context,
                                    model = model,
                                    assistant = assistant,
                                    settings = settings
                                )
                                emit(
                                    GenerationChunk.Messages(
                                        messages.visualTransforms(
                                            transformers = outputTransformers,
                                            context = context,
                                            model = model,
                                            assistant = assistant,
                                            settings = settings
                                        )
                                    )
                                )
                            },
                            transformers = inputTransformers,
                            model = model,
                            providerImpl = providerImpl,
                            provider = provider,
                            tools = tools,
                            memories = memories ?: emptyList(),
                            stream = assistant.streamOutput,
                            processingStatus = processingStatus,
                            conversationSystemPrompt = conversationSystemPrompt,
                            conversationId = conversationId,
                            workspaceCwd = workspaceCwd,
                        )
                        messages = messages.visualTransforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        messages = messages.onGenerationFinish(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                            finishedAt = Clock.System.now()
                                .toLocalDateTime(TimeZone.currentSystemDefault())
                        )
                        emit(GenerationChunk.Messages(messages))
                        checkpoint(
                            if (messages.last().getTools().none { !it.isExecuted }) AgentCheckpointPhase.FINISHED
                            else AgentCheckpointPhase.READY,
                        )

                        toolsToProcess = messages.last().getTools().filter { !it.isExecuted }
                    } else {
                        // Recheck the whole unfinished batch, not just the approved call. A mode change
                        // can require approval for siblings that were previously auto-approved.
                        toolsToProcess = messages.last().getTools().filter { !it.isExecuted }
                    }

                    if (toolsToProcess.isEmpty()) {
                        checkpoint(AgentCheckpointPhase.FINISHED)
                        break
                    }
                    val checkedTools = prepareAgentToolCalls(toolsToProcess, tools)
                    if (checkedTools != toolsToProcess) {
                        val lastMessage = messages.last()
                        messages = messages.dropLast(1) + lastMessage.copy(parts = lastMessage.parts.map { part ->
                            if (part is UIMessagePart.Tool) {
                                checkedTools.find { it.toolCallId == part.toolCallId } ?: part
                            } else part
                        })
                        emit(GenerationChunk.Messages(messages))
                    }
                    if (checkedTools.any { it.approvalState is ToolApprovalState.Pending }) {
                        agentRun?.waitForUser(checkedTools.any {
                            it.toolName == "ask_user" && it.approvalState is ToolApprovalState.Pending
                        })
                        checkpoint(AgentCheckpointPhase.WAITING_USER)
                        Log.i(TAG, "generateText: waiting for tool approval")
                        break
                    }

                    // Handle tools (execute approved tools, handle denied tools)
                    checkedTools.forEach { tool ->
                        agentRun?.checkResourceBudget()
                        val executedTools = arrayListOf<UIMessagePart.Tool>()
                        when (tool.approvalState) {
                            is ToolApprovalState.Denied -> {
                                // Tool was denied by user
                                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive("Tool execution denied. Reason: ${reason.ifBlank { "No reason provided" }}")
                                                    )
                                                }
                                            )
                                        )
                                    )
                                )
                            }

                            is ToolApprovalState.Answered -> {
                                // Tool was answered by user (e.g., ask_user tool)
                                val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(answer)
                                    )
                                )
                            }

                            is ToolApprovalState.Pending -> {
                                // Should not reach here, but just in case
                            }

                            else -> {
                                // The durable write-ahead marker is OUTSIDE the tool-error handler:
                                // a failed commit must abort the run, never become a model-visible retry.
                                currentCoroutineContext().ensureActive()
                                agentRun?.beginTool(tool.toolName, tool.toolCallId)
                                checkpoint(AgentCheckpointPhase.TOOL_IN_FLIGHT)
                                var executionStarted = false
                                runCatching {
                                    val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                        ?: error("Tool ${tool.toolName} not found")
                                    val args = runCatching {
                                        json.parseToJsonElement(tool.input.ifBlank { "{}" })
                                    }.getOrElse {
                                        error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                                    }
                                    Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                                    currentCoroutineContext().ensureActive()
                                    executionStarted = true
                                    val result = completedToolOutput(toolDef.execute(args))
                                    val hasShellAccess = tools.any { it.name == "workspace_shell" }
                                    executedTools += tool.copy(
                                        output = maybeTruncateToolOutput(tool, result, hasShellAccess)
                                    )
                                }.onFailure {
                                    // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                                    if (it is CancellationException || it is AgentResourceBudgetExceeded || it is AgentStepBudgetExceeded || it is AgentCheckpointWriteException) throw it
                                    if (agentRun != null && executionStarted && tools.find { def -> def.name == tool.toolName }?.readOnly != true) {
                                        agentRun.interrupt("${tool.toolName} 执行出错，无法确认是否已产生副作用；请核对实际结果，不能自动重试。")
                                        throw it
                                    }
                                    it.printStackTrace()
                                    executedTools += tool.copy(
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(
                                                    buildJsonObject {
                                                        put(
                                                            "error",
                                                            JsonPrimitive(buildString {
                                                                append("[${it.javaClass.name}] ${it.message}")
                                                                append("\n${it.stackTraceToString()}")
                                                            })
                                                        )
                                                    }
                                                )
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        if (executedTools.isNotEmpty()) {
                            val lastMessage = messages.last()
                            messages = messages.dropLast(1) + lastMessage.copy(parts = lastMessage.parts.map { part ->
                                if (part is UIMessagePart.Tool) {
                                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                                } else part
                            })
                            emit(GenerationChunk.Messages(messages.transforms(
                                transformers = outputTransformers, context = context, model = model,
                                assistant = assistant, settings = settings,
                            )))
                            agentRun?.finishTool()
                            checkpoint(AgentCheckpointPhase.READY)
                        }
                    }
                }
                true
            }
            if (finishedInTime == null) agentRun?.exhaust("执行时间预算已用尽，任务已停止；若工具正在执行，请核对实际结果。")
        } finally {
            agentRun?.pauseClock()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<MemoryFile>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        agentRun: AgentRunTracker? = null,
        beforeRequest: suspend () -> Unit = {},
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                val agentPrompt = AgentPolicy(assistant.agentMode).systemPrompt
                if (agentPrompt.isNotBlank()) {
                    appendLine()
                    appendLine(agentPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(if (agentRun != null) agentContextMessages(messages, agentRun.snapshot) else messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val inputEstimate = if (agentRun != null) agentInputEstimate(internalMessages, tools) else 0
        val availableOutput = minOf(assistant.maxTokens ?: 4096, model.contextLength - inputEstimate)
        if (agentRun != null) {
            check(availableOutput >= 256) {
                "当前上下文超过模型窗口，请减少附件或使用更大的上下文模型。"
            }
        }
        val outputLimit = agentRun?.reserveTokens(inputEstimate, availableOutput)
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = outputLimit ?: assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = AgentPolicy(assistant.agentMode).customBody(buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }),
            sessionId = (conversationId ?: Uuid.random()).toString(),
        )
        var requestUsage: TokenUsage? = null
        var responseCompleted = false
        try {
            if (agentRun != null) beforeRequest()
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            if (chunk is StreamChunk.Usage) {
                                val merged = requestUsage.merge(chunk.usage)
                                requestUsage = merged.copy(totalTokens = maxOf(merged.totalTokens,
                                    requestUsage?.totalTokens ?: 0, chunk.usage.totalTokens))
                            }
                            try {
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages
                        break
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry && agentRun == null,
                        )
                    }
                }
            } else {
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry && agentRun == null,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                }
                requestUsage = result.usage
                messages = messages.handleTextGenerationResult(result = result, model = model)
                onUpdateMessages(messages)
            }
            responseCompleted = true
        } finally {
            if (agentRun != null && agentRun.snapshot.reservedTokens > 0) {
                agentRun.settleTokens(if (responseCompleted) requestUsage else null)
            }
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        if (!enabled || error !is IOException || retryCount >= MAX_PROVIDER_NETWORK_RETRIES) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        val retryDelay = INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            MAX_PROVIDER_NETWORK_RETRIES,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$MAX_PROVIDER_NETWORK_RETRIES)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: IOException): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val toolCallId = tool.toolCallId
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= MAX_TOOL_OUTPUT_CHARS || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
