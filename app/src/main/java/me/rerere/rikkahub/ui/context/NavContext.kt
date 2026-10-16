package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey
import me.rerere.rikkahub.Screen

class Navigator(private val backStack: MutableList<NavKey>) {
    fun navigate(screen: Screen, builder: NavigateOptionsBuilder.() -> Unit = {}) {
        val options = NavigateOptionsBuilder().apply(builder)

        options.popUpToScreen?.let { target ->
            val targetIndex = backStack.indexOfLast { it == target }
            if (targetIndex != -1) {
                val removeFromIndex = if (options.popUpToInclusive) targetIndex else targetIndex + 1
                repeat(backStack.size - removeFromIndex) {
                    backStack.removeLastOrNull()
                }
            }
        }

        if (options.launchSingleTop && backStack.lastOrNull() == screen) {
            return
        }

        backStack.add(screen)
    }

    fun clearAndNavigate(screen: Screen) {
        backStack.clear()
        if (screen != Screen.Projects) backStack.add(Screen.Projects)
        backStack.add(screen)
    }

    fun backToProject(id: String) {
        val target = Screen.ProjectDetail(id)
        if (backStack.dropLast(1).lastOrNull() is Screen.ProjectDetail &&
            (backStack.dropLast(1).lastOrNull() as Screen.ProjectDetail).id == id) {
            popBackStack()
        } else {
            val index = backStack.indexOfLast { it is Screen.ProjectDetail && it.id == id }
            if (index >= 0) while (backStack.size > index + 1) backStack.removeLastOrNull()
            else clearAndNavigate(target)
        }
    }

    /** Return to the existing Chat destination instead of pushing a duplicate. */
    fun returnToChat(id: String, text: String, workspaceId: String) {
        val index = backStack.indexOfLast { it is Screen.Chat && it.id == id }
        if (index >= 0) {
            while (backStack.size > index + 1) backStack.removeLastOrNull()
            backStack[index] = (backStack[index] as Screen.Chat).copy(text = text)
        } else navigate(Screen.Chat(id, text = text, workspaceId = workspaceId))
    }

    fun popBackStack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
}

class NavigateOptionsBuilder {
    internal var popUpToScreen: Screen? = null
    internal var popUpToInclusive: Boolean = false
    var launchSingleTop: Boolean = false

    fun popUpTo(screen: Screen, builder: PopUpToBuilder.() -> Unit = {}) {
        val options = PopUpToBuilder().apply(builder)
        popUpToScreen = screen
        popUpToInclusive = options.inclusive
    }
}

class PopUpToBuilder {
    var inclusive: Boolean = false
}

val LocalNavController = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
