package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationConfigTest {
    private val modelA = Model(modelId = "model-a", displayName = "Model A", tools = setOf(BuiltInTools.Search))
    private val modelB = Model(modelId = "model-b", displayName = "Model B")
    private val mcpServer = Uuid.random()
    private val workspace = Uuid.random()
    private val injection = Uuid.random()
    private val lorebook = Uuid.random()

    private val assistant = Assistant(
        chatModelId = modelA.id,
        reasoningLevel = ReasoningLevel.HIGH,
        enableWebSearch = true,
        mcpServers = setOf(mcpServer),
        workspaceId = workspace,
        enabledSkills = setOf("skill"),
        modeInjectionIds = setOf(injection),
        lorebookIds = setOf(lorebook),
    )

    private fun settings(assistant: Assistant = this.assistant) = Settings(
        assistantId = assistant.id,
        assistants = listOf(assistant),
        chatModelId = modelB.id,
        providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))),
        mcpServers = listOf(McpServerConfig.StreamableHTTPServer(id = mcpServer)),
        modeInjections = listOf(PromptInjection.ModeInjection(id = injection)),
        lorebooks = listOf(Lorebook(id = lorebook)),
    )

    private fun conversation(assistant: Assistant = this.assistant) = Conversation(
        assistantId = assistant.id,
        messageNodes = emptyList(),
    )

    @Test
    fun `resource budget and agent capabilities are isolated per conversation`() {
        val configured = assistant.copy(agentMaxTokens = 50_000, agentMaxMinutes = 5,
            agentAutoCompact = false, agentSubtasks = false)
        val bound = conversation(configured).bindConfig(settings(configured))
        val changed = settings(configured.copy(agentMaxTokens = 1_000_000, agentMaxMinutes = 60,
            agentAutoCompact = true, agentSubtasks = true))
        assertEquals(50_000, changed.getAssistantOf(bound).agentMaxTokens)
        assertEquals(5, changed.getAssistantOf(bound).agentMaxMinutes)
        assertFalse(changed.getAssistantOf(bound).agentAutoCompact)
        assertFalse(changed.getAssistantOf(bound).agentSubtasks)
        val edited = changed.getAssistantOf(bound).copy(agentMaxTokens = 200_000, agentMaxMinutes = 15,
            agentAutoCompact = true, agentSubtasks = true)
        assertEquals(edited.agentMaxTokens, bound.withAssistantUpdate(edited, changed).config?.agentMaxTokens)
        assertEquals(configured.agentMaxTokens, edited.withoutConversationFields(bound, configured).agentMaxTokens)
        assertEquals(200_000, Json.decodeFromString<ConversationConfig>("{}").agentMaxTokens)
    }

    @Test
    fun `agent budget is snapshotted and edited only on the conversation`() {
        val configured = assistant.copy(agentMaxSteps = 8)
        val bound = conversation(configured).bindConfig(settings(configured))
        val changed = settings(configured.copy(agentMaxSteps = 128))
        assertEquals(8, changed.getAssistantOf(bound).agentMaxSteps)
        val edited = changed.getAssistantOf(bound).copy(agentMaxSteps = 64)
        assertEquals(64, bound.withAssistantUpdate(edited, changed).config?.agentMaxSteps)
        assertEquals(8, edited.withoutConversationFields(bound, configured).agentMaxSteps)
        assertEquals(32, Json.decodeFromString<ConversationConfig>("{}").agentMaxSteps)
        val config = ConversationConfig(agentMode = AgentMode.PLAN, agentMaxSteps = 64)
        assertEquals(config, Json.decodeFromString<ConversationConfig>(Json.encodeToString(config)))
    }

    @Test
    fun `unstarted conversation follows the assistant`() {
        val conversation = conversation()

        assertSame(assistant, settings().getAssistantOf(conversation))
        assertEquals(modelA, settings().getChatModelOf(conversation))
    }

    @Test
    fun `binding snapshots the assistant configuration`() {
        val bound = conversation().bindConfig(settings())

        assertEquals(
            ConversationConfig(
                chatModelId = modelA.id,
                reasoningLevel = ReasoningLevel.HIGH,
                enableWebSearch = true,
                builtInSearch = true,
                mcpServers = setOf(mcpServer),
                workspaceId = workspace,
                enabledSkills = setOf("skill"),
            ),
            bound.config,
        )
        assertEquals(setOf(injection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
        assertSame(bound, bound.bindConfig(settings(assistant.copy(reasoningLevel = ReasoningLevel.OFF))))
    }

    @Test
    fun `binding resolves the global default model`() {
        val assistant = assistant.copy(chatModelId = null)

        val bound = conversation(assistant).bindConfig(settings(assistant))

        assertEquals(modelB.id, bound.config?.chatModelId)
        assertEquals(false, bound.config?.builtInSearch)
    }

    @Test
    fun `bound conversation ignores later assistant changes`() {
        val bound = conversation().bindConfig(settings())
        val changed = assistant.copy(
            chatModelId = modelB.id,
            reasoningLevel = ReasoningLevel.OFF,
            enableWebSearch = false,
            mcpServers = emptySet(),
            workspaceId = null,
            enabledSkills = emptySet(),
            modeInjectionIds = emptySet(),
            lorebookIds = emptySet(),
            systemPrompt = "changed",
        )

        val resolved = settings(changed).getAssistantOf(bound)

        assertEquals(assistant.copy(systemPrompt = "changed"), resolved)
        assertEquals(modelA, settings(changed).getChatModelOf(bound))
    }

    @Test
    fun `bound built-in search overrides the model switch`() {
        val bound = conversation().bindConfig(settings())
        val disabled = bound.copy(config = bound.config?.copy(builtInSearch = false))
        val withoutModelSearch = settings().copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA.copy(tools = emptySet()), modelB))),
        )

        assertFalse(BuiltInTools.Search in settings().getChatModelOf(disabled)!!.tools)
        assertTrue(BuiltInTools.Search in withoutModelSearch.getChatModelOf(bound)!!.tools)
    }

    @Test
    fun `bound conversation falls back to the assistant model when its model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        assertEquals(modelB.id, withoutModelA.getChatModelOf(bound)?.id)
        // 会话上固定的内置搜索开关属于被删掉的模型，不能套到退回的模型上
        assertEquals(modelB, withoutModelA.getChatModelOf(bound))
    }

    @Test
    fun `binding again pins the assistant model once the pinned model is removed`() {
        val bound = conversation().bindConfig(settings())
        val withoutModelA = settings(assistant.copy(chatModelId = null)).copy(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(modelB))),
        )

        val rebound = bound.bindConfig(withoutModelA)

        assertEquals(bound.config?.copy(chatModelId = modelB.id, builtInSearch = false), rebound.config)
        assertEquals(bound.modeInjectionIds, rebound.modeInjectionIds)
        assertSame(rebound, rebound.bindConfig(withoutModelA))
    }

    @Test
    fun `bound conversation drops references to removed items`() {
        val bound = conversation().bindConfig(settings())
        val emptied = settings().copy(mcpServers = emptyList(), modeInjections = emptyList(), lorebooks = emptyList())

        val resolved = emptied.getAssistantOf(bound)

        assertEquals(emptySet<Uuid>(), resolved.mcpServers)
        assertEquals(emptySet<Uuid>(), resolved.modeInjectionIds)
        assertEquals(emptySet<Uuid>(), resolved.lorebookIds)
    }

    @Test
    fun `chat page update goes to the assistant before the conversation starts`() {
        val conversation = conversation()
        val updated = assistant.copy(reasoningLevel = ReasoningLevel.LOW)

        assertSame(conversation, conversation.withAssistantUpdate(updated, settings()))
        assertSame(updated, updated.withoutConversationFields(conversation, assistant))
    }

    @Test
    fun `chat page update stays on the started conversation`() {
        val bound = conversation().bindConfig(settings())
        val newInjection = Uuid.random()
        val updated = settings().getAssistantOf(bound).copy(
            reasoningLevel = ReasoningLevel.LOW,
            modeInjectionIds = setOf(newInjection),
            quickMessageIds = setOf(Uuid.random()),
        )

        val conversation = bound.withAssistantUpdate(updated, settings())
        val stored = updated.withoutConversationFields(bound, assistant)

        assertEquals(ReasoningLevel.LOW, conversation.config?.reasoningLevel)
        assertEquals(setOf(newInjection), conversation.modeInjectionIds)
        // 不随会话固定的字段仍然写回助手
        assertEquals(assistant.copy(quickMessageIds = updated.quickMessageIds), stored)
    }

    @Test
    fun `switching model follows the new model's built-in search`() {
        val bound = conversation().bindConfig(settings())
        val updated = settings().getAssistantOf(bound).copy(chatModelId = modelB.id)

        val conversation = bound.withAssistantUpdate(updated, settings())

        assertEquals(modelB.id, conversation.config?.chatModelId)
        assertEquals(false, conversation.config?.builtInSearch)
    }

    @Test
    fun `switching workspace resets the working directory`() {
        val bound = conversation().bindConfig(settings()).copy(workspaceCwd = "/workspace/project")
        val assistantView = settings().getAssistantOf(bound)

        val sameWorkspace = bound.withAssistantUpdate(assistantView.copy(reasoningLevel = ReasoningLevel.LOW), settings())
        val otherWorkspace = bound.withAssistantUpdate(assistantView.copy(workspaceId = Uuid.random()), settings())

        assertEquals("/workspace/project", sameWorkspace.workspaceCwd)
        assertNull(otherWorkspace.workspaceCwd)
    }

    @Test
    fun `conversation bound before per-conversation config keeps its own injections`() {
        val conversationInjection = Uuid.random()
        val conversation = conversation().copy(modeInjectionIds = setOf(conversationInjection))

        val bound = conversation.bindConfig(settings())

        // 会话自己绑定过的保留，没绑定过的继承助手
        assertEquals(setOf(conversationInjection), bound.modeInjectionIds)
        assertEquals(setOf(lorebook), bound.lorebookIds)
    }

    @Test
    fun `legacy configuration defaults to chat mode`() {
        assertEquals(AgentMode.CHAT, Json.decodeFromString<ConversationConfig>("{}").agentMode)
        assertEquals(AgentMode.CHAT, Json.decodeFromString<Assistant>("{}").agentMode)
    }

    @Test
    fun `binding snapshots agent mode independently of subsequent assistant changes`() {
        val planner = assistant.copy(agentMode = AgentMode.PLAN)
        val bound = conversation(planner).bindConfig(settings(planner))
        val changedSettings = settings(planner.copy(agentMode = AgentMode.EXECUTE))

        assertEquals(AgentMode.PLAN, bound.config?.agentMode)
        assertEquals(AgentMode.PLAN, changedSettings.getAssistantOf(bound).agentMode)
    }

    @Test
    fun `chat page agent mode changes remain on the conversation`() {
        val bound = conversation().bindConfig(settings())
        val edited = settings().getAssistantOf(bound).copy(agentMode = AgentMode.EXECUTE)
        val updated = bound.withAssistantUpdate(edited, settings())

        assertEquals(AgentMode.EXECUTE, settings().getAssistantOf(updated).agentMode)
        assertEquals(AgentMode.CHAT, edited.withoutConversationFields(bound, assistant).agentMode)
    }

    @Test
    fun `unbound agent mode updates become assistant defaults`() {
        val unbound = conversation()
        val edited = assistant.copy(agentMode = AgentMode.PLAN)
        assertEquals(AgentMode.PLAN, edited.withoutConversationFields(unbound, assistant).agentMode)
    }

    @Test
    fun `agent mode survives configuration serialization`() {
        AgentMode.entries.forEach { mode ->
            val config = ConversationConfig(agentMode = mode)
            assertEquals(config, Json.decodeFromString<ConversationConfig>(Json.encodeToString(config)))
        }
    }
}
