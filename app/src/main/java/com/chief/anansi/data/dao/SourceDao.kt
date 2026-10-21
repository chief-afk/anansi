package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.chief.anansi.data.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources WHERE identity = :identity")
    suspend fun findByIdentity(identity: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE identity = :identity")
    fun observeByIdentity(identity: String): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE ecosystem = :ecosystem ORDER BY numericId")
    suspend fun findByEcosystem(ecosystem: String): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE repoUrl = :repoUrl ORDER BY numericId")
    suspend fun findByRepoUrl(repoUrl: String): List<SourceEntity>

    /** The Browse list order: pinned first, then registration order. */
    @Query("SELECT * FROM sources ORDER BY pinned DESC, numericId")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT MAX(numericId) FROM sources WHERE numericId BETWEEN :min AND :max")
    suspend fun maxNumericIdInRange(min: Long, max: Long): Long?

    @Insert
    suspend fun insert(source: SourceEntity): Long

    @Update
    suspend fun update(source: SourceEntity)

    @Delete
    suspend fun delete(source: SourceEntity)

    @Query("UPDATE sources SET pinned = :pinned WHERE identity = :identity")
    suspend fun setPinned(identity: String, pinned: Boolean)

    @Query("UPDATE sources SET enabled = :enabled WHERE identity = :identity")
    suspend fun setEnabled(identity: String, enabled: Boolean)
}
