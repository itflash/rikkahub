package me.rerere.rikkahub.data.ai.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.local.buildAskUserTool
import me.rerere.rikkahub.data.ai.tools.local.buildTimeInfoTool
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.AgentMode
import org.junit.Assert.*
import org.junit.Test

class AgentPolicyTest {
    private val args = JsonObject(emptyMap())
    private fun tool(name: String, readOnly: Boolean = false, approval: Boolean = false) = Tool(
        name = name,
        description = name,
        readOnly = readOnly,
        needsApproval = { approval },
        execute = { listOf(UIMessagePart.Text("result")) },
    )
    private fun call(name: String, state: ToolApprovalState = ToolApprovalState.Auto) = UIMessagePart.Tool(
        toolCallId = name,
        toolName = name,
        input = "{}",
        approvalState = state,
    )

    @Test
    fun `chat preserves the original tools and approvals`() {
        val original = listOf(tool("write"), tool("read", readOnly = true, approval = true))
        assertSame(original, AgentPolicy(AgentMode.CHAT).tools(original))
    }

    @Test
    fun `plan only advertises explicitly trusted read-only tools`() {
        val original = listOf(
            tool("workspace_read_file", readOnly = true),
            tool("workspace_shell"), tool("workspace_write_file"), tool("memory_write"),
            tool("mcp__server__read_file"), tool("unknown"),
        )
        assertEquals(listOf(original.first()), AgentPolicy(AgentMode.PLAN).tools(original))
        // A read-like name alone does not grant trust.
        assertTrue(AgentPolicy(AgentMode.PLAN).tools(listOf(tool("workspace_read_file"))).isEmpty())
    }

    @Test
    fun `execute adds approval for unknown and write tools without weakening read approvals`() {
        val result = AgentPolicy(AgentMode.EXECUTE).tools(listOf(
            tool("write"), tool("mcp__server__unknown"),
            tool("read", readOnly = true), tool("private_read", readOnly = true, approval = true),
        ))
        assertEquals(listOf(true, true, false, true), result.map { it.needsApproval(args) })
    }

    @Test
    fun `approved write from earlier mode is denied in plan`() {
        val available = AgentPolicy(AgentMode.PLAN).tools(listOf(tool("write")))
        val result = prepareAgentToolCalls(listOf(call("write", ToolApprovalState.Approved)), available)
        assertTrue(result.single().approvalState is ToolApprovalState.Denied)
    }

    @Test
    fun `resuming an approved call also rechecks its auto-approved siblings`() {
        val available = AgentPolicy(AgentMode.EXECUTE).tools(listOf(tool("shell"), tool("write")))
        val result = prepareAgentToolCalls(listOf(
            call("shell", ToolApprovalState.Approved), call("write"),
        ), available)
        assertEquals(ToolApprovalState.Approved, result[0].approvalState)
        assertEquals(ToolApprovalState.Pending, result[1].approvalState)
    }

    @Test
    fun `denials pending approvals and user answers are preserved`() {
        val ask = buildAskUserTool()
        listOf(ToolApprovalState.Pending, ToolApprovalState.Denied("no"), ToolApprovalState.Answered("yes"))
            .forEach { state ->
                val call = call(ask.name, state)
                assertEquals(call, prepareAgentToolCalls(listOf(call), listOf(ask)).single())
            }
        assertEquals(ToolApprovalState.Pending,
            prepareAgentToolCalls(listOf(call(ask.name)), listOf(ask)).single().approvalState)
    }

    @Test
    fun `unavailable pending tools become denied rather than waiting forever`() {
        val result = prepareAgentToolCalls(listOf(call("shell", ToolApprovalState.Pending)), emptyList())
        assertTrue(result.single().approvalState is ToolApprovalState.Denied)
    }

    @Test
    fun `already executed calls are not rewritten or rerun`() {
        val done = call("write").copy(output = listOf(UIMessagePart.Text("done")))
        assertSame(done, prepareAgentToolCalls(listOf(done), emptyList()).single())
    }

    @Test
    fun `policy preparation does not execute tools`() = runBlocking {
        var executions = 0
        val write = tool("write").copy(execute = {
            executions++
            listOf(UIMessagePart.Text("done"))
        })
        val available = AgentPolicy(AgentMode.EXECUTE).tools(listOf(write))
        assertEquals(ToolApprovalState.Pending,
            prepareAgentToolCalls(listOf(call("write")), available).single().approvalState)
        assertEquals(0, executions)
        available.single().execute(args)
        assertEquals(1, executions)
    }

    @Test
    fun `plan preserves read-only server tools but removes image generation`() {
        val original = Model(tools = setOf(BuiltInTools.Search, BuiltInTools.UrlContext, BuiltInTools.ImageGeneration))
        assertEquals(setOf(BuiltInTools.Search, BuiltInTools.UrlContext), AgentPolicy(AgentMode.PLAN).model(original).tools)
        assertSame(original, AgentPolicy(AgentMode.CHAT).model(original))
        assertEquals(setOf(BuiltInTools.Search, BuiltInTools.UrlContext), AgentPolicy(AgentMode.EXECUTE).model(original).tools)
    }

    @Test
    fun `plan cannot have server tools injected through custom request bodies`() {
        val original = listOf(CustomBody("tools", JsonPrimitive("override")))
        assertTrue(AgentPolicy(AgentMode.PLAN).customBody(original).isEmpty())
        assertTrue(AgentPolicy(AgentMode.EXECUTE).customBody(original).isEmpty())
        assertSame(original, AgentPolicy(AgentMode.CHAT).customBody(original))
    }

    @Test
    fun `built-in research and clarification tools opt in to plan mode`() {
        val tools = createSearchTools(Settings()).toList() + buildAskUserTool() + buildTimeInfoTool()
        assertEquals(tools, AgentPolicy(AgentMode.PLAN).tools(tools))
        assertTrue(buildAskUserTool().needsApproval(args))
    }

    @Test
    fun `chat adds no agent instruction and other modes have distinct instructions`() {
        assertEquals("", AgentPolicy(AgentMode.CHAT).systemPrompt)
        assertTrue(AgentPolicy(AgentMode.PLAN).systemPrompt.contains("PLAN mode"))
        assertTrue(AgentPolicy(AgentMode.EXECUTE).systemPrompt.contains("EXECUTE mode"))
    }
}
