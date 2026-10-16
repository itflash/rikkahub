package me.rerere.rikkahub.data.export

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectZipper {
    fun zip(projectRoot: File, rootFolderName: String, output: File) {
        require(projectRoot.isDirectory) { "项目目录不存在" }
        require(rootFolderName.isNotBlank() && rootFolderName !in setOf(".", "..") && '/' !in rootFolderName && '\\' !in rootFolderName) { "根目录名称无效" }
        val root = projectRoot.canonicalFile
        output.parentFile?.mkdirs()
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                fun add(directory: File, prefix: String) {
                    check(!Thread.currentThread().isInterrupted) { "Export cancelled" }
                    zip.putNextEntry(ZipEntry("$prefix/")); zip.closeEntry()
                    directory.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                        if (file.name.startsWith('.') || Files.isSymbolicLink(file.toPath()) || file.canonicalFile == output.canonicalFile) return@forEach
                        require(file.canonicalFile.toPath().startsWith(root.toPath())) { "路径越界" }
                        val path = "$prefix/${file.name}"
                        if (file.isDirectory) add(file, path)
                        else if (file.isFile) {
                            zip.putNextEntry(ZipEntry(path))
                            file.inputStream().use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    check(!Thread.currentThread().isInterrupted) { "Export cancelled" }
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    zip.write(buffer, 0, n)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                add(root, rootFolderName)
            }
        } catch (e: Exception) { output.delete(); throw e }
    }
}
