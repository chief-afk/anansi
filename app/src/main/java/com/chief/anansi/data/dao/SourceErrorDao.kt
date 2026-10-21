package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.chief.anansi.data.entity.SourceErrorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceErrorDao {

    @Upsert
    suspend fun upsert(error: SourceErrorEntity)

    @Query("DELETE FROM source_errors WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)

    @Query("DELETE FROM source_errors WHERE sourceId IN (:sourceIds)")
    suspend fun deleteAll(sourceIds: Collection<String>)

    @Query("SELECT * FROM source_errors")
    suspend fun all(): List<SourceErrorEntity>

    @Query("SELECT * FROM source_errors")
    fun observeAll(): Flow<List<SourceErrorEntity>>

    @Query("SELECT * FROM source_errors WHERE sourceId = :sourceId")
    fun observe(sourceId: String): Flow<SourceErrorEntity?>
}
