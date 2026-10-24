package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AgentCheckpointTest {
    private val write = UIMessagePart.Tool("call-write", "workspace_write_file", """{"path":"/workspace/a.txt"}""",
        approvalState = ToolApprovalState.Pending)
    private val conversation = Conversation(
        assistantId = Uuid.random(),
        config = ConversationConfig(agentMode = AgentMode.EXECUTE, workspaceId = Uuid.random()),
        messageNodes = listOf(UIMessage.user("Write a file").toMessageNode(),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(write)).toMessageNode()),
        workspaceCwd = "/workspace",
    )
    private val run = AgentRun(mode = AgentMode.EXECUTE, maxSteps = 8, steps = 3, toolCalls = 2,
        status = AgentRunStatus.WAITING_APPROVAL)
    private fun checkpoint(phase: AgentCheckpointPhase = AgentCheckpointPhase.WAITING_USER, run: AgentRun = this.run) =
        AgentCheckpoint(run = run, phase = phase, fingerprint = agentCheckpointFingerprint(conversation))
    private fun tools(vararg tools: UIMessagePart.Tool): Conversation = conversation.updateCurrentMessages(
        conversation.currentMessages.dropLast(1) + conversation.currentMessages.last().copy(parts = tools.toList()))

    @Test fun `waiting approvals recover original run and counters through serialization`() {
        val checkpoint = checkpoint()
        val restored = restoreAgentCheckpoint(JsonInstant.encodeToString(checkpoint), conversation)!!
        assertEquals(run, restored)
        val tracker = AgentRunTracker.restore(restored) {}
        tracker.resume(AgentMode.PLAN)
        assertEquals(run.id, tracker.snapshot.id)
        assertEquals(3, tracker.snapshot.steps)
        assertEquals(8, tracker.snapshot.maxSteps)
        tracker.beginStep()
        assertEquals(4, tracker.snapshot.steps)
    }

    @Test fun `approval decisions do not invalidate inputs but require explicit continuation`() {
        val approved = tools(write.copy(approvalState = ToolApprovalState.Approved))
        assertEquals(AgentRunStatus.PAUSED, checkpoint().recover(approved).status)
        val denied = tools(write.copy(approvalState = ToolApprovalState.Denied("no")))
        assertEquals(AgentRunStatus.PAUSED, checkpoint().recover(denied).status)
    }

    @Test fun `ask user waiting state is restored separately`() {
        val asking = tools(write.copy(toolName = "ask_user"))
        assertEquals(AgentRunStatus.WAITING_INPUT, checkpoint().copy(
            fingerprint = agentCheckpointFingerprint(asking)).recover(asking).status)
    }

    @Test fun `in flight marker blocks replay even if process died before actual invocation`() {
        val checkpoint = checkpoint(AgentCheckpointPhase.TOOL_IN_FLIGHT,
            run.copy(status = AgentRunStatus.EXECUTING_TOOL, currentTool = write.toolName, currentToolCallId = write.toolCallId))
        val restored = checkpoint.recover(conversation)
        assertEquals(AgentRunStatus.INTERRUPTED, restored.status)
        assertEquals(write.toolCallId, restored.currentToolCallId)
        assertThrows(IllegalStateException::class.java) {
            AgentRunTracker.restore(restored) {}.resume(AgentMode.EXECUTE)
        }
    }

    @Test fun `marker also blocks replay if phase was inconsistent`() {
        val checkpoint = checkpoint(AgentCheckpointPhase.READY, run.copy(currentToolCallId = write.toolCallId))
        assertEquals(AgentRunStatus.INTERRUPTED, checkpoint.recover(conversation).status)
    }

    @Test fun `request interruption can be retried but spent steps are not refunded`() {
        val checkpoint = checkpoint(AgentCheckpointPhase.MODEL_REQUEST,
            run.copy(status = AgentRunStatus.GENERATING, steps = 8))
        val restored = checkpoint.recover(conversation)
        assertEquals(AgentRunStatus.PAUSED, restored.status)
        val tracker = AgentRunTracker.restore(restored) {}
        tracker.resume(AgentMode.EXECUTE)
        assertThrows(AgentStepBudgetExceeded::class.java) { tracker.beginStep() }
        assertEquals(8, tracker.snapshot.steps)
    }

    @Test fun `finished reply never requests another provider response`() {
        assertEquals(AgentRunStatus.COMPLETED, checkpoint(AgentCheckpointPhase.FINISHED,
            run.copy(status = AgentRunStatus.GENERATING)).recover(conversation).status)
    }

    @Test fun `terminal failures and cancellation remain terminal`() {
        listOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELLED, AgentRunStatus.BUDGET_EXHAUSTED).forEach { status ->
            assertEquals(status, checkpoint(run = run.copy(status = status)).recover(conversation).status)
        }
    }

    @Test fun `tool result commit prevents replay of previously completed batch members`() {
        val done = write.copy(output = listOf(UIMessagePart.Text("saved")), approvalState = ToolApprovalState.Approved)
        val next = write.copy(toolCallId = "next")
        val updated = tools(done, next)
        val checkpoint = checkpoint(AgentCheckpointPhase.READY, run.copy(status = AgentRunStatus.PREPARING))
            .copy(fingerprint = agentCheckpointFingerprint(updated))
        assertEquals(AgentRunStatus.WAITING_APPROVAL, checkpoint.recover(updated).status)
        assertEquals(listOf("next"), updated.currentMessages.last().getTools().filter { !it.isExecuted }.map { it.toolCallId })
    }

    @Test fun `changed tool inputs outputs conversation identity and workspace block recovery`() {
        val checkpoint = checkpoint()
        listOf(
            tools(write.copy(input = "{}")),
            tools(write.copy(output = listOf(UIMessagePart.Text("different result")))),
            conversation.copy(id = Uuid.random()),
            conversation.copy(assistantId = Uuid.random()),
            conversation.copy(workspaceCwd = "/workspace/other"),
            conversation.copy(config = conversation.config!!.copy(workspaceId = Uuid.random())),
            conversation.copy(config = conversation.config!!.copy(chatModelId = Uuid.random())),
            conversation.copy(messageNodes = conversation.messageNodes.dropLast(1)),
        ).forEach { assertEquals(AgentRunStatus.INTERRUPTED, checkpoint.recover(it).status) }
    }

    @Test fun `metadata mode and future task budget changes do not reset current budget`() {
        val changed = conversation.copy(title = "new title", isPinned = true,
            config = conversation.config!!.copy(agentMode = AgentMode.PLAN, agentMaxSteps = 128))
        assertEquals(run, checkpoint().recover(changed))
    }

    @Test fun `missing corrupt or future checkpoints fail closed`() {
        assertNull(restoreAgentCheckpoint(null, conversation))
        assertEquals(AgentRunStatus.INTERRUPTED, restoreAgentCheckpoint("not json", conversation)?.status)
        assertEquals(AgentRunStatus.INTERRUPTED, checkpoint().copy(version = 2).recover(conversation).status)
        assertEquals(AgentRunStatus.INTERRUPTED, checkpoint(run = run.copy(steps = 9)).recover(conversation).status)
    }

    @Test fun `cancellation during an active tool reports unknown outcome instead of safe retry`() {
        val tracker = AgentRunTracker(AgentMode.EXECUTE, 8)
        tracker.beginStep()
        tracker.beginTool(write.toolName, write.toolCallId)
        tracker.fail(CancellationException("stopped"))
        assertEquals(AgentRunStatus.INTERRUPTED, tracker.snapshot.status)
        assertEquals(write.toolCallId, tracker.snapshot.currentToolCallId)
    }

    @Test fun `recovered approvals require confirmation again but answers denials and results are retained`() {
        val oldApproved = write.copy(approvalState = ToolApprovalState.Approved)
        val completed = write.copy(toolCallId = "done", approvalState = ToolApprovalState.Approved,
            output = listOf(UIMessagePart.Text("saved")))
        val denied = write.copy(toolCallId = "denied", approvalState = ToolApprovalState.Denied("no"))
        val answered = write.copy(toolCallId = "answer", approvalState = ToolApprovalState.Answered("yes"))
        val source = tools(oldApproved, completed, denied, answered)
        val restored = resetRecoveredApprovals(source)
        assertEquals(agentCheckpointFingerprint(source), agentCheckpointFingerprint(restored))
        val calls = restored.currentMessages.last().getTools()
        assertEquals(ToolApprovalState.Pending, calls[0].approvalState)
        assertEquals(completed, calls[1])
        assertEquals(denied, calls[2])
        assertEquals(answered, calls[3])
    }

    @Test fun `recovery binds historical generation prefix instead of the final conversation node`() {
        val extended = conversation.copy(messageNodes = conversation.messageNodes + UIMessage.user("later context").toMessageNode())
        val saved = checkpoint(run = run.copy(contextMessageCount = 2)).copy(fingerprint = agentCheckpointFingerprint(extended))
        assertEquals(AgentRunStatus.WAITING_APPROVAL, saved.recover(extended).status)
        val approved = extended.updateCurrentMessages(extended.currentMessages.mapIndexed { index, message ->
            if (index == 1) message.copy(parts = listOf(write.copy(approvalState = ToolApprovalState.Approved))) else message
        })
        val restored = resetRecoveredApprovals(approved, 2)
        assertEquals(ToolApprovalState.Pending, restored.currentMessages[1].getTools().single().approvalState)
        assertEquals("later context", restored.currentMessages.last().toText())
        assertEquals(AgentRunStatus.INTERRUPTED, saved.copy(run = run.copy(contextMessageCount = 99)).recover(extended).status)
    }

    @Test fun `known result clears in flight marker before next safe boundary`() {
        val tracker = AgentRunTracker(AgentMode.EXECUTE, 8)
        tracker.beginTool(write.toolName, write.toolCallId)
        tracker.finishTool()
        assertNull(tracker.snapshot.currentToolCallId)
        assertEquals(AgentRunStatus.PREPARING, tracker.snapshot.status)
        assertEquals(1, tracker.snapshot.toolCalls)
    }
}
