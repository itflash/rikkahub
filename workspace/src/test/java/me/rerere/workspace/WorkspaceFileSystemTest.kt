package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceFileSystemTest {
    @get:Rule val temp = TemporaryFolder()
    private val fs = WorkspaceFileSystem()
    @Test fun `mkdirs and text writes are sandboxed and refuse overwrite`() {
        val root = temp.newFolder("root")
        assertTrue(fs.mkdirs(root, "css/nested").isDirectory)
        assertTrue(fs.mkdirs(root, "css/nested").isDirectory)
        fs.writeText(root, "css/nested/a.css", "hello", false)
        assertEquals("hello", fs.readText(root, "css/nested/a.css"))
        assertThrows(IllegalArgumentException::class.java) { fs.writeText(root, "css/nested/a.css", "other", false) }
        assertThrows(IllegalArgumentException::class.java) { fs.mkdirs(root, "../escaped") }
        assertThrows(IllegalArgumentException::class.java) { fs.mkdirs(root, "css/nested/a.css") }
    }
    @Test fun `tool read limits can be larger without changing editor defaults`() {
        val root = temp.newFolder("root")
        fs.writeText(root, "large.html", "x".repeat(600 * 1024))
        assertThrows(IllegalArgumentException::class.java) { fs.readText(root, "large.html") }
        assertEquals(600 * 1024, fs.readText(root, "large.html", maxBytes = 8L * 1024 * 1024).length)
    }
    @Test fun `hidden files do not consume visible list limit`() {
        val root = temp.newFolder("root")
        repeat(502) { File(root, ".hidden$it").writeText("") }
        repeat(503) { File(root, "visible$it").writeText("") }
        val files = fs.list(root, "", maxEntries = 501, includeHidden = false)
        assertEquals(501, files.size)
        assertTrue(files.none { it.name.startsWith('.') })
    }
    @Test fun `recursive deletion cannot delete root through normalized or symbolic aliases`() {
        val root = temp.newFolder("root")
        fs.mkdirs(root, "child")
        assertThrows(IllegalArgumentException::class.java) { fs.delete(root, "child", false) }
        assertThrows(IllegalArgumentException::class.java) { fs.delete(root, "child/..", true) }
        Files.createSymbolicLink(File(root, "alias").toPath(), root.toPath())
        assertThrows(IllegalArgumentException::class.java) { fs.delete(root, "alias", true) }
        assertTrue(fs.delete(root, "child", true))
        assertTrue(root.exists())
    }
}
