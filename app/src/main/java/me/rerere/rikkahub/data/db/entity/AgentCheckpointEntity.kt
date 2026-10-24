package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

// Independent from the conversation row: ordinary title/config saves must not roll back the journal.
@Entity(
    tableName = "agent_checkpoints",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE,
    )],
)
data class AgentCheckpointEntity(
    @PrimaryKey @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("checkpoint") val checkpoint: String,
)
