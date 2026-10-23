package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.completedToolOutput
import me.rerere.rikkahub.data.model.AgentMode
import org.junit.Assert.*
import org.junit.Test

class AgentRunTest {
    @Test
    fun `empty successful tool result is executed rather than eligible for repeated execution`() {
        val tool = UIMessagePart.Tool("id", "write", "{}", output = completedToolOutput(emptyList()))
        assertTrue(tool.isExecuted)
        val output = listOf(UIMessagePart.Text("result"))
        assertSame(output, completedToolOutput(output))
    }

    @Test
    fun `steps accumulate over approval rounds without resetting the budget`() {
        val run = AgentRunTracker(AgentMode.EXECUTE, 2)
        val id = run.snapshot.id
        run.beginStep()
        run.waitForUser(false)
        run.complete()
        assertEquals(AgentRunStatus.WAITING_APPROVAL, run.snapshot.status)
        run.resume(AgentMode.EXECUTE)
        run.beginTool("write")
        run.beginStep()
        run.waitForUser(true)
        run.resume(AgentMode.PLAN)
        assertEquals(id, run.snapshot.id)
        assertEquals(2, run.snapshot.steps)
        assertEquals(1, run.snapshot.toolCalls)
        assertEquals(AgentMode.PLAN, run.snapshot.mode)
        assertThrows(AgentStepBudgetExceeded::class.java) { run.beginStep() }
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
        run.fail(IllegalStateException("later error"))
        run.complete()
        assertEquals(AgentRunStatus.BUDGET_EXHAUSTED, run.snapshot.status)
    }

    @Test
    fun `finishing exactly on budget succeeds`() {
        val run = AgentRunTracker(AgentMode.PLAN, 1)
        run.beginStep()
        run.complete()
        assertEquals(AgentRunStatus.COMPLETED, run.snapshot.status)
        assertThrows(IllegalStateException::class.java) { run.resume(AgentMode.PLAN) }
        assertThrows(IllegalStateException::class.java) { run.beginTool("read") }
    }

    @Test
    fun `tool calls do not consume additional model steps`() {
        val run = AgentRunTracker(AgentMode.EXECUTE, 1)
        run.beginStep()
        repeat(3) { run.beginTool("read_$it") }
        assertEquals(1, run.snapshot.steps)
        assertEquals(3, run.snapshot.toolCalls)
        assertEquals("read_2", run.snapshot.currentTool)
    }

    @Test
    fun `waiting for an answer is not completion`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8)
        run.waitForUser(true)
        run.complete()
        assertEquals(AgentRunStatus.WAITING_INPUT, run.snapshot.status)
        assertTrue(run.snapshot.status.isWaiting)
    }

    @Test
    fun `cancellation while waiting is terminal`() {
        val run = AgentRunTracker(AgentMode.EXECUTE, 8)
        run.waitForUser(false)
        run.fail(CancellationException("stop"))
        assertEquals(AgentRunStatus.CANCELLED, run.snapshot.status)
        assertNull(run.snapshot.detail)
        assertThrows(IllegalStateException::class.java) { run.resume(AgentMode.EXECUTE) }
    }

    @Test
    fun `failures are displayed and not converted into completion`() {
        val run = AgentRunTracker(AgentMode.PLAN, 8)
        run.fail(IllegalStateException("connection failed"))
        run.complete()
        assertEquals(AgentRunStatus.FAILED, run.snapshot.status)
        assertEquals("connection failed", run.snapshot.detail)
    }

    @Test
    fun `invalid imported budgets are clamped`() {
        assertEquals(1, AgentRunTracker(AgentMode.PLAN, -1).snapshot.maxSteps)
        assertEquals(256, AgentRunTracker(AgentMode.PLAN, Int.MAX_VALUE).snapshot.maxSteps)
    }

    @Test
    fun `updates expose phase and count before work starts`() {
        val updates = mutableListOf<AgentRun>()
        val run = AgentRunTracker(AgentMode.EXECUTE, 8, updates::add)
        run.beginStep()
        run.beginTool("write")
        run.complete()
        assertEquals(listOf(AgentRunStatus.GENERATING, AgentRunStatus.EXECUTING_TOOL, AgentRunStatus.COMPLETED),
            updates.map { it.status })
        assertEquals(1, updates.first().steps)
        assertNull(updates.last().currentTool)
    }
}
