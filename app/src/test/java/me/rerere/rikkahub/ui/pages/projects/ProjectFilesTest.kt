package me.rerere.rikkahub.ui.pages.projects

import me.rerere.rikkahub.utils.MimeTypes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectFilesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `html discovery skips hidden files and symlinks and sorts paths`() {
        val root = temp.newFolder("project")
        File(root, "b.html").writeText("b")
        File(root, "a").mkdirs()
        File(root, "a/index.htm").writeText("a")
        File(root, ".hidden.html").writeText("hidden")
        val secret = temp.newFile("secret.html")
        Files.createSymbolicLink(File(root, "secret.html").toPath(), secret.toPath())
        Files.createSymbolicLink(File(root, "loop").toPath(), root.toPath())
        assertEquals(listOf("a/index.htm", "b.html"), projectHtmlFiles(root))
    }
    @Test fun `canonical resolution rejects traversal symlinks and sibling prefix tricks`() {
        val root = temp.newFolder("root")
        val sibling = temp.newFolder("root-other")
        val secret = File(sibling, "secret.html").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "alias").toPath(), secret.toPath())
        assertThrows(IllegalArgumentException::class.java) { resolveProjectFile(root, "../root-other/secret.html") }
        assertThrows(IllegalArgumentException::class.java) { resolveProjectFile(root, "alias") }
        assertEquals(File(root, "index.html").canonicalFile, resolveProjectFile(root, "css/../index.html"))
    }

    @Test fun `web mime types are independent of Android`() {
        assertEquals("text/javascript", MimeTypes.of("app.mjs?v=1"))
        assertEquals("font/woff2", MimeTypes.of("font.woff2"))
        assertEquals("image/svg+xml", MimeTypes.of("a.svg#icon"))
        assertEquals("application/octet-stream", MimeTypes.of("unknown.bin"))
    }
}
