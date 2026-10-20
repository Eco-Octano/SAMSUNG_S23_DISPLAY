package com.ecooctano.telemetry.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {

    @Insert
    suspend fun insertSession(session: RaceSession): Long

    @Update
    suspend fun updateSession(session: RaceSession)

    @Insert
    suspend fun insertLaps(laps: List<LapData>)

    @Insert
    suspend fun insertPoints(points: List<TelemetryPoint>)

    @Query("SELECT * FROM race_sessions ORDER BY startedAtEpochMs DESC")
    fun observeSessions(): Flow<List<RaceSession>>

    @Query("SELECT * FROM race_sessions WHERE id = :sessionId")
    suspend fun getSession(sessionId: Long): RaceSession?

    @Query("SELECT * FROM laps WHERE sessionId = :sessionId ORDER BY lapNumber ASC")
    suspend fun getLaps(sessionId: Long): List<LapData>

    @Query("SELECT * FROM telemetry_points WHERE sessionId = :sessionId ORDER BY elapsedMs ASC, id ASC")
    suspend fun getPoints(sessionId: Long): List<TelemetryPoint>

    @Query("DELETE FROM telemetry_points WHERE sessionId = :sessionId")
    suspend fun deletePoints(sessionId: Long)

    @Query("DELETE FROM laps WHERE sessionId = :sessionId")
    suspend fun deleteLaps(sessionId: Long)

    @Query("DELETE FROM race_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Database(
    entities = [RaceSession::class, LapData::class, TelemetryPoint::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun telemetryDao(): TelemetryDao

    companion object {
        private const val DB_NAME = "ecooctano_telemetry.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
