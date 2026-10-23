package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

/** Execution policy, independent of the assistant's system prompt. */
@Serializable
enum class AgentMode {
    CHAT,
    PLAN,
    EXECUTE,
}
