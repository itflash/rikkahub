package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceStorageArea

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
    "workspace_list_files" to false,
    "workspace_delete_file" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    shellAvailable: Boolean = true,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = cwd?.removePrefix("/workspace/")?.removePrefix("/workspace")

    val access: WorkspaceFileAccess = if (shellAvailable) {
        RootfsFileAccess(workspaceId, workspaceRepository)
    } else {
        FilesAreaFileAccess(workspaceId, workspaceRepository)
    }
    return buildList {
        add(createReadFileTool(::needsApproval, access))
        add(createWriteFileTool(::needsApproval, access))
        add(createEditFileTool(::needsApproval, access))
        add(createListFilesTool(workspaceId, ::needsApproval, workspaceRepository))
        add(createDeleteFileTool(workspaceId, ::needsApproval, workspaceRepository))
        if (shellAvailable) add(createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd))
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    needsApproval: (String) -> Boolean,
    access: WorkspaceFileAccess,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file in the bound workspace. Paths must be absolute; without Linux only /workspace is available.
        Use /workspace for the workspace files area.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
            },
            required = listOf("path"),
        )
    },
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = it.jsonObject.absolutePath("path")
        if (path.isImagePath()) {
            imageParts(path, access.readBytes(path))
        } else {
            val text = access.readText(path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    needsApproval: (String) -> Boolean,
    access: WorkspaceFileAccess,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the bound workspace. Paths must be absolute; without Linux only /workspace is available.
        Use /workspace for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = access.writeText(path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    needsApproval: (String) -> Boolean,
    access: WorkspaceFileAccess,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the bound workspace. Paths must be absolute; without Linux only /workspace is available.
        Use /workspace for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots("path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = access.readText(path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = access.writeText(path, result.updated, overwrite = true)
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at /workspace. ")
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = (params.string("cwd") ?: defaultCwd.orEmpty())
            .removePrefix("/workspace/").removePrefix("/workspace")
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

/**
 * 按 Rootfs 内绝对路径读入内存。路径映射交给 WorkspaceManager, 由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val size = rootfsFileSize(workspaceId, path)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { exportRootfsFile(workspaceId, path, it) }
}

private suspend fun imageParts(path: String, bytes: ByteArray): List<UIMessagePart> {
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

private suspend fun WorkspaceRepository.writeTextInRootfs(
    workspaceId: String,
    path: String,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry {
    val pathArg = path.shellQuote()
    val result = runRootfsCommand(
        workspaceId = workspaceId,
        action = "Write file",
        command = """
            if [ -e $pathArg ] && [ ${(!overwrite).shellFlag()} = 1 ]; then
              printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
              exit 1
            fi
            if [ -e $pathArg ] && [ ! -f $pathArg ]; then
              printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
              exit 1
            fi
            parent=${'$'}(dirname -- $pathArg) || exit 1
            mkdir -p -- "${'$'}parent" || exit 1
            cat > $pathArg || exit 1
            ${statEntryCommand(path)}
        """.trimIndent(),
        stdin = text.toByteArray(Charsets.UTF_8),
    )
    return result.stdout.parseRootfsEntry()
}

private suspend fun WorkspaceRepository.runRootfsCommand(
    workspaceId: String,
    action: String,
    command: String,
    stdin: ByteArray? = null,
): WorkspaceCommandResult {
    val result = executeCommand(
        id = workspaceId,
        command = command,
        timeoutMillis = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin = stdin,
    )
    if (result.timedOut) {
        error("$action timed out")
    }
    if (result.exitCode != 0) {
        val message = result.stderr.ifBlank { result.stdout }.trim()
        error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
    }
    if (result.truncated) {
        error("$action output is too large")
    }
    return result
}

private fun statEntryCommand(path: String): String {
    val pathArg = path.shellQuote()
    return """
        if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
        entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
        entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
        printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
    """.trimIndent()
}

private fun String.parseRootfsEntry(): WorkspaceFileEntry =
    parseRootfsEntries().singleOrNull() ?: error("Invalid file metadata output")

private fun String.parseRootfsEntries(): List<WorkspaceFileEntry> {
    val fields = split('\u0000').dropLastWhile { it.isEmpty() }
    require(fields.size % 4 == 0) { "Invalid file metadata output" }
    return fields.chunked(4).map { chunk ->
        val type = chunk[0]
        val size = chunk[1].toLongOrNull() ?: error("Invalid file size: ${chunk[1]}")
        val updatedAt = (chunk[2].toLongOrNull() ?: error("Invalid file mtime: ${chunk[2]}")) * 1_000L
        val path = chunk[3]
        WorkspaceFileEntry(
            path = path,
            name = path.rootfsName(),
            isDirectory = type == "d",
            sizeBytes = size,
            updatedAt = updatedAt,
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path in the workspace" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区: 工作区文件目录、临时目录和技能目录
private val WRITABLE_ROOT_PREFIXES = listOf("/workspace", "/tmp", "/skills")

private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(name: String): Boolean =
    runCatching {
        jsonObject.absolutePath(name).isOutsideWritableRoots()
    }.getOrDefault(true)

private fun String.isOutsideWritableRoots(): Boolean {
    val normalized = trimEnd('/').ifBlank { "/" }
    return WRITABLE_ROOT_PREFIXES.none { prefix ->
        normalized == prefix || normalized.startsWith("$prefix/")
    }
}

private fun String.rootfsName(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { "/" }

private fun String.shellQuote(): String =
    "'" + replace("'", "'\"'\"'") + "'"

private fun Boolean.shellFlag(): Int = if (this) 1 else 0

private fun JsonObjectBuilder.putPathProperty(required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use /workspace for the workspace files area."
            } else {
                "Optional absolute path in the workspace. Use /workspace for the workspace files area."
            }
        )
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}


internal fun String.toFilesAreaPath(): String {
    require(startsWith('/') && !contains('\u0000')) { "Invalid absolute path: $this" }
    val normalized = Paths.get(this).normalize().toString()
    require(normalized == "/workspace" || normalized.startsWith("/workspace/")) {
        "No Linux environment in this workspace. Only paths under /workspace are available: $this"
    }
    return normalized.removePrefix("/workspace").removePrefix("/")
}

private fun WorkspaceFileEntry.toRootfsView() =
    copy(path = if (path.isEmpty()) "/workspace" else "/workspace/$path")

private interface WorkspaceFileAccess {
    suspend fun readText(path: String): String
    suspend fun readBytes(path: String): ByteArray
    suspend fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry
}

private class RootfsFileAccess(val id: String, val repository: WorkspaceRepository) : WorkspaceFileAccess {
    override suspend fun readText(path: String) = repository.readTextInRootfs(id, path)
    override suspend fun readBytes(path: String) = repository.readRootfsBuffer(id, path).toByteArray()
    override suspend fun writeText(path: String, text: String, overwrite: Boolean) =
        repository.writeTextInRootfs(id, path, text, overwrite)
}

private class FilesAreaFileAccess(val id: String, val repository: WorkspaceRepository) : WorkspaceFileAccess {
    override suspend fun readText(path: String): String =
        repository.readText(id, path.toFilesAreaPath(), maxBytes = MAX_READ_FILE_BYTES)
    override suspend fun readBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = repository.resolveFile(id, WorkspaceStorageArea.FILES, path.toFilesAreaPath())
        require(file.isFile && file.length() <= MAX_READ_FILE_BYTES) { "Invalid or oversized image: $path" }
        file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_READ_FILE_BYTES) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= MAX_READ_FILE_BYTES) { "File is too large: $path" }
            bytes
        }
    }
    override suspend fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        repository.writeText(id, path.toFilesAreaPath(), text, overwrite).toRootfsView().also { repository.touch(id) }
}

private fun createListFilesTool(id: String, approval: (String) -> Boolean, repository: WorkspaceRepository) = Tool(
    name = "workspace_list_files",
    description = "List files under /workspace, skipping hidden files. Returns at most 500 entries.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            putPathProperty(required = false)
            put("recursive", buildJsonObject { put("type", "boolean"); put("description", "Defaults to true") })
        })
    },
    needsApproval = { approval("workspace_list_files") },
    execute = {
        val params = it.jsonObject
        val path = params.string("path") ?: "/workspace"
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entries = mutableListOf<WorkspaceFileEntry>()
        val visited = mutableSetOf<String>()
        suspend fun visit(relative: String) {
            val canonical = repository.resolveDirectory(id, WorkspaceStorageArea.FILES, relative).canonicalPath
            if (!visited.add(canonical)) return
            for (entry in repository.listFiles(id, WorkspaceStorageArea.FILES, relative, maxEntries = 501, includeHidden = false)) {
                if (entry.name.startsWith('.')) continue
                entries.add(entry.toRootfsView())
                if (entries.size > 500) return
                if (recursive && entry.isDirectory) visit(entry.path)
                if (entries.size > 500) return
            }
        }
        visit(path.toFilesAreaPath())
        listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path)
            put("entries", buildJsonArray { entries.take(500).forEach { add(it.toJson()) } })
            if (entries.size > 500) put("truncated", true)
        }.toString()))
    },
)

private fun createDeleteFileTool(id: String, approval: (String) -> Boolean, repository: WorkspaceRepository) = Tool(
    name = "workspace_delete_file",
    description = "Delete a file or directory under /workspace. Directory deletion requires recursive=true. Cannot delete the project root.",
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {
            putPathProperty(required = true)
            put("recursive", buildJsonObject { put("type", "boolean"); put("description", "Defaults to false") })
        }, required = listOf("path"))
    },
    needsApproval = { approval("workspace_delete_file") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val relative = path.toFilesAreaPath()
        require(relative.isNotEmpty()) { "Cannot delete /workspace" }
        val recursive = params["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        check(repository.deleteFile(id, WorkspaceStorageArea.FILES, relative, recursive)) { "File does not exist or could not be deleted: $path" }
        repository.touch(id)
        listOf(UIMessagePart.Text(buildJsonObject { put("path", path); put("deleted", true) }.toString()))
    },
)
