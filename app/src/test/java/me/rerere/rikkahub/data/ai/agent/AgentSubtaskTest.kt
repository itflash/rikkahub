package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentMode
import org.junit.Assert.*
import org.junit.Test

class AgentSubtaskTest {
    private val args = buildJsonObject { put("task", "Inspect /workspace and report evidence") }
    private fun result(vararg parts: UIMessagePart) = TextGenerationResult("r", "model",
        UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()), usage = TokenUsage(totalTokens = 100))
    private fun call(name: String) = UIMessagePart.Tool(name, name, "{}")
    private fun tool(name: String, readOnly: Boolean, approval: Boolean = false, action: () -> Unit = {}) = Tool(
        name, name, readOnly = readOnly, needsApproval = { approval }, execute = {
            action(); listOf(UIMessagePart.Text("evidence"))
        })

    @Test fun `child tools cannot expand permissions or recursively delegate`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.EXECUTE, 8)
        var reads = 0
        var forbidden = 0
        var requests = 0
        val definitions = listOf(tool("read", true) { reads++ }, tool("write", false) { forbidden++ },
            tool("mcp", false) { forbidden++ }, tool("private_read", true, true) { forbidden++ },
            tool("delegate_research", true) { forbidden++ }, tool("ask_user", true) { forbidden++ })
        val delegate = createAgentSubtaskTool(parent, definitions, beforeWork = {}, request = { messages, tools, _ ->
            assertEquals(setOf("read", "private_read"), tools.map { it.name }.toSet())
            requests++
            if (requests == 1) result(call("read"), call("write"), call("private_read"), call("delegate_research"))
            else {
                val outputs = messages.last().getTools()
                assertEquals(4, outputs.size)
                assertTrue(outputs.all { it.isExecuted })
                result(UIMessagePart.Text("Checked evidence; cannot modify files"))
            }
        })
        assertTrue(delegate.readOnly)
        delegate.execute(args)
        assertEquals(1, reads)
        assertEquals(0, forbidden)
        assertEquals(2, parent.snapshot.steps)
        assertEquals(200L, parent.snapshot.usedTokens)
        assertEquals("completed", parent.snapshot.subtasks.single().status)
    }

    @Test fun `child budget exhaustion stops parent instead of silently resetting allowance`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.PLAN, 1)
        var requests = 0
        val delegate = createAgentSubtaskTool(parent, emptyList(), beforeWork = {}, request = { _, _, _ ->
            requests++; result(call("unknown"))
        })
        try { delegate.execute(args); fail("Expected budget exhaustion") }
        catch (_: AgentStepBudgetExceeded) { }
        assertEquals(1, requests)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, parent.snapshot.status)
        assertEquals("failed", parent.snapshot.subtasks.single().status)
    }

    @Test fun `child is bounded even with a larger parent budget`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.PLAN, 32)
        var requests = 0
        val delegate = createAgentSubtaskTool(parent, emptyList(), beforeWork = {}, request = { _, _, _ ->
            requests++; result(call("unknown"))
        })
        delegate.execute(args)
        assertEquals(8, requests)
        assertEquals("limit_reached", parent.snapshot.subtasks.single().status)
        assertEquals(8, parent.snapshot.steps)
    }

    @Test fun `cancellation propagates and does not mark child completed`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.PLAN, 8)
        val delegate = createAgentSubtaskTool(parent, emptyList(), beforeWork = {}, request = { _, _, _ ->
            throw CancellationException("user stop")
        })
        try { delegate.execute(args); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals("cancelled", parent.snapshot.subtasks.single().status)
        assertTrue(parent.snapshot.usageEstimated)
        assertTrue(parent.snapshot.usedTokens > 0)
    }

    @Test fun `subtask receives isolated explicit context and does not modify parent summary`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.PLAN, 8)
        parent.saveSummary("private parent context", 3)
        val delegate = createAgentSubtaskTool(parent, emptyList(), beforeWork = {}, request = { messages, _, _ ->
            assertEquals(2, messages.size)
            assertFalse(messages.any { it.toText().contains("private parent context") })
            assertEquals("Inspect /workspace and report evidence", messages.last().toText())
            result(UIMessagePart.Text("Findings"))
        })
        delegate.execute(args)
        assertEquals("private parent context", parent.snapshot.contextSummary)
        assertEquals(3, parent.snapshot.summarizedMessageCount)
    }

    @Test fun `empty task is rejected before any model request`() = runBlocking {
        val parent = AgentRunTracker(AgentMode.PLAN, 8)
        var requests = 0
        val delegate = createAgentSubtaskTool(parent, emptyList(), beforeWork = {}, request = { _, _, _ ->
            requests++; result(UIMessagePart.Text("oops"))
        })
        try { delegate.execute(buildJsonObject { put("task", "") }); fail("Expected validation") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, requests)
    }
}
