package com.ecooctano.telemetry.data

import android.util.Log
import androidx.room.withTransaction
import com.ecooctano.telemetry.data.db.AppDatabase
import com.ecooctano.telemetry.data.export.TelemetryExporter
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import com.ecooctano.telemetry.data.transfer.ParsedSession
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

    data class ImportSummary(val imported: Int, val duplicates: Int)

    /** Grava corridas lidas de um pacote; corridas já existentes (mesmo início e duração) são ignoradas. */
    suspend fun importSessions(parsed: List<ParsedSession>): ImportSummary = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        for (p in parsed) {
            if (dao.findSame(p.session.startedAtEpochMs, p.session.durationMs) != null) {
                duplicates++
                p.imuTemp?.delete()
                continue
            }
            val newId = db.withTransaction {
                val id = dao.insertSession(p.session.copy(id = 0, exportLocation = null, imuFileName = null))
                if (p.laps.isNotEmpty()) dao.insertLaps(p.laps.map { it.copy(id = 0, sessionId = id) })
                p.points.chunked(1_000).forEach { chunk ->
                    dao.insertPoints(chunk.map { it.copy(id = 0, sessionId = id) })
                }
                id
            }
            val imuTemp = p.imuTemp
            if (imuTemp != null && imuTemp.exists()) {
                val saved = p.session.copy(id = newId)
                val target = File(exporter.exportsDir().apply { mkdirs() }, "${exporter.baseNameFor(saved)}_imu.csv")
                val moved = try {
                    if (!imuTemp.renameTo(target)) {
                        imuTemp.copyTo(target, overwrite = true)
                        imuTemp.delete()
                    }
                    true
                } catch (e: Exception) {
                    Log.e("TelemetryRepository", "Falha ao guardar IMU importado", e)
                    imuTemp.delete()
                    false
                }
                if (moved) dao.getSession(newId)?.let { dao.updateSession(it.copy(imuFileName = target.name)) }
            }
            imported++
        }
        ImportSummary(imported, duplicates)
    }

    /**
     * Gera um pacote .ecooctano.zip com as corridas pedidas (todas, se [sessionIds] for null).
     * Pacotes antigos de várias corridas são apagados para não acumular espaço.
     */
    suspend fun exportPackage(sessionIds: List<Long>?): File? = withContext(Dispatchers.IO) {
        val ids = sessionIds ?: dao.allSessionIds()
        if (ids.isEmpty()) return@withContext null
        val entries = ids.mapNotNull { id ->
            val loaded = loadSession(id) ?: return@mapNotNull null
            val imu = loaded.session.imuFileName?.let { File(exporter.exportsDir(), it) }
            TelemetryExporter.PackageEntry(loaded.session, loaded.laps, loaded.points, imu)
        }
        if (entries.isEmpty()) return@withContext null
        val dir = exporter.exportsDir().apply { mkdirs() }
        val target = if (entries.size == 1) {
            File(dir, exporter.baseNameFor(entries[0].session) + TelemetryExporter.PACKAGE_EXTENSION)
        } else {
            dir.listFiles { f -> f.name.startsWith("EcoOctano_pacote_") }?.forEach { it.delete() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            File(dir, "EcoOctano_pacote_${entries.size}corridas_$stamp${TelemetryExporter.PACKAGE_EXTENSION}")
        }
        exporter.writePackage(target, entries)
        target
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.deletePoints(sessionId)
            dao.deleteLaps(sessionId)
            dao.deleteSession(sessionId)
        }
    }
}
