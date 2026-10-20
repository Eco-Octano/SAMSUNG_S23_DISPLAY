package com.ecooctano.telemetry.data

import android.util.Log
import androidx.room.withTransaction
import com.ecooctano.telemetry.data.db.AppDatabase
import com.ecooctano.telemetry.data.export.TelemetryExporter
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class TelemetryRepository(
    private val db: AppDatabase,
    private val exporter: TelemetryExporter,
) {
    private val dao = db.telemetryDao()

    data class SaveResult(
        val session: RaceSession,
        val files: List<File>,
        val publicLocation: String?,
        val exportFailed: Boolean,
    )

    data class LoadedSession(
        val session: RaceSession,
        val laps: List<LapData>,
        val points: List<TelemetryPoint>,
    )

    fun observeSessions(): Flow<List<RaceSession>> = dao.observeSessions()

    /** Persiste tudo numa transação e depois exporta CSV/JSON. Falha de exportação não desfaz o DB. */
    suspend fun saveRace(
        session: RaceSession,
        laps: List<LapData>,
        points: List<TelemetryPoint>,
        rawImu: File? = null,
    ): SaveResult = withContext(Dispatchers.IO) {
        val sessionId = db.withTransaction {
            val newId = dao.insertSession(session.copy(id = 0))
            if (laps.isNotEmpty()) {
                dao.insertLaps(laps.map { it.copy(id = 0, sessionId = newId) })
            }
            points.chunked(1_000).forEach { chunk ->
                dao.insertPoints(chunk.map { it.copy(id = 0, sessionId = newId) })
            }
            newId
        }

        val saved = session.copy(id = sessionId)
        try {
            val export = exporter.export(saved, dao.getLaps(sessionId), dao.getPoints(sessionId), rawImu)
            val withLocation = saved.copy(exportLocation = export.publicLocation, imuFileName = export.imuFileName)
            dao.updateSession(withLocation)
            SaveResult(withLocation, export.localFiles, export.publicLocation, exportFailed = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TelemetryRepository", "Exportação falhou", e)
            SaveResult(saved, emptyList(), null, exportFailed = true)
        }
    }

    suspend fun loadSession(sessionId: Long): LoadedSession? = withContext(Dispatchers.IO) {
        val session = dao.getSession(sessionId) ?: return@withContext null
        LoadedSession(session, dao.getLaps(sessionId), dao.getPoints(sessionId))
    }

    /** Regera os arquivos de uma corrida já salva (para compartilhar novamente). */
    suspend fun exportSession(sessionId: Long): List<File> = withContext(Dispatchers.IO) {
        val loaded = loadSession(sessionId) ?: return@withContext emptyList()
        val imu = loaded.session.imuFileName?.let { File(exporter.exportsDir(), it) }
        exporter.export(loaded.session, loaded.laps, loaded.points, imu).localFiles
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.deletePoints(sessionId)
            dao.deleteLaps(sessionId)
            dao.deleteSession(sessionId)
        }
    }
}
