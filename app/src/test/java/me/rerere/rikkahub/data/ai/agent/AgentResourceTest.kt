package me.rerere.rikkahub.data.ai.agent

import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentResourceTest {
    @Test fun `restored task keeps capability snapshot even if assistant settings change`() {
        val saved = AgentRun(mode = AgentMode.EXECUTE, maxSteps = 8, autoCompact = false, subtasksEnabled = false)
        val restored = AgentRunTracker.restore(saved) {}
        assertFalse(restored.snapshot.autoCompact)
        assertFalse(restored.snapshot.subtasksEnabled)
    }

    @Test fun `recovery does not offer resume when token or time budget is already exhausted`() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("task").toMessageNode()))
        val tokenSpent = AgentRun(mode = AgentMode.PLAN, maxSteps = 8, maxTokens = 1024, usedTokens = 1024)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED,
            AgentCheckpoint(run = tokenSpent, phase = AgentCheckpointPhase.READY, fingerprint = agentCheckpointFingerprint(conversation)).recover(conversation).status)
        val timeSpent = AgentRun(mode = AgentMode.PLAN, maxSteps = 8, maxActiveMillis = 1000, activeMillis = 1000)
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED,
            AgentCheckpoint(run = timeSpent, phase = AgentCheckpointPhase.READY, fingerprint = agentCheckpointFingerprint(conversation)).recover(conversation).status)
    }

    @Test fun `reserve caps output and actual usage replaces the reservation`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8, maxTokens = 2048)
        assertEquals(1048, run.reserveTokens(1000, 4096))
        assertEquals(2048, run.snapshot.reservedTokens)
        run.settleTokens(TokenUsage(promptTokens = 500, completionTokens = 100, totalTokens = 600))
        assertEquals(600L, run.snapshot.usedTokens)
        assertEquals(0, run.snapshot.reservedTokens)
        assertFalse(run.snapshot.usageEstimated)
    }

    @Test fun `unknown usage charges entire reservation and prevents overspending next request`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8, maxTokens = 2048)
        run.reserveTokens(1000, 4096)
        run.settleTokens(null)
        assertEquals(2048L, run.snapshot.usedTokens)
        assertTrue(run.snapshot.usageEstimated)
        assertThrows(AgentResourceBudgetExceeded::class.java) { run.beginStep() }
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
    }

    @Test fun `time counts active work but not approval waiting`() {
        var now = 1000L
        val run = AgentRunTracker(AgentMode.EXECUTE, 8, maxActiveMillis = 5000, nowMillis = { now })
        run.startClock()
        now += 1000
        run.waitForUser(false)
        now += 60_000
        assertEquals(4000L, run.remainingMillis())
        run.resume(AgentMode.EXECUTE)
        run.startClock()
        now += 4000
        assertThrows(AgentResourceBudgetExceeded::class.java) { run.checkResourceBudget() }
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
    }

    @Test fun `unsettled crash reservation and active clock are recovered conservatively`() {
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(UIMessage.user("task").toMessageNode()))
        val run = AgentRun(mode = AgentMode.PLAN, maxSteps = 8, usedTokens = 300, reservedTokens = 1000,
            activeMillis = 100, activeStartedAtMillis = System.currentTimeMillis() - 3000,
            contextSummary = "goal", summarizedMessageCount = 1)
        val recovered = AgentCheckpoint(run = run, phase = AgentCheckpointPhase.MODEL_REQUEST,
            fingerprint = agentCheckpointFingerprint(conversation)).recover(conversation)
        assertEquals(1300L, recovered.usedTokens)
        assertEquals(0, recovered.reservedTokens)
        assertTrue(recovered.usageEstimated)
        assertTrue(recovered.activeMillis >= 3100)
        assertNull(recovered.activeStartedAtMillis)
        assertEquals("goal", recovered.contextSummary)
    }

    @Test fun `no silent nested token reservation`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8)
        run.reserveTokens(1000, 1000)
        assertThrows(IllegalStateException::class.java) { run.reserveTokens(1000, 1000) }
    }

    @Test fun `token limits cannot be lowered by negative usage counters`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8)
        run.reserveTokens(1000, 1000)
        run.settleTokens(TokenUsage(promptTokens = -10, completionTokens = -10))
        assertEquals(2000L, run.snapshot.usedTokens)
    }
}
