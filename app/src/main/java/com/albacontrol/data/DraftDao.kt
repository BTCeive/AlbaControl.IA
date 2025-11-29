package com.albacontrol.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(draft: Draft): Long

    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    suspend fun getAll(): List<Draft>

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM drafts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Draft?
}
