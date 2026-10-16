package me.rerere.rikkahub.ui.pages.projects

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class ProjectDetailVM(
    private val id: String,
    private val repository: WorkspaceRepository,
    private val conversationsRepository: ConversationRepository,
    private val context: Application,
) : ViewModel() {
    val project = repository.listFlow().map { it.find { row -> row.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val conversations = conversationsRepository.getConversationsOfWorkspace(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val error = MutableStateFlow<String?>(null)
    init { viewModelScope.launch { perform { repository.touch(id) } } }
    fun rename(name: String) = viewModelScope.launch { perform { repository.rename(id, name) } }
    fun delete(onDeleted: () -> Unit) = viewModelScope.launch {
        perform { repository.delete(id); ProjectThumbnails.delete(context, id); onDeleted() }
    }
    fun deleteConversation(conversation: Conversation) = viewModelScope.launch {
        perform { conversationsRepository.getConversationById(conversation.id)?.let { conversationsRepository.deleteConversation(it) } }
    }
    private suspend fun perform(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error.value = e.message ?: "操作失败" }
    }
}
