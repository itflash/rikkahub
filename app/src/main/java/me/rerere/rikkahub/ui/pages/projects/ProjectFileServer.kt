package me.rerere.rikkahub.ui.pages.projects

import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import me.rerere.rikkahub.utils.MimeTypes
import java.io.File
import java.nio.file.Files

const val PROJECT_HOST = "project.local"

/** Serves only canonical files inside this project's files area. */
class ProjectFileServer(val filesRoot: File) {
    fun urlOf(path: String): String = "https://$PROJECT_HOST/" +
        path.removePrefix("/").split('/').joinToString("/") { Uri.encode(it) }

    fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.host != PROJECT_HOST) return null
        return try {
            // Uri.path is already URL-decoded. Never decode twice.
            val relative = uri.path.orEmpty().removePrefix("/").let {
                if (it.isEmpty() || it.endsWith('/')) "${it}index.html" else it
            }
            val root = filesRoot.canonicalFile
            val file = resolveProjectFile(root, relative)
            if (!file.isFile) return errorResponse(404, "Not Found")
            val mime = MimeTypes.of(file.name).takeUnless { it == "application/octet-stream" }
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            val encoding = if (mime.startsWith("text/") || mime in setOf("application/json", "image/svg+xml")) "utf-8" else null
            WebResourceResponse(mime, encoding, 200, "OK", headers, file.inputStream())
        } catch (_: Exception) {
            errorResponse(403, "Forbidden")
        }
    }

    private fun errorResponse(status: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", status, reason, headers, reason.byteInputStream())

    private val headers = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
}

internal fun projectHtmlFiles(root: File): List<String> {
    val canonicalRoot = root.canonicalFile.toPath()
    return root.walkTopDown().onEnter { directory ->
        !directory.name.startsWith('.') && !Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.toPath().startsWith(canonicalRoot)
    }.filter {
        it.isFile && !it.name.startsWith('.') && !Files.isSymbolicLink(it.toPath()) &&
            it.extension.lowercase() in setOf("html", "htm") && it.canonicalFile.toPath().startsWith(canonicalRoot)
    }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
}

internal fun resolveProjectFile(root: File, path: String): File {
    val canonicalRoot = root.canonicalFile
    val file = File(canonicalRoot, path).canonicalFile
    require(file.toPath().startsWith(canonicalRoot.toPath())) { "Path escapes project root" }
    return file
}
