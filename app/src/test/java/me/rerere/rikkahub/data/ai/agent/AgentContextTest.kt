package me.rerere.rikkahub.data.ai.agent

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentMode
import org.junit.Assert.*
import org.junit.Test

class AgentContextTest {
    private val run = AgentRun(mode = AgentMode.EXECUTE, maxSteps = 32)
    private fun history() = (0..9).map { UIMessage.user("message $it " + "x".repeat(1000)) }

    @Test fun `tool dense single message is compacted without splitting calls and results`() {
        val calls = (0..2).map { UIMessagePart.Tool("call-$it", "read", "{}",
            output = listOf(UIMessagePart.Text("result-$it " + "x".repeat(3000)))) }
        val messages = listOf(UIMessage.user("goal"), UIMessage(role = MessageRole.ASSISTANT, parts = calls))
        assertNull(agentCompactionCut(messages, run, 12_000, emptyList()))
        val ids = agentToolCompactionIds(messages, run, 12_000, emptyList())
        assertEquals(setOf("call-0", "call-1"), ids)
        val compacted = agentContextMessages(messages, run.copy(toolResultSummary = "Earlier evidence", summarizedToolCallIds = ids))
        val remaining = compacted.last().getTools()
        assertEquals(calls.map { it.toolCallId }, remaining.map { it.toolCallId })
        assertTrue(remaining.all { it.isExecuted })
        assertEquals(calls.last(), remaining.last())
        assertTrue((remaining.first().output.first() as UIMessagePart.Text).text.length < 300)
        assertEquals(3009, (messages.last().getTools().first().output.first() as UIMessagePart.Text).text.length)
    }

    @Test fun `compaction retains recent messages and leaves original history unchanged`() {
        val messages = history()
        assertEquals(6, agentCompactionCut(messages, run, 10_000, emptyList()))
        val compacted = agentContextMessages(messages, run.copy(contextSummary = "summary", summarizedMessageCount = 6))
        assertEquals(5, compacted.size)
        assertEquals(messages.takeLast(4), compacted.drop(1))
        assertEquals(10, messages.size)
        assertTrue(compacted.first().isContextCheckpoint)
    }

    @Test fun `unfinished old tools cannot be summarized away`() {
        val pending = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool("a", "write", "{}")))
        assertNull(agentCompactionCut(listOf(pending) + history(), run, 10_000, emptyList()))
    }

    @Test fun `summary contains tool inputs results denials and paths`() {
        val tool = UIMessagePart.Tool("a", "write", """{"path":"/workspace/a.txt"}""",
            output = listOf(UIMessagePart.Text("Permission denied")), approvalState = ToolApprovalState.Denied("do not write"))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val prompt = agentCompactionPrompt(listOf(message) + history(), run, 2)
        assertTrue(prompt.contains("/workspace/a.txt"))
        assertTrue(prompt.contains("Permission denied"))
        assertTrue(prompt.contains("do not write"))
    }

    @Test fun `small or recent-only contexts are not repeatedly compressed`() {
        assertNull(agentCompactionCut(history(), run, 1_000_000, emptyList()))
        assertNull(agentCompactionCut(history(), run.copy(contextSummary = "summary", summarizedMessageCount = 6), 1024, emptyList()))
    }

    @Test fun `previous summary is carried forward and counts remain absolute`() {
        val previous = run.copy(contextSummary = "earlier important goal", summarizedMessageCount = 2)
        val prompt = agentCompactionPrompt(history(), previous, 6)
        assertTrue(prompt.contains("earlier important goal"))
        assertFalse(prompt.contains("message 0 "))
        assertTrue(prompt.contains("message 5 "))
    }
}
