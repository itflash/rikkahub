package me.rerere.rikkahub.ui.pages.projects

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.MoreVertical
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.navigateToChatPage
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun ProjectsPage() {
    val vm: ProjectsVM = koinViewModel()
    val projects by vm.projects.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navigator = LocalNavController.current
    var thumbnailVersion by remember { mutableLongStateOf(0L) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        thumbnailVersion = System.currentTimeMillis()
    }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceEntity?>(null) }
    LaunchedEffect(error) { error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.error.value = null } }
    fun create(name: String) { vm.create(name) { id ->
        creating = false
        navigator.navigate(Screen.ProjectDetail(id))
        navigator.navigate(Screen.Chat(Uuid.random().toString(), workspaceId = id))
    } }
    Scaffold(
        topBar = { LargeTopAppBar(title = { Text("工作台") }, actions = {
            IconButton(onClick = { navigateToChatPage(navigator) }) { Icon(HugeIcons.MessageAdd01, "普通聊天") }
            IconButton(onClick = { navigator.navigate(Screen.Setting) }) { Icon(HugeIcons.Settings03, "设置") }
        }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(HugeIcons.Add01, null) }, text = { Text("新项目") }) },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(HugeIcons.FolderOpen, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text("还没有项目", style = MaterialTheme.typography.titleLarge)
                FilledTonalButton(onClick = { creating = true }) { Text("新建项目") }
            }
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(projects, key = { it.id }) { project ->
                var menu by remember { mutableStateOf(false) }
                Card(onClick = { navigator.navigate(Screen.ProjectDetail(project.id)) }, shape = MaterialTheme.shapes.extraLarge) {
                    val thumb = ProjectThumbnails.file(context, project.id)
                    // Re-evaluate mtime when the page returns to composition.
                    val stamp = remember(thumbnailVersion, project.lastAccessAt) { thumb.lastModified() }
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                        if (thumb.exists()) AsyncImage(
                            model = ImageRequest.Builder(context).data(thumb).memoryCacheKey("${project.id}:$stamp").diskCacheKey("${project.id}:$stamp").build(),
                            contentDescription = "${project.name}预览", contentScale = ContentScale.Crop,
                        ) else Box(contentAlignment = Alignment.Center) {
                            Text(project.name.take(1), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleSmallEmphasized, maxLines = 1)
                            Text(relativeProjectTime(project.lastAccessAt ?: project.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(HugeIcons.MoreVertical, "项目操作") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = project })
                                DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = project })
                            }
                        }
                    }
                }
            }
        }
    }
    if (creating) ProjectNameDialog("新项目", "未命名项目", { creating = false }, ::create)
    renaming?.let { target -> ProjectNameDialog("重命名项目", target.name, { renaming = null }) { vm.rename(target.id, it); renaming = null } }
    deleting?.let { target -> ProjectDeleteDialog(target.name, { deleting = null }) { vm.delete(target.id); deleting = null } }
}

internal fun relativeProjectTime(time: Long): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
internal fun ProjectNameDialog(title: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名称") })
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { confirm(name.trim()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
internal fun ProjectDeleteDialog(name: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("删除项目") }, text = { Text("删除项目「$name」？项目内的文件会一并删除，相关对话保留。") },
        confirmButton = { TextButton(onClick = confirm) { Text("删除") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

internal data class ProjectChatContext(val projectId: String, val conversationId: String)
internal val LocalProjectChat = staticCompositionLocalOf<ProjectChatContext?> { null }
