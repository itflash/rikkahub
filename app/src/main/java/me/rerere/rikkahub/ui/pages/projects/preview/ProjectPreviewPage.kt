package me.rerere.rikkahub.ui.pages.projects.preview

import android.content.Intent
import android.webkit.ConsoleMessage
import androidx.compose.foundation.layout.*
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
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.webview.ConsoleLogsSheet
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.ui.pages.projects.ProjectThumbnails
import me.rerere.rikkahub.utils.base64Encode
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun ProjectPreviewPage(id: String, path: String? = null, conversationId: String? = null) {
    val vm: ProjectPreviewVM = koinViewModel(key = "preview-$id", parameters = { parametersOf(id) })
    val project by vm.state.collectAsStateWithLifecycle()
    val navigator = LocalNavController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appScope: AppScope = koinInject()
    var selected by rememberSaveable(id, path) { mutableStateOf(path) }
    var menu by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(false) }
    LaunchedEffect(id) { vm.refresh() }
    val entry = selected ?: project.paths.firstOrNull { it == "index.html" } ?: project.paths.firstOrNull()
    val server = project.server
    val interceptor = remember(server) { server?.let { s -> { uri: android.net.Uri -> s.intercept(uri) } } }
    val state = rememberWebViewState(
        url = if (server != null && entry != null) server.urlOf(entry) else "about:blank",
        requestInterceptor = interceptor,
        settings = {
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
        },
    )
    fun reload() { state.clearConsoleMessages(); state.webView?.reload(); vm.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumed) reload() else resumed = true
    }
    fun captureThumbnail() {
        val view = state.webView ?: return
        appScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            runCatching { ProjectThumbnails.capture(view, id) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { captureThumbnail() }
    DisposableEffect(state, id) { onDispose { captureThumbnail() } }
    val errors = state.consoleMessages.filter { it.messageLevel() == ConsoleMessage.MessageLevel.ERROR }
        .distinctBy { Triple(it.message(), it.sourceId(), it.lineNumber()) }
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Box {
                    TextButton(onClick = { entries = true }) { Text("${entry ?: "项目预览"} ▾", maxLines = 1) }
                    DropdownMenu(expanded = entries, onDismissRequest = { entries = false }) {
                        project.paths.forEach { item ->
                            DropdownMenuItem(text = { Text(item) }, onClick = { selected = item; entries = false; state.clearConsoleMessages() })
                        }
                    }
                }
            },
            navigationIcon = { BackButton() },
            actions = {
                IconButton(onClick = ::reload) { Text("⟳") }
                Box {
                    IconButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("在浏览器中打开") }, enabled = server != null && entry != null, onClick = {
                            menu = false
                            runCatching {
                                val file = File(server!!.filesRoot, entry!!).canonicalFile
                                require(file.toPath().startsWith(server.filesRoot.canonicalFile.toPath()))
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }.onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
                        })
                        DropdownMenuItem(text = { Text("查看 Console") }, onClick = { menu = false; console = true })
                    }
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (project.loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (entry == null || server == null) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(project.error ?: "还没有页面，去和 AI 对话生成一个吧")
                    TextButton(onClick = { navigator.popBackStack() }) { Text("返回") }
                }
            } else {
                WebView(state = state, modifier = Modifier.weight(1f).fillMaxWidth())
            }
            if (errors.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⚠ ${errors.size} 个脚本错误", Modifier.weight(1f))
                    TextButton(onClick = {
                        val message = buildString {
                            appendLine("预览 $entry 时出现了以下脚本错误，请帮我修复：")
                            errors.forEachIndexed { index, error ->
                                appendLine("${index + 1}. ${error.sourceId().removePrefix("https://project.local/")} 第 ${error.lineNumber()} 行：${error.message()}")
                            }
                        }
                        scope.launch {
                            navigator.returnToChat(vm.targetConversation(conversationId), message.base64Encode(), id)
                        }
                    }) { Text("让 AI 修复") }
                }
            }
        }
    }
    if (console) ConsoleLogsSheet(state.consoleMessages) { console = false }
}
