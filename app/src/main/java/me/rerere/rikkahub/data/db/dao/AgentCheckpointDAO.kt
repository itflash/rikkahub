package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import me.rerere.rikkahub.data.db.entity.AgentCheckpointEntity

@Dao
interface AgentCheckpointDAO {
    @Query("SELECT checkpoint FROM agent_checkpoints WHERE conversation_id = :id")
    suspend fun get(id: String): String?

    @Upsert
    suspend fun upsert(entity: AgentCheckpointEntity)

    @Query("DELETE FROM agent_checkpoints WHERE conversation_id = :id")
    suspend fun delete(id: String)
}
