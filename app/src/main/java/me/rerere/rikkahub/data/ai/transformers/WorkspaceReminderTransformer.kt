package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Workspace 系统提示注入转换器
 *
 * 为绑定的工作区注入文件工具与 HTML 创作约定；无需安装 Linux 环境。
 * Rootfs 就绪时保留 shell、发行版与分层 AGENTS.md 指引，否则只读取 FILES 区的 AGENTS.md。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        val prompt = if (workspace.shellStatus == WorkspaceShellStatus.READY.name) {
            buildWorkspacePrompt(
                workspace, workspaceRepository.rootfsShell(workspaceId), readDistroName(workspaceId), ctx.workspaceCwd,
            ) + "\n\n" + HTML_PROJECT_GUIDELINES + buildAgentsPrompt(workspaceId, ctx.workspaceCwd)
        } else {
            buildFilesWorkspacePrompt(workspace.name, ctx.workspaceCwd) + "\n\n" + HTML_PROJECT_GUIDELINES +
                buildFilesAgentsPrompt(workspaceId)
        }

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun buildFilesAgentsPrompt(id: String): String = try {
        require(workspaceRepository.fileSize(id, me.rerere.workspace.WorkspaceStorageArea.FILES, "AGENTS.md") <= MAX_AGENTS_BYTES)
        val content = workspaceRepository.readText(id, "AGENTS.md")
        if (content.isBlank()) "" else "\n\n<workspace_instructions>\nFollow the AGENTS.md instructions below.\nAGENTS.md source: /workspace/AGENTS.md\n$content\n</workspace_instructions>"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d("WorkspaceReminder", "Skipping workspace AGENTS.md", e)
        ""
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            readRootfsText(workspaceId, path, MAX_AGENTS_BYTES)?.let { path to it }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private suspend fun readDistroName(workspaceId: String): String? =
        OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
            readRootfsText(workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
        }

    // 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分
    private suspend fun readRootfsText(workspaceId: String, path: String, maxBytes: Long): String? =
        try {
            val size = workspaceRepository.rootfsFileSize(workspaceId, path)
            require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportRootfsFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
            null
        }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val MAX_OS_RELEASE_BYTES = 16L * 1024
        val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")
    }
}

private const val MAX_DISTRO_NAME_LENGTH = 80

/** 从 os-release 内容中取发行版名称, 优先 PRETTY_NAME, 其次 NAME + VERSION_ID */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件, 拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    shell: String,
    distro: String?,
    cwd: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    if (distro != null) {
        appendLine("- The Rootfs distribution is $distro. Use its native package manager when you need to install missing tools.")
    }
    if (shell == "/bin/bash") {
        appendLine("- `workspace_shell` runs commands with `/bin/bash`.")
    } else {
        appendLine("- `workspace_shell` runs commands with `$shell`, a POSIX shell. Bash is not installed, so avoid bash-only syntax such as arrays, here-strings (`<<<`) and brace expansion.")
    }
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Built-in skills shipped with the app are mounted at `/builtin_skills/<skill-name>/` with the same layout. Treat `/builtin_skills` as READ-ONLY: you may read files and run scripts there, but never modify, overwrite, or delete anything. A skill in `/skills` with the same name overrides the built-in one.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}


internal fun buildFilesWorkspacePrompt(name: String, cwd: String?): String = buildString {
    appendLine("<workspace>")
    appendLine("You are working in a project folder named \"$name\". It is a plain file area, there is NO shell and NO Linux environment.")
    appendLine("- The project root is `/workspace`. All tool paths must be absolute and inside `/workspace` (for example `/workspace/index.html`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_list_files`: list files in the project.")
    appendLine("  - `workspace_read_file`: read a file.")
    appendLine("  - `workspace_write_file`: create or overwrite a file.")
    appendLine("  - `workspace_edit_file`: make precise edits to an existing file. Prefer it over rewriting whole files.")
    appendLine("  - `workspace_delete_file`: delete a file or folder.")
    if (!cwd.isNullOrBlank()) appendLine("- Current working directory: `$cwd`.")
    append("</workspace>")
}

internal val HTML_PROJECT_GUIDELINES = """
<html_project_guidelines>
This project is a web page that the user previews on a phone and shares to chat apps such as WeChat.
- The entry page is `/workspace/index.html`. Put CSS, JS, images and other assets in the project and reference them with relative paths (e.g. `css/style.css`).
- Do NOT load anything from external CDNs or web font services (Google Fonts, jsDelivr, unpkg, cdnjs, Tailwind CDN, etc.). They are often unreachable for the user. If a library is needed, write it into the project as a local file; prefer plain CSS/JS. Use inline SVG for icons. Use system font stacks.
- Design mobile-first for a 390 CSS px wide viewport. Always include `<meta name="viewport" content="width=device-width, initial-scale=1">`.
- For normal content pages, let the page scroll naturally; do not lock the whole page to `100vh` with `overflow: hidden`.
- For slide decks, make each slide a direct child `<section>` of `<body>` (or `<main>`) with height `100vh`, so each slide can be exported as one image.
- After creating or changing files, briefly tell the user what changed. The user previews the result in the app.
</html_project_guidelines>
""".trimIndent()
