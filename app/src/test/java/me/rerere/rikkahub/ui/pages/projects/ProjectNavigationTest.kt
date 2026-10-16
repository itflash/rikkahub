package me.rerere.rikkahub.ui.pages.projects

import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.ConversationConfig
import me.rerere.rikkahub.ui.context.Navigator
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ProjectNavigationTest {
    @Test fun `config encoding matches workspace query`() {
        val id = Uuid.random()
        assertTrue(JsonInstant.encodeToString(ConversationConfig(workspaceId = id)).contains("\"workspaceId\":\"$id\""))
    }
    @Test fun `clear navigation always keeps workbench at bottom`() {
        val stack = mutableListOf<NavKey>(Screen.ProjectDetail("x"))
        val nav = Navigator(stack)
        nav.clearAndNavigate(Screen.Chat("chat"))
        assertEquals(listOf(Screen.Projects, Screen.Chat("chat")), stack)
        nav.clearAndNavigate(Screen.Projects)
        assertEquals(listOf(Screen.Projects), stack)
    }
    @Test fun `project chat opened from notification returns without a back loop`() {
        val stack = mutableListOf<NavKey>(Screen.Projects, Screen.Chat("c"))
        Navigator(stack).backToProject("p")
        assertEquals(listOf(Screen.Projects, Screen.ProjectDetail("p")), stack)
    }

    @Test fun `repair returns to existing chat without duplicate`() {
        val stack = mutableListOf<NavKey>(Screen.Projects, Screen.ProjectDetail("p"), Screen.Chat("c", workspaceId = "p"), Screen.ProjectPreview("p"))
        Navigator(stack).returnToChat("c", "prefilled", "p")
        assertEquals(3, stack.size)
        assertEquals("prefilled", (stack.last() as Screen.Chat).text)
    }
}
