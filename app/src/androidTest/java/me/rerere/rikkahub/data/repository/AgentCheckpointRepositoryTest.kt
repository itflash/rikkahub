package me.rerere.rikkahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.agent.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class AgentCheckpointRepositoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var scope: AppScope
    private lateinit var conversation: Conversation
    private lateinit var run: AgentRunTracker
    private var model = Model(modelId = "checkpoint-test", abilities = listOf(ModelAbility.TOOL))
    private val setting get() = ProviderSetting.OpenAI(models = listOf(model))
    private val assistant = Assistant(agentMode = AgentMode.EXECUTE, streamOutput = false)

    @Before fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
        scope = AppScope()
        repository = createRepository()
        conversation = Conversation(assistantId = assistant.id,
            config = ConversationConfig(agentMode = AgentMode.EXECUTE, chatModelId = model.id),
            messageNodes = listOf(UIMessage.user("Do the task").toMessageNode()))
        database.conversationDao().insert(repository.conversationToConversationEntity(conversation))
        run = AgentRunTracker(AgentMode.EXECUTE, 8)
        persist(AgentCheckpointPhase.READY)
    }

    private fun createRepository() = ConversationRepository(database.conversationDao(), database.messageNodeDao(),
        database.favoriteDao(), database, FilesManager(context, FilesRepository(database.managedFileDao()), scope),
        MessageFtsManager(database))

    @After fun tearDown() {
        database.close()
        scope.cancel()
    }

    private suspend fun persist(phase: AgentCheckpointPhase) {
        repository.saveAgentCheckpoint(conversation, AgentCheckpoint(run = run.snapshot, phase = phase,
            fingerprint = agentCheckpointFingerprint(conversation)))
    }

    @Test fun rollbackKeepsPreviousMessagesAndJournalTogether() = runBlocking {
        val original = repository.getAgentCheckpoint(conversation.id)
        val nodes = database.messageNodeDao().getNodesOfConversation(conversation.id.toString())
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_checkpoint BEFORE UPDATE ON agent_checkpoints
            BEGIN SELECT RAISE(ABORT, 'checkpoint failure'); END
        """.trimIndent())
        conversation = conversation.updateCurrentMessages(conversation.currentMessages + UIMessage.user("new message"))
        var failed = false
        try { persist(AgentCheckpointPhase.MODEL_REQUEST) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(original, repository.getAgentCheckpoint(conversation.id))
        assertEquals(nodes, database.messageNodeDao().getNodesOfConversation(conversation.id.toString()))
    }

    @Test fun ordinaryRowSaveDoesNotOverwriteJournalAndDeleteCascades() = runBlocking {
        val original = repository.getAgentCheckpoint(conversation.id)
        database.conversationDao().update(repository.conversationToConversationEntity(conversation.copy(title = "edited")))
        assertEquals(original, repository.getAgentCheckpoint(conversation.id))
        database.conversationDao().deleteById(conversation.id.toString())
        assertNull(repository.getAgentCheckpoint(conversation.id))
    }

    @Test fun staleRunCannotRollBackNewJournalOrMessages() = runBlocking {
        val old = run
        val next = AgentRunTracker(AgentMode.EXECUTE, 16)
        val checkpoint = AgentCheckpoint(run = next.snapshot, phase = AgentCheckpointPhase.READY,
            fingerprint = agentCheckpointFingerprint(conversation))
        repository.saveAgentCheckpoint(conversation, checkpoint, replaceRun = true)
        val expected = repository.getAgentCheckpoint(conversation.id)
        val originalNodes = database.messageNodeDao().getNodesOfConversation(conversation.id.toString())
        conversation = conversation.updateCurrentMessages(conversation.currentMessages + UIMessage.user("stale write"))
        run = old
        var rejected = false
        try { persist(AgentCheckpointPhase.FINISHED) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        assertEquals(expected, repository.getAgentCheckpoint(conversation.id))
        assertEquals(originalNodes, database.messageNodeDao().getNodesOfConversation(conversation.id.toString()))
    }

    @Test fun journalSurvivesSerializationAndRestoresBudget() = runBlocking {
        run.beginStep()
        run.beginStep()
        persist(AgentCheckpointPhase.MODEL_REQUEST)
        val loaded = repository.getConversationById(conversation.id)!!
        val recovered = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), loaded)!!
        assertEquals(AgentRunStatus.PAUSED, recovered.status)
        assertEquals(run.snapshot.id, recovered.id)
        assertEquals(2, recovered.steps)
        assertEquals(8, recovered.maxSteps)
    }

    @Test fun fileBackedCheckpointSurvivesClosingAndReopeningDatabase() = runBlocking {
        val name = "agent-reopen-${Uuid.random()}"
        database.close()
        try {
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
            repository = createRepository()
            database.conversationDao().insert(repository.conversationToConversationEntity(conversation))
            run.beginStep()
            persist(AgentCheckpointPhase.MODEL_REQUEST)
            val savedId = run.snapshot.id
            database.close()
            database = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .openHelperFactory(RequerySQLiteOpenHelperFactory()).build()
            repository = createRepository()
            val loaded = repository.getConversationById(conversation.id)!!
            val restored = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), loaded)!!
            assertEquals(savedId, restored.id)
            assertEquals(1, restored.steps)
            assertEquals(8, restored.maxSteps)
            assertEquals(AgentRunStatus.PAUSED, restored.status)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private class FakeProvider(private val replies: MutableList<UIMessage>) : Provider<ProviderSetting.OpenAI> {
        var requests = 0
        val requestMessages = mutableListOf<List<UIMessage>>()
        val requestParams = mutableListOf<TextGenerationParams>()
        var requestDelayMillis = 0L
        var usageByRequest: List<me.rerere.ai.core.TokenUsage?> = emptyList()
        override suspend fun listModels(providerSetting: ProviderSetting.OpenAI) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
            requests++
            requestMessages += messages
            requestParams += params
            if (requestDelayMillis > 0) delay(requestDelayMillis)
            return TextGenerationResult("result-$requests", params.model.modelId, replies.removeAt(0), usage = usageByRequest.getOrNull(requests - 1))
        }
        override suspend fun streamText(providerSetting: ProviderSetting.OpenAI, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = error("not used")
    }

    private fun reply(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun toolCall(id: String) = UIMessagePart.Tool(id, id, "{}")
    private fun loop(provider: FakeProvider): GenerationLoop {
        val manager = ProviderManager(OkHttpClient(), context)
        manager.registerProvider("openai", provider)
        return GenerationLoop(context, manager, JsonInstant)
    }

    private suspend fun generate(
        loop: GenerationLoop,
        tools: List<Tool>,
        onCheckpoint: suspend (AgentCheckpointPhase) -> Unit = { persist(it) },
    ) {
        loop.generateText(settings = Settings(providers = listOf(setting)), model = model,
            assistant = assistant, messages = conversation.currentMessages.take(run.snapshot.contextMessageCount ?: conversation.currentMessages.size), tools = tools, agentRun = run,
            onAgentCheckpoint = { messages, phase ->
                conversation = conversation.updateCurrentMessages(messages)
                onCheckpoint(phase)
            },
        ).collect { chunk ->
            if (chunk is GenerationChunk.Messages) conversation = conversation.updateCurrentMessages(chunk.messages)
        }
    }

    private fun approveAll() {
        conversation = conversation.updateCurrentMessages(conversation.currentMessages.map { message ->
            message.copy(parts = message.parts.map { part ->
                if (part is UIMessagePart.Tool && !part.isExecuted) part.copy(approvalState = ToolApprovalState.Approved) else part
            })
        })
        run.resume(AgentMode.EXECUTE)
    }

    @Test fun failedWriteAheadCommitPreventsToolExecution() = runBlocking {
        var effects = 0
        val tool = Tool("write", "write", execute = { effects++; listOf(UIMessagePart.Text("done")) })
        val provider = FakeProvider(mutableListOf(reply(toolCall("write"))))
        val loop = loop(provider)
        generate(loop, listOf(tool))
        assertEquals(AgentRunStatus.WAITING_APPROVAL, run.snapshot.status)
        approveAll()
        var failed = false
        try {
            generate(loop, listOf(tool)) { phase ->
                if (phase == AgentCheckpointPhase.TOOL_IN_FLIGHT) throw IOException("disk full")
                persist(phase)
            }
        } catch (error: Exception) {
            failed = error is IOException || error.cause is IOException
        }
        assertTrue(failed)
        assertEquals(0, effects)
        assertEquals(1, provider.requests)
    }

    @Test fun crashDuringInvocationNeverProducesResumableCheckpoint() = runBlocking {
        val tool = Tool("write", "write", execute = { throw CancellationException("simulated process death") })
        val provider = FakeProvider(mutableListOf(reply(toolCall("write"))))
        val loop = loop(provider)
        generate(loop, listOf(tool))
        approveAll()
        try { generate(loop, listOf(tool)); fail("Expected interruption") } catch (_: CancellationException) { }
        val loaded = repository.getConversationById(conversation.id)!!
        val recovered = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), loaded)!!
        assertEquals(AgentRunStatus.INTERRUPTED, recovered.status)
        assertEquals("write", recovered.currentToolCallId)
    }

    @Test fun historicalPrefixRecoveryDoesNotSendLaterMessagesToTheModel() = runBlocking {
        conversation = conversation.copy(messageNodes = conversation.messageNodes +
            listOf(reply(UIMessagePart.Text("old answer")).toMessageNode(), UIMessage.user("later context").toMessageNode()))
        run.recordContextSize(1)
        var effects = 0
        val tool = Tool("write", "write", execute = { effects++; listOf(UIMessagePart.Text("saved")) })
        val provider = FakeProvider(mutableListOf(reply(toolCall("write")), reply(UIMessagePart.Text("finished"))))
        generate(loop(provider), listOf(tool))
        assertEquals(2, run.snapshot.contextMessageCount)
        conversation = repository.getConversationById(conversation.id)!!
        val restored = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), conversation)!!
        assertEquals(AgentRunStatus.WAITING_APPROVAL, restored.status)
        assertEquals("later context", conversation.currentMessages.last().toText())
        run = AgentRunTracker.restore(restored) {}
        approveAll()
        generate(loop(provider), listOf(tool))
        assertEquals(1, effects)
        assertTrue(provider.requestMessages.flatten().none { it.toText().contains("later context") })
    }

    @Test fun ambiguousWriteFailureDoesNotReturnToTheModelForAutomaticRetry() = runBlocking {
        var attempts = 0
        val tool = Tool("write", "write", execute = {
            attempts++
            throw IOException("remote acknowledgement lost")
        })
        val provider = FakeProvider(mutableListOf(reply(toolCall("write")), reply(toolCall("write"))))
        val loop = loop(provider)
        generate(loop, listOf(tool))
        approveAll()
        try { generate(loop, listOf(tool)); fail("Expected ambiguous outcome") } catch (_: IOException) { }
        assertEquals(1, attempts)
        assertEquals(1, provider.requests)
        assertEquals(AgentRunStatus.INTERRUPTED, run.snapshot.status)
        val loaded = repository.getConversationById(conversation.id)!!
        assertEquals(AgentRunStatus.INTERRUPTED,
            restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), loaded)?.status)
    }

    @Test fun tokenPreflightRefusesRequestBeforeProviderOrToolWork() = runBlocking {
        run = AgentRunTracker(AgentMode.EXECUTE, 8, maxTokens = 1024)
        val provider = FakeProvider(mutableListOf(reply(UIMessagePart.Text("should not happen"))))
        try { generate(loop(provider), emptyList()); fail("Expected insufficient token budget") }
        catch (_: AgentResourceBudgetExceeded) { }
        assertEquals(0, provider.requests)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
    }

    @Test fun timeBudgetCancelsSlowRequestAndRetainsSpentReservation() = runBlocking {
        run = AgentRunTracker(AgentMode.EXECUTE, 8, maxActiveMillis = 1000)
        repository.clearAgentCheckpoint(conversation.id)
        val provider = FakeProvider(mutableListOf(reply(UIMessagePart.Text("too late")))).apply { requestDelayMillis = 5000 }
        try { generate(loop(provider), emptyList()); fail("Expected timeout") }
        catch (_: AgentResourceBudgetExceeded) { }
        assertEquals(1, provider.requests)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
        assertTrue(run.snapshot.activeMillis >= 900)
        assertTrue(run.snapshot.usedTokens > 0)
    }

    @Test fun autoCompactionKeepsHistoryAndPersistsSummaryWithSharedCosts() = runBlocking {
        model = model.copy(contextLength = 12_000)
        conversation = conversation.copy(messageNodes = (0..9).map {
            UIMessage.user("history $it " + "x".repeat(1000)).toMessageNode()
        })
        val originalIds = conversation.currentMessages.map { it.id }
        val provider = FakeProvider(mutableListOf(reply(UIMessagePart.Text("Keep goal and completed file changes")),
            reply(UIMessagePart.Text("Final response"))))
        generate(loop(provider), emptyList())
        assertEquals(2, provider.requests)
        assertTrue(provider.requestParams.first().tools.isEmpty())
        assertEquals(1, run.snapshot.compactions)
        assertEquals(6, run.snapshot.summarizedMessageCount)
        assertEquals(2, run.snapshot.steps)
        assertEquals(originalIds, conversation.currentMessages.take(10).map { it.id })
        assertTrue(provider.requestMessages.last().any { it.toText().contains("Keep goal") })
        assertFalse(provider.requestMessages.last().any { it.toText().contains("history 0 ") })
        val loaded = repository.getConversationById(conversation.id)!!
        val restored = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), loaded)!!
        assertEquals(run.snapshot.contextSummary, restored.contextSummary)
        assertEquals(run.snapshot.usedTokens, restored.usedTokens)
    }

    @Test fun singleAssistantMessageWithManyToolResultsCanBeAutoCompacted() = runBlocking {
        model = model.copy(contextLength = 12_000)
        val calls = (0..2).map { UIMessagePart.Tool("read-$it", "read", "{}",
            output = listOf(UIMessagePart.Text("evidence-$it " + "x".repeat(3000)))) }
        conversation = conversation.copy(messageNodes = conversation.messageNodes + reply(*calls.toTypedArray()).toMessageNode())
        val provider = FakeProvider(mutableListOf(reply(UIMessagePart.Text("read-0 and read-1 found earlier evidence")),
            reply(UIMessagePart.Text("Final findings"))))
        generate(loop(provider), emptyList())
        assertEquals(2, provider.requests)
        assertEquals(1, run.snapshot.compactions)
        assertEquals(setOf("read-0", "read-1"), run.snapshot.summarizedToolCallIds)
        assertEquals(calls, conversation.currentMessages.last().getTools())
        assertTrue(provider.requestMessages.last().flatMap { it.getTools() }.all { it.isExecuted })
    }

    @Test fun missingUsageOnLaterRequestDoesNotReuseEarlierMessageUsage() = runBlocking {
        val read = Tool("read", "read", readOnly = true, execute = { listOf(UIMessagePart.Text("evidence")) })
        val provider = FakeProvider(mutableListOf(reply(toolCall("read")), reply(UIMessagePart.Text("final")))).apply {
            usageByRequest = listOf(me.rerere.ai.core.TokenUsage(promptTokens = 80, completionTokens = 20), null)
        }
        generate(loop(provider), listOf(read))
        assertEquals(2, provider.requests)
        assertTrue(run.snapshot.usageEstimated)
        assertTrue(run.snapshot.usedTokens > 200)
    }

    @Test fun childInvestigationRunsInsideParentBudgetsWithoutLeakingItsTranscript() = runBlocking {
        var reads = 0
        val read = Tool("read", "read", readOnly = true, execute = { reads++; listOf(UIMessagePart.Text("file evidence")) })
        val delegate = UIMessagePart.Tool("child", "delegate_research", """{"task":"Read the file and review it"}""")
        val provider = FakeProvider(mutableListOf(reply(delegate), reply(toolCall("read")),
            reply(UIMessagePart.Text("child-only finding")), reply(UIMessagePart.Text("Parent conclusion"))))
        generate(loop(provider), listOf(read))
        assertEquals(4, provider.requests)
        assertEquals(4, run.snapshot.steps)
        assertEquals(1, reads)
        assertEquals("completed", run.snapshot.subtasks.single().status)
        assertTrue(provider.requestParams[1].tools.none { it.name == "delegate_research" })
        assertTrue(provider.requestParams[1].model.tools.isEmpty())
        assertFalse(conversation.currentMessages.any { it.toText() == "child-only finding" })
        assertTrue(conversation.currentMessages.flatMap { it.getTools() }.any {
            it.toolName == "delegate_research" && it.isExecuted
        })
    }

    @Test fun committedFirstResultIsNotExecutedAgainAfterRestartMidBatch() = runBlocking {
        var first = 0
        var second = 0
        val tools = listOf(
            Tool("first", "first", execute = { first++; listOf(UIMessagePart.Text("done first")) }),
            Tool("second", "second", execute = { second++; listOf(UIMessagePart.Text("done second")) }),
        )
        val provider = FakeProvider(mutableListOf(reply(toolCall("first"), toolCall("second")), reply(UIMessagePart.Text("finished"))))
        val loop = loop(provider)
        generate(loop, tools)
        approveAll()
        try {
            generate(loop, tools) { phase ->
                persist(phase)
                if (phase == AgentCheckpointPhase.READY && first == 1) throw CancellationException("simulated restart")
            }
        } catch (_: CancellationException) { }
        assertEquals(1, first)
        assertEquals(0, second)
        conversation = resetRecoveredApprovals(repository.getConversationById(conversation.id)!!)
        val restored = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), conversation)!!
        assertEquals(AgentRunStatus.WAITING_APPROVAL, restored.status)
        assertEquals(1, restored.steps)
        run = AgentRunTracker.restore(restored) {}
        // Merely restoring must not call a provider or tool.
        assertEquals(1, provider.requests)
        approveAll()
        generate(loop(provider), tools)
        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(2, provider.requests)
        assertEquals(2, run.snapshot.steps)
        val completed = restoreAgentCheckpoint(repository.getAgentCheckpoint(conversation.id), conversation)!!
        assertEquals(AgentRunStatus.COMPLETED, completed.status)
    }
}
