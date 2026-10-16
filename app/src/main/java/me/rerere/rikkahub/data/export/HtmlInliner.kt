package me.rerere.rikkahub.data.export

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.rerere.rikkahub.utils.MimeTypes
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

fun interface ResourceDownloader {
    suspend fun download(url: String): Downloaded?
}
data class Downloaded(val bytes: ByteArray, val contentType: String?)
class ResourceTooLargeException : Exception("Resource exceeds inline size limit")

/** JVM-only export. All caches and budgets belong to one invocation, not the exporter instance. */
class HtmlInliner(
    private val downloader: ResourceDownloader,
    private val maxResourceBytes: Long = 5L * 1024 * 1024,
    private val maxTotalBytes: Long = 30L * 1024 * 1024,
) {
    data class Result(val html: String, val inlinedCount: Int, val failedUrls: List<String>, val skippedForSize: List<String>)
    suspend fun inline(projectRoot: File, entryPath: String): Result = Session(projectRoot.canonicalFile).inline(entryPath)

    private inner class Session(val root: File) {
        val cache = mutableMapOf<String, Downloaded?>()
        val failures = linkedSetOf<String>()
        val oversized = linkedSetOf<String>()
        var total = 0L
        var count = 0

        suspend fun inline(entryPath: String): Result {
            val entry = File(root, entryPath).canonicalFile
            require(entry.toPath().startsWith(root.toPath()) && entry.isFile) { "入口文件不存在或不在项目内" }
            require(entry.length() <= maxTotalBytes) { "入口文件过大" }
            val base = entry.toURI().toString()
            val doc = Jsoup.parse(entry.readText(), base)
            doc.outputSettings().prettyPrint(false)
            // Styles converted from <link> were already rewritten against the stylesheet's own URL.
            val convertedStyles = Collections.newSetFromMap(IdentityHashMap<Element, Boolean>())
            for (link in doc.select("link[href]").toList()) {
                currentCoroutineContext().ensureActive()
                val href = link.attr("href")
                val rels = link.attr("rel").lowercase().split(Regex("\\s+")).toSet()
                when {
                    "stylesheet" in rels -> {
                        val location = resolve(base, href)
                        val resource = location?.let { read(it) }
                        if (resource != null) {
                            val css = css(resource.bytes.toString(Charsets.UTF_8), location, linkedSetOf(location))
                            val style = Element("style").appendChild(DataNode(escapeClosing(css, "style")))
                            if (link.hasAttr("media")) style.attr("media", link.attr("media"))
                            link.replaceWith(style)
                            convertedStyles.add(style)
                        }
                    }
                    rels.any { it in setOf("icon", "apple-touch-icon") } -> link.attr("href", dataUri(base, href))
                    rels.any { it in setOf("preconnect", "dns-prefetch") } -> link.remove()
                    rels.any { it in setOf("preload", "modulepreload") } && !isRemote(resolve(base, href).orEmpty()) -> link.remove()
                }
            }
            val deferred = mutableListOf<Element>()
            for (script in doc.select("script[src]")) {
                val location = resolve(base, script.attr("src"))
                val resource = location?.let { read(it) } ?: continue
                // Inline scripts ignore defer/async. Module scripts keep type=module (inline modules are still
                // deferred); classic deferred scripts move to the end of <body> to keep running after parsing.
                val module = script.attr("type").trim().equals("module", ignoreCase = true)
                if (!module && script.hasAttr("defer") && !script.hasAttr("async")) deferred.add(script)
                script.removeAttr("src").removeAttr("defer").removeAttr("async")
                script.removeAttr("integrity").removeAttr("crossorigin")
                script.empty().appendChild(DataNode(escapeClosing(resource.bytes.toString(Charsets.UTF_8), "script")))
            }
            deferred.forEach { doc.body().appendChild(it) }
            val attributes = mapOf(
                "img" to listOf("src", "srcset"), "source" to listOf("src", "srcset"),
                "video" to listOf("src", "poster"), "audio" to listOf("src"), "input[type=image]" to listOf("src"),
            )
            for ((selector, attrs) in attributes) for (element in doc.select(selector)) for (attr in attrs) {
                if (!element.hasAttr(attr)) continue
                element.attr(attr, if (attr == "srcset") srcset(base, element.attr(attr)) else dataUri(base, element.attr(attr)))
            }
            for (style in doc.select("style")) if (style !in convertedStyles) style.html(escapeClosing(css(style.data(), base, linkedSetOf()), "style"))
            for (element in doc.select("[style]")) element.attr("style", css(element.attr("style"), base, linkedSetOf()))
            // An author-supplied base URL would break otherwise untouched relative links in the portable file.
            doc.select("base[href]").remove()
            return Result(doc.outerHtml(), count, failures.toList(), oversized.toList())
        }

        fun resolve(base: String, raw: String): String? {
            val ref = raw.trim()
            if (ref.isEmpty() || ref.startsWith('#') || Regex("^(data|blob|javascript|mailto):", RegexOption.IGNORE_CASE).containsMatchIn(ref)) return null
            return try {
                val uri = when {
                    ref.startsWith("//") -> URI("https:$ref")
                    ref.startsWith('/') && !isRemote(base) -> File(root, ref.removePrefix("/").substringBefore('?').substringBefore('#')).toURI()
                    else -> URI(base).resolve(ref.replace(" ", "%20"))
                }
                if (uri.scheme !in setOf("file", "http", "https")) return null
                val clean = URI(uri.scheme, uri.authority, uri.path, uri.query.takeIf { isRemote(uri.toString()) }, null)
                if (clean.scheme == "file") {
                    val file = File(URI(clean.scheme, clean.authority, clean.path, null, null)).canonicalFile
                    if (!file.toPath().startsWith(root.toPath())) { failures.add(ref); return null }
                    file.toURI().toString()
                } else clean.toString()
            } catch (_: Exception) { failures.add(ref); null }
        }

        suspend fun read(location: String): Downloaded? {
            currentCoroutineContext().ensureActive()
            if (cache.containsKey(location)) return cache[location]
            val resource = try {
                if (isRemote(location)) downloader.download(location) else {
                    val file = File(URI(location))
                    if (!file.isFile) null else {
                        if (file.length() > maxResourceBytes || file.length() > maxTotalBytes - total) throw ResourceTooLargeException()
                        Downloaded(file.readBytes(), MimeTypes.of(file.name))
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: ResourceTooLargeException) { oversized.add(location); null
            } catch (_: Exception) { null }
            val accepted = if (resource != null && (resource.bytes.size > maxResourceBytes || resource.bytes.size > maxTotalBytes - total)) {
                oversized.add(location); null
            } else resource
            if (accepted != null) { total += accepted.bytes.size; count++ }
            else if (location !in oversized) failures.add(location)
            cache[location] = accepted
            return accepted
        }

        suspend fun dataUri(base: String, raw: String): String {
            val location = resolve(base, raw) ?: return raw
            val resource = read(location) ?: return if (isRemote(base)) location else raw
            val mime = resource.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: MimeTypes.of(location)
            return "data:$mime;base64,${Base64.getEncoder().encodeToString(resource.bytes)}"
        }

        suspend fun srcset(base: String, value: String): String {
            val result = mutableListOf<String>()
            var i = 0
            while (i < value.length) {
                while (i < value.length && (value[i].isWhitespace() || value[i] == ',')) i++
                if (i == value.length) break
                val start = i
                val data = value.regionMatches(i, "data:", 0, 5, true)
                while (i < value.length && !value[i].isWhitespace() && (data || value[i] != ',')) i++
                val url = value.substring(start, i).trimEnd(',')
                val descriptorStart = i
                while (i < value.length && value[i] != ',') i++
                val descriptor = value.substring(descriptorStart, i).trim()
                result.add(dataUri(base, url) + if (descriptor.isNotEmpty()) " $descriptor" else "")
            }
            return result.joinToString(", ")
        }

        suspend fun css(text: String, base: String, stack: MutableSet<String>): String {
            val imports = replaceSuspending(IMPORT, text) { match ->
                val ref = match.groupValues[1].ifBlank { match.groupValues[2] }.ifBlank { match.groupValues[3] }
                val location = resolve(base, ref)
                if (location == null) match.value
                else if (location in stack) ""
                else {
                    val resource = read(location)
                    if (resource == null) "@import url(\"${if (isRemote(base)) location else ref}\") ${match.groupValues[4]};"
                    else {
                        stack.add(location)
                        val imported = try { css(resource.bytes.toString(Charsets.UTF_8), location, stack) } finally { stack.remove(location) }
                        val media = match.groupValues[4].trim()
                        if (media.isEmpty()) imported else "@media $media {\n$imported\n}"
                    }
                }
            }
            return replaceSuspending(URL, imports) { match ->
                val raw = match.groupValues[1].trim().removeSurrounding("\"").removeSurrounding("'")
                "url(\"${dataUri(base, raw)}\")"
            }
        }
    }

    private companion object {
        val IMPORT = Regex("""@import\s+(?:url\(\s*["']?([^\s)"']+)["']?\s*\)|"([^"]+)"|'([^']+)')\s*([^;]*);""", RegexOption.IGNORE_CASE)
        val URL = Regex("""url\(\s*((?:"[^"]*"|'[^']*'|[^)]*))\s*\)""", RegexOption.IGNORE_CASE)
        fun isRemote(value: String) = value.startsWith("https://") || value.startsWith("http://")
        fun escapeClosing(value: String, tag: String) = Regex("</$tag", RegexOption.IGNORE_CASE).replace(value) { "<\\/${it.value.drop(2)}" }
        suspend fun replaceSuspending(regex: Regex, value: String, transform: suspend (MatchResult) -> String): String {
            val output = StringBuilder()
            var end = 0
            for (match in regex.findAll(value)) {
                currentCoroutineContext().ensureActive()
                output.append(value, end, match.range.first).append(transform(match))
                end = match.range.last + 1
            }
            return output.append(value, end, value.length).toString()
        }
    }
}
