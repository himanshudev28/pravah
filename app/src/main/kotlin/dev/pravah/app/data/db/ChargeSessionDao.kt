package dev.pravah.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargeSessionDao {

    @Insert
    suspend fun insert(session: ChargeSession): Long

    @Query("SELECT * FROM charge_sessions ORDER BY startMillis DESC")
    fun observeAll(): Flow<List<ChargeSession>>

    @Query("SELECT * FROM charge_sessions ORDER BY startMillis DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ChargeSession>

    @Query("DELETE FROM charge_sessions")
    suspend fun deleteAll()
}
