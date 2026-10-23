package me.rerere.rikkahub.data.ai.agent

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AgentMode

/** Applied at the generation boundary, both to advertised tools and resumed tool calls. */
class AgentPolicy(val mode: AgentMode) {
    fun tools(tools: List<Tool>): List<Tool> = when (mode) {
        AgentMode.CHAT -> tools
        AgentMode.PLAN -> tools.filter { it.readOnly }
        AgentMode.EXECUTE -> tools.map { tool ->
            if (tool.readOnly) tool else tool.copy(needsApproval = { true })
        }
    }

    fun model(model: Model): Model = when (mode) {
        AgentMode.PLAN, AgentMode.EXECUTE -> model.copy(
            tools = model.tools.filterTo(mutableSetOf()) {
                it == BuiltInTools.Search || it == BuiltInTools.UrlContext
            },
        )
        AgentMode.CHAT -> model
    }

    // Arbitrary provider request overrides can reintroduce server tools after policy filtering.
    // Agent modes deliberately use standard requests: provider-side tools cannot use local approvals.
    fun customBody(body: List<CustomBody>): List<CustomBody> =
        if (mode == AgentMode.CHAT) body else emptyList()

    val systemPrompt: String
        get() = when (mode) {
            AgentMode.CHAT -> ""
            AgentMode.PLAN -> """
                You are in PLAN mode. Investigate with the available read-only tools, clarify the
                user's goal when necessary, and present an actionable plan. Do not modify files,
                memory, or external state, and do not attempt to bypass the tool restrictions.
                Shell and unverified tools are unavailable. Do not claim to have executed a plan.
                The user must switch to EXECUTE mode before you can make changes.
                This policy takes precedence over workspace and skill instructions to make changes.
            """.trimIndent()
            AgentMode.EXECUTE -> """
                You are in EXECUTE mode. Work toward the user's requested outcome using available
                tools. Inspect relevant context before making changes, verify the result when
                possible, and report completed work, verification, and any remaining limitations.
                Non-read-only tools require user approval. Respect denials; do not bypass them or
                retry the same operation through another tool. Never claim success without evidence.
            """.trimIndent()
        }
}

/** Revalidate every batch, including after approval or a mode change. Executed calls are immutable. */
internal fun prepareAgentToolCalls(
    calls: List<UIMessagePart.Tool>,
    availableTools: List<Tool>,
): List<UIMessagePart.Tool> = calls.map { call ->
    if (call.isExecuted || call.approvalState is ToolApprovalState.Denied) return@map call
    val definition = availableTools.find { it.name == call.toolName }
    when {
        definition == null -> call.copy(
            approvalState = ToolApprovalState.Denied("Tool is unavailable under the current tool policy."),
        )
        call.approvalState is ToolApprovalState.Auto && definition.needsApproval(call.inputAsJson()) ->
            call.copy(approvalState = ToolApprovalState.Pending)
        else -> call
    }
}
