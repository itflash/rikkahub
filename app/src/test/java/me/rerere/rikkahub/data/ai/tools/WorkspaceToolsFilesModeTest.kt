package me.rerere.rikkahub.data.ai.tools

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.rikkahub.data.ai.agent.AgentPolicy
import me.rerere.rikkahub.data.model.AgentMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceToolsFilesModeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `paths stay inside files area`() {
        assertEquals("", "/workspace".toFilesAreaPath())
        assertEquals("b.html", "/workspace/a/../b.html".toFilesAreaPath())
        listOf("/etc/passwd", "/workspace/../x", "/tmp/a", "relative").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { path.toFilesAreaPath() }
        }
    }

    @Test fun `files tools work without rootfs and enforce safeguards`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val manager = WorkspaceManager(temp.newFolder("workspaces"))
            val dao = FakeDao()
            val store = SettingsStore(object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(data.value).also { data.value = it }
            }, scope)
            val repository = WorkspaceRepository(dao, manager, RootfsInstaller(manager), store)
            val workspace = repository.create("test")
            val tools = createWorkspaceTools(workspace.id, repository, shellAvailable = false).associateBy { it.name }
            assertEquals(
                setOf("workspace_read_file", "workspace_list_files"),
                AgentPolicy(AgentMode.PLAN).tools(tools.values.toList()).map { it.name }.toSet(),
            )
            val executionTools = AgentPolicy(AgentMode.EXECUTE).tools(tools.values.toList())
            assertTrue(executionTools.single { it.name == "workspace_write_file" }.needsApproval(buildJsonObject { }))
            assertFalse(tools.containsKey("workspace_shell"))
            assertFalse(tools.getValue("workspace_list_files").needsApproval(buildJsonObject { }))
            assertTrue(tools.getValue("workspace_delete_file").needsApproval(buildJsonObject { }))
            assertTrue(createWorkspaceTools(workspace.id, repository).any { it.name == "workspace_shell" })
            suspend fun call(name: String, path: String, extra: JsonObjectBuilder.() -> Unit = {}) =
                tools.getValue("workspace_$name").execute(buildJsonObject { put("path", path); extra() })
            call("write_file", "/workspace/css/a.css") { put("text", "color: red;") }
            val read = (call("read_file", "/workspace/css/a.css").single() as UIMessagePart.Text).text
            assertTrue(read.contains("color: red;"))
            try {
                call("write_file", "/workspace/css/a.css") { put("text", "no"); put("overwrite", false) }
                fail("overwrite=false must fail")
            } catch (_: IllegalArgumentException) { }
            val edit = call("edit_file", "/workspace/css/a.css") { put("old_text", "red"); put("new_text", "blue") }.single()
            assertNotNull(edit.metadataAs<DiffMetadata>()?.diff)
            assertEquals("color: blue;", repository.readText(workspace.id, "css/a.css"))
            call("write_file", "/workspace/large.html") { put("text", "x".repeat(600 * 1024)) }
            val large = (call("read_file", "/workspace/large.html").single() as UIMessagePart.Text).text
            assertEquals(600 * 1024, Json.parseToJsonElement(large).jsonObject.getValue("text").jsonPrimitive.content.length)
            call("write_file", "/workspace/.hidden") { put("text", "secret") }
            val listing = (call("list_files", "/workspace").single() as UIMessagePart.Text).text
            assertTrue(listing.contains("/workspace/css/a.css"))
            assertFalse(listing.contains(".hidden"))
            repeat(505) { manager.writeText(workspace.root, "f$it.txt", "x") }
            val limited = Json.parseToJsonElement((call("list_files", "/workspace").single() as UIMessagePart.Text).text).jsonObject
            assertEquals(500, limited.getValue("entries").jsonArray.size)
            assertEquals(true, limited.getValue("truncated").jsonPrimitive.boolean)
            for (path in listOf("/workspace", "/workspace/css")) {
                try { call("delete_file", path); fail("unsafe deletion must fail") } catch (_: IllegalArgumentException) { }
            }
            call("delete_file", "/workspace/css") { put("recursive", true) }
            assertNotNull(dao.getById(workspace.id)?.lastAccessAt)
        } finally { scope.cancel() }
    }

    private class FakeDao : WorkspaceDAO {
        val rows = MutableStateFlow<List<WorkspaceEntity>>(emptyList())
        override fun listFlow() = rows
        override suspend fun getAll() = rows.value
        override suspend fun getById(id: String) = rows.value.find { it.id == id }
        override suspend fun upsert(workspace: WorkspaceEntity) { rows.value = rows.value.filterNot { it.id == workspace.id } + workspace }
        override suspend fun deleteById(id: String): Int { rows.value = rows.value.filterNot { it.id == id }; return 1 }
        override suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long) = 0
        override suspend fun setShellCompatibilityMode(id: String, enabled: Boolean, updatedAt: Long) = 0
    }
}
