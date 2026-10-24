package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration_30_31_Test {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun checkpointTableIsAddedWithoutChangingExistingConversations() {
        val name = "agent-checkpoint-migration"
        helper.createDatabase(name, 30).apply {
            execSQL("INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned) VALUES ('existing', 'assistant', 'kept', '[]', 0, 0, '[]', 0)")
            close()
        }
        helper.runMigrationsAndValidate(name, 31, true).use { db ->
            db.query("SELECT title FROM ConversationEntity WHERE id = 'existing'").use {
                assertTrue(it.moveToFirst()); assertEquals("kept", it.getString(0))
            }
            db.query("SELECT COUNT(*) FROM agent_checkpoints").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.execSQL("INSERT INTO agent_checkpoints (conversation_id, checkpoint) VALUES ('existing', '{}')")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM ConversationEntity WHERE id = 'existing'")
            db.query("SELECT COUNT(*) FROM agent_checkpoints").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }
}
