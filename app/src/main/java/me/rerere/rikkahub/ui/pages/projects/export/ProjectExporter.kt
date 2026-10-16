package me.rerere.rikkahub.ui.pages.projects.export

import android.app.Activity
import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.export.HtmlInliner
import me.rerere.rikkahub.data.export.OkHttpResourceDownloader
import me.rerere.rikkahub.data.export.ProjectZipper
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.projects.ProjectFileServer
import me.rerere.rikkahub.ui.pages.projects.projectHtmlFiles
import me.rerere.workspace.WorkspaceStorageArea
import okhttp3.OkHttpClient
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface ExportResult {
    val files: List<File>
    val warnings: List<String>
    data class Images(override val files: List<File>, val mode: CaptureMode, override val warnings: List<String>) : ExportResult
    data class Html(val file: File, override val warnings: List<String>) : ExportResult { override val files get() = listOf(file) }
    data class Zip(val file: File, override val warnings: List<String> = emptyList()) : ExportResult { override val files get() = listOf(file) }
}
enum class ExportFormat(val title: String, val description: String) {
    IMAGES("长图", "适合直接发微信、朋友圈"), HTML("HTML", "单个文件，对方点开就能看"), ZIP("zip", "整个项目打包，可继续编辑"),
}

class ProjectExporter(private val context: Application, private val repository: WorkspaceRepository, private val client: OkHttpClient) : ViewModel() {
    data class State(val running: Boolean = false, val progress: String = "", val result: ExportResult? = null, val error: String? = null)
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun generate(id: String, path: String?, format: ExportFormat, activity: Activity) {
        val previous = job
        job = viewModelScope.launch {
            previous?.cancelAndJoin()
            _state.value = State(running = true, progress = "正在准备…")
            try {
                exportMutex.withLock {
                    val outputDir = File(context.cacheDir, "project_export")
                    try {
                        withContext(Dispatchers.IO) { check(!outputDir.exists() || outputDir.deleteRecursively()); check(outputDir.mkdirs()) }
                        val project = repository.getById(id) ?: error("项目不存在")
                        val root = repository.resolveDirectory(id, WorkspaceStorageArea.FILES, "")
                        val entry = path ?: withContext(Dispatchers.IO) {
                            projectHtmlFiles(root).let { paths -> paths.firstOrNull { it == "index.html" } ?: paths.firstOrNull() }
                        }
                        val safeName = project.name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim().trim('.').ifBlank { "项目" }.take(80)
                        val baseName = "$safeName-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}"
                        val result = when (format) {
                            ExportFormat.IMAGES -> {
                                require(entry != null) { "还没有可导出的 HTML 页面" }
                                _state.value = State(running = true, progress = "正在加载页面…")
                                val capture = WebPageCapturer(activity).capture(ProjectFileServer(root), entry, outputDir, baseName) { current, total ->
                                    _state.value = State(running = true, progress = "正在截图 $current/$total")
                                }
                                ExportResult.Images(capture.files, capture.mode, capture.warnings)
                            }
                            ExportFormat.HTML -> withContext(Dispatchers.IO) {
                                require(entry != null) { "还没有可导出的 HTML 页面" }
                                val inlined = HtmlInliner(OkHttpResourceDownloader(client)).inline(root, entry)
                                currentCoroutineContext().ensureActive()
                                val file = File(outputDir, "$baseName.html").apply { writeText(inlined.html) }
                                val warnings = buildList {
                                    if (inlined.failedUrls.isNotEmpty()) add("${inlined.failedUrls.size} 个外部资源下载失败，已保留原链接（断网时可能无法显示）")
                                    if (inlined.skippedForSize.isNotEmpty()) add("${inlined.skippedForSize.size} 个资源过大未内联")
                                }
                                ExportResult.Html(file, warnings)
                            }
                            ExportFormat.ZIP -> runInterruptible(Dispatchers.IO) {
                                val file = File(outputDir, "$baseName.zip")
                                ProjectZipper.zip(root, safeName, file)
                                ExportResult.Zip(file)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        _state.value = State(result = result)
                    } catch (error: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) { outputDir.deleteRecursively() }
                        throw error
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { _state.value = State(error = e.message ?: "导出失败") }
        }
    }

    fun cancel() { job?.cancel(); _state.value = State() }
    companion object { private val exportMutex = Mutex() }
}
