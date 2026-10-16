package me.rerere.rikkahub.data.export

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class ProjectZipperTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun `zip preserves root contents and empty directories but not hidden or symlink files`() {
        val root = temp.newFolder("project")
        File(root, "index.html").writeText("<html>你好</html>")
        File(root, "empty").mkdirs()
        File(root, ".git").mkdirs(); File(root, ".git/config").writeText("private")
        File(root, ".DS_Store").writeText("hidden")
        Files.createSymbolicLink(File(root, "outside").toPath(), temp.newFile("secret").toPath())
        val output = File(temp.root, "project.zip")
        ProjectZipper.zip(root, "测试", output)
        ZipFile(output).use { zip ->
            assertEquals(setOf("测试/", "测试/empty/", "测试/index.html"), zip.entries().asSequence().map { it.name }.toSet())
            assertEquals("<html>你好</html>", zip.getInputStream(zip.getEntry("测试/index.html")).reader().readText())
        }
    }
    @Test fun `unsafe root folder names are rejected`() {
        val root = temp.newFolder("project")
        assertThrows(IllegalArgumentException::class.java) { ProjectZipper.zip(root, "../escape", File(temp.root, "bad.zip")) }
    }
}
