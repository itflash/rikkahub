package me.rerere.rikkahub.ui.pages.projects.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.projects.ProjectFileServer
import me.rerere.rikkahub.ui.pages.projects.projectHtmlFiles
import me.rerere.workspace.WorkspaceStorageArea

class ProjectPreviewVM(private val id: String, private val repository: WorkspaceRepository, private val conversations: me.rerere.rikkahub.data.repository.ConversationRepository) : ViewModel() {
    data class State(val server: ProjectFileServer? = null, val paths: List<String> = emptyList(), val loading: Boolean = true, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    suspend fun targetConversation(preferred: String?): String = preferred
        ?: conversations.getConversationsOfWorkspace(id).first().firstOrNull()?.id?.toString()
        ?: kotlin.uuid.Uuid.random().toString()

    fun refresh() = viewModelScope.launch {
        try {
            val root = repository.resolveDirectory(id, WorkspaceStorageArea.FILES, "")
            val paths = withContext(Dispatchers.IO) { projectHtmlFiles(root) }
            _state.value = State(_state.value.server ?: ProjectFileServer(root), paths, false)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { _state.value = State(loading = false, error = e.message ?: "项目无法打开") }
    }
}
