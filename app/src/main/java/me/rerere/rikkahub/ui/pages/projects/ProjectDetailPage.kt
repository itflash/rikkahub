package me.rerere.rikkahub.ui.pages.projects

import android.content.Intent
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceFilesPage
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

@Composable
fun ProjectDetailPage(id: String, initialTab: Int = 0) {
    val vm: ProjectDetailVM = koinViewModel(key = "project-$id", parameters = { parametersOf(id) })
    val filesVM: WorkspaceDetailVM = koinViewModel(key = "project-files-$id", parameters = { parametersOf(id) })
    val project by vm.project.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val files by filesVM.state.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val context = LocalContext.current
    var tab by rememberSaveable(id) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var createMenu by remember { mutableStateOf(false) }
    var createDirectory by remember { mutableStateOf<Boolean?>(null) }
    var deleteFile by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var deleteChat by remember { mutableStateOf<Conversation?>(null) }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget.also { exportTarget = null }
        if (uri != null && target != null) context.contentResolver.openOutputStream(uri)?.let { filesVM.exportFile(target, it) }
    }
    val exportDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { filesVM.exportFilesToDirectory(it, context.contentResolver) }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "imported_file"
            context.contentResolver.openInputStream(uri)?.let { filesVM.importFile(it, name) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { filesVM.refresh() }
    LaunchedEffect(error) { error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.error.value = null } }
    BackHandler(tab == 1 && files.path.isNotEmpty()) { filesVM.goUp() }
    fun newChat() { navigator.navigate(Screen.Chat(Uuid.random().toString(), workspaceId = id)) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(project?.name ?: "项目") }, navigationIcon = { BackButton() }, actions = {
            TextButton(onClick = { navigator.navigate(Screen.ProjectPreview(id)) }) { Text("预览") }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = true })
                    DropdownMenuItem(text = { Text("高级设置") }, onClick = { menu = false; navigator.navigate(Screen.WorkspaceDetail(id)) })
                }
            }
        }) },
        floatingActionButton = {
            if (tab == 0) ExtendedFloatingActionButton(onClick = ::newChat, icon = { Text("✎") }, text = { Text("新对话") })
            else ExtendedFloatingActionButton(onClick = { createMenu = true }, icon = { Text("＋") }, text = { Text("新建") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("对话", "文件").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) }
            }
            if (tab == 0) {
                if (conversations.isEmpty()) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有对话", style = MaterialTheme.typography.titleLarge)
                    FilledTonalButton(onClick = ::newChat) { Text("开始创作") }
                } else LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp)) {
                    item {
                        CardGroup {
                            conversations.forEach { conversation -> item(
                                onClick = { navigator.navigate(Screen.Chat(conversation.id.toString())) },
                                headlineContent = { Text(conversation.title.ifBlank { "新对话" }) },
                                supportingContent = { Text(relativeProjectTime(conversation.updateAt.toEpochMilli())) },
                                trailingContent = { TextButton(onClick = { deleteChat = conversation }) { Text("删除") } },
                            ) }
                        }
                    }
                }
            } else WorkspaceFilesPage(
                state = files, isActive = true, showAreaSwitcher = false,
                onSelectArea = filesVM::selectArea, onGoUp = filesVM::goUp, onNavigatePath = filesVM::goTo,
                onResolveImage = filesVM::resolveImageFile,
                onOpen = { entry ->
                    if (entry.isDirectory) filesVM.open(entry)
                    else if (entry.name.substringAfterLast('.').lowercase() in setOf("html", "htm")) navigator.navigate(Screen.ProjectPreview(id, entry.path))
                    else navigator.navigate(Screen.WorkspaceFileEditor(id, WorkspaceStorageArea.FILES.name, entry.path))
                },
                onDelete = { deleteFile = it }, onRename = filesVM::renameFile,
                onExport = { exportTarget = it; exportFile.launch(it.name) },
                onBatchExport = { if (filesVM.prepareBatchExport(it)) exportDirectory.launch(null) },
                onShare = { entry -> filesVM.exportToCacheFile(entry, context.cacheDir) { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                } },
            )
        }
    }
    if (rename) ProjectNameDialog("重命名项目", project?.name.orEmpty(), { rename = false }) { vm.rename(it); rename = false }
    if (deleting) ProjectDeleteDialog(project?.name.orEmpty(), { deleting = false }) { vm.delete { navigator.clearAndNavigate(Screen.Projects) } }
    if (createMenu) ModalBottomSheet(onDismissRequest = { createMenu = false }) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            listOf("新建文件夹", "新建文件", "从手机导入").forEachIndexed { index, label ->
                TextButton(onClick = { createMenu = false; if (index == 2) importFile.launch(arrayOf("*/*")) else createDirectory = index == 0 }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
    createDirectory?.let { directory -> ProjectNameDialog(if (directory) "新建文件夹" else "新建文件", "", { createDirectory = null }) {
        filesVM.createEntry(it, directory); createDirectory = null
    } }
    deleteFile?.let { target -> AlertDialog(onDismissRequest = { deleteFile = null }, title = { Text("删除文件") }, text = { Text("删除「${target.name}」？") },
        confirmButton = { TextButton(onClick = { filesVM.delete(target); deleteFile = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("取消") } }) }
    deleteChat?.let { target -> AlertDialog(onDismissRequest = { deleteChat = null }, title = { Text("删除对话") }, text = { Text("确定删除这个对话？") },
        confirmButton = { TextButton(onClick = { vm.deleteConversation(target); deleteChat = null }) { Text("删除") } }, dismissButton = { TextButton(onClick = { deleteChat = null }) { Text("取消") } }) }
    files.exportResult?.let { result -> AlertDialog(onDismissRequest = filesVM::dismissExportResult, title = { Text("导出结果") }, text = { Text(result) },
        confirmButton = { TextButton(onClick = filesVM::dismissExportResult) { Text("确定") } }) }
}
