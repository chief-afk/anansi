package com.chief.anansi.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.chief.anansi.data.entity.SourceEntity

@Dao
interface SourceDao {

    @Query("SELECT * FROM sources WHERE identity = :identity")
    suspend fun findByIdentity(identity: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE ecosystem = :ecosystem ORDER BY numericId")
    suspend fun findByEcosystem(ecosystem: String): List<SourceEntity>

    @Query("SELECT MAX(numericId) FROM sources WHERE numericId BETWEEN :min AND :max")
    suspend fun maxNumericIdInRange(min: Long, max: Long): Long?

    @Insert
    suspend fun insert(source: SourceEntity): Long
}
