package com.onion.macrolearn.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Query("SELECT * FROM macros ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Macro>>

    @Query("SELECT * FROM macros WHERE id = :id")
    suspend fun getById(id: Long): Macro?

    @Insert
    suspend fun insert(macro: Macro): Long

    @Query("UPDATE macros SET scheduled = :scheduled WHERE id = :id")
    suspend fun setScheduled(id: Long, scheduled: Boolean)

    @Delete
    suspend fun delete(macro: Macro)
}
