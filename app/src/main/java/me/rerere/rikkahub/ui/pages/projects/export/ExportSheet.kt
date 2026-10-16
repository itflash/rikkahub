package me.rerere.rikkahub.ui.pages.projects.export

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.projects.projectHtmlFiles
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.getActivity
import me.rerere.rikkahub.utils.saveImagesToGallery
import me.rerere.rikkahub.utils.saveToDownloads
import me.rerere.rikkahub.utils.shareFiles
import org.koin.androidx.compose.koinViewModel

@Composable
fun ExportSheet(id: String, entryPath: String?, onDismissRequest: () -> Unit) {
    val vm: ProjectExporter = koinViewModel(key = "export-$id")
    val state by vm.state.collectAsStateWithLifecycle()
    val repository: WorkspaceRepository = koinInject()
    val resolvedEntry by produceState(entryPath, id, entryPath) {
        if (entryPath == null) try {
            val root = repository.resolveDirectory(id, WorkspaceStorageArea.FILES, "")
            value = withContext(Dispatchers.IO) {
                projectHtmlFiles(root).let { paths -> paths.firstOrNull { it == "index.html" } ?: paths.firstOrNull() }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { value = null }
    }
    val context = LocalContext.current
    val activity = context.getActivity()
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.IMAGES) }
    var saving by remember { mutableStateOf(false) }
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    ModalBottomSheet(onDismissRequest = { vm.cancel(); onDismissRequest() }) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val result = state.result
            when {
                state.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Text(state.progress, style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
                result != null -> {
                    Text(when (result) {
                        is ExportResult.Images -> when (result.mode) {
                            CaptureMode.SINGLE -> "长页面 · ${result.files.size} 张"
                            CaptureMode.SPLIT -> "长页面 · 已切成 ${result.files.size} 张"
                            CaptureMode.SLIDES -> "幻灯片模式 · ${result.files.size} 张"
                            CaptureMode.VIEWPORT_ONLY -> "仅当前屏"
                        }
                        else -> result.files.first().name
                    }, style = MaterialTheme.typography.titleLarge)
                    if (result is ExportResult.Images) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(result.files) { AsyncImage(it, "导出预览", modifier = Modifier.width(88.dp).height(132.dp)) }
                    } else Text(result.files.sumOf { it.length() }.fileSizeToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                    val mime = when (result) { is ExportResult.Images -> "image/png"; is ExportResult.Html -> "text/html"; is ExportResult.Zip -> "application/zip" }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                            scope.launch {
                                saving = true
                                try {
                                    if (result is ExportResult.Images) saveImagesToGallery(context, result.files)
                                    else saveToDownloads(context, result.files.first(), mime)
                                    Toast.makeText(context, if (result is ExportResult.Images) "已保存到 相册/RikkaHub" else "已保存到 下载/RikkaHub", Toast.LENGTH_LONG).show()
                                } catch (e: CancellationException) { throw e
                                } catch (e: Exception) { Toast.makeText(context, e.message ?: "保存失败", Toast.LENGTH_LONG).show()
                                } finally { saving = false }
                            }
                        }) { Text(if (saving) "保存中…" else "保存到本机") }
                        Button(modifier = Modifier.weight(1f), onClick = {
                            runCatching { shareFiles(context, result.files, mime) }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() }
                        }) { Text("分享") }
                    }
                    TextButton(onClick = vm::cancel) { Text("重新选择格式") }
                }
                else -> {
                    Text("导出 ${resolvedEntry ?: "项目"}", style = MaterialTheme.typography.titleLarge)
                    CardGroup {
                        ExportFormat.entries.forEach { option -> item(
                            onClick = { format = option },
                            headlineContent = { Text(option.title) },
                            supportingContent = { Text(option.description) },
                            trailingContent = { RadioButton(selected = format == option, onClick = null) },
                        ) }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(enabled = activity != null, onClick = { activity?.let { vm.generate(id, resolvedEntry, format, it) } }, modifier = Modifier.fillMaxWidth()) { Text("生成") }
                }
            }
        }
    }
}
