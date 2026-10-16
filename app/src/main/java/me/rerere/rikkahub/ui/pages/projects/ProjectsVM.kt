package me.rerere.rikkahub.ui.pages.projects

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class ProjectsVM(private val repository: WorkspaceRepository, private val context: Application) : ViewModel() {
    val projects = repository.listFlow().map { it.sortedByDescending { row -> row.lastAccessAt ?: row.updatedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val error = MutableStateFlow<String?>(null)
    fun create(name: String, onCreated: (String) -> Unit) = viewModelScope.launch {
        perform {
            val base = name.trim().ifBlank { "未命名项目" }
            var candidate = base
            var suffix = 2
            while (repository.isNameTaken(candidate, null)) candidate = "$base ${suffix++}"
            onCreated(repository.create(candidate).id)
        }
    }
    fun rename(id: String, name: String) = viewModelScope.launch { perform { repository.rename(id, name) } }
    fun delete(id: String) = viewModelScope.launch { perform { repository.delete(id); ProjectThumbnails.delete(context, id) } }
    private suspend fun perform(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error.value = e.message ?: "操作失败" }
    }
}
