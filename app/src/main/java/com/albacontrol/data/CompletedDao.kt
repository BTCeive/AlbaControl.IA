package com.albacontrol.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CompletedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completed: CompletedAlbaran): Long

    @Query("SELECT * FROM completed_albaranes ORDER BY createdAt DESC")
    suspend fun getAll(): List<CompletedAlbaran>

    @Query("DELETE FROM completed_albaranes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM completed_albaranes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CompletedAlbaran?
}
