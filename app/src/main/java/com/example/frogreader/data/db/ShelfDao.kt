package com.example.frogreader.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update

@Dao
interface ShelfDao {

    @Query("SELECT * FROM shelves")
    suspend fun getAllShelves(): List<ShelfEntity>

    @Query("SELECT * FROM shelves WHERE id = :id LIMIT 1")
    suspend fun getShelfById(id: String): ShelfEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShelfIgnore(entity: ShelfEntity): Long

    @Update
    suspend fun updateShelfRaw(entity: ShelfEntity)

    @Query("DELETE FROM shelves WHERE id = :id")
    suspend fun deleteShelfById(id: String): Int

    @Query("DELETE FROM shelves")
    suspend fun clearAllShelves()

    suspend fun saveShelf(entity: ShelfEntity) {
        val inserted = insertShelfIgnore(entity)
        if (inserted == -1L) {
            updateShelfRaw(entity)
        }
    }
}
