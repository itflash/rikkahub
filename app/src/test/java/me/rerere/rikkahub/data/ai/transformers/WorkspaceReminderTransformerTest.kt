package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceReminderTransformerTest {

    @Test
    fun `files prompt describes a plain project without shell`() {
        val prompt = buildFilesWorkspacePrompt("project", "/workspace/css")
        org.junit.Assert.assertTrue(prompt.contains("NO shell and NO Linux environment"))
        org.junit.Assert.assertTrue(prompt.contains("workspace_list_files"))
        org.junit.Assert.assertTrue(prompt.contains("Current working directory: `/workspace/css`"))
        org.junit.Assert.assertFalse(buildFilesWorkspacePrompt("project", null).contains("Current working directory"))
        org.junit.Assert.assertTrue(HTML_PROJECT_GUIDELINES.contains("<html_project_guidelines>"))
    }

    @Test
    fun `HTML creation does not require a fixed entry filename`() {
        assertTrue(HTML_PROJECT_GUIDELINES.contains("There is no mandatory entry filename"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("Respect a filename specified by the user"))
        assertFalse(HTML_PROJECT_GUIDELINES.contains("/workspace/index.html"))
        assertFalse(buildFilesWorkspacePrompt("project", null).contains("/workspace/index.html"))
    }

    @Test
    fun `HTML changes inspect existing files and clarify ambiguous intent`() {
        assertTrue(HTML_PROJECT_GUIDELINES.contains("`workspace_list_files`"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("`workspace_read_file`"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("request and conversation context"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("ask the user before writing"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("user-specified filename already exists"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("prefer `workspace_edit_file`"))
    }

    @Test
    fun `creating a new page protects existing pages and shared assets`() {
        assertTrue(HTML_PROJECT_GUIDELINES.contains("choose a unique filename"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("overwrite=false"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("shared CSS/JS/images"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("A new conversation does not grant permission to replace existing files"))
        assertTrue(HTML_PROJECT_GUIDELINES.contains("name the HTML file to preview"))
    }

    @Test
    fun `os-release should prefer pretty name`() {
        val content = """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            HOME_URL="https://alpinelinux.org/"
        """.trimIndent()
        assertEquals("Alpine Linux v3.24", parseOsReleaseName(content))
    }

    @Test
    fun `os-release should fall back to name and version`() {
        val content = """
            # comment
            NAME='Ubuntu'
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04", parseOsReleaseName(content))
    }

    @Test
    fun `os-release without name should return null`() {
        assertNull(parseOsReleaseName("ID=unknown\nnot a key value line\n"))
    }

    @Test
    fun `os-release name should be sanitized and truncated`() {
        val name = parseOsReleaseName("PRETTY_NAME=\"Distro\u0007 ${"x".repeat(200)}\"")!!
        assertEquals(80, name.length)
        assertEquals("Distro x", name.take(8))
    }
}
