package com.ecooctano.telemetry.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cabeçalho de uma corrida salva.
 */
@Entity(tableName = "race_sessions")
data class RaceSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val startedAtEpochMs: Long,
    val durationMs: Long,
    val distanceMeters: Double,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val lapCount: Int,
    val bestLapMs: Long?,
    val pointCount: Int,
    /** Onde os arquivos CSV/JSON públicos foram gravados (ex.: "Documents/EcoOctano_Telemetry"). */
    val exportLocation: String? = null,
)

/**
 * Uma volta fechada (botão NOVA VOLTA) ou a volta parcial final ao salvar.
 * Offsets são relativos ao tempo de corrida (pausas não contam).
 */
@Entity(
    tableName = "laps",
    foreignKeys = [
        ForeignKey(
            entity = RaceSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class LapData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val lapNumber: Int,
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val durationMs: Long,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
)

/**
 * Amostra de telemetria gravada a 5 Hz. Campos nulos = sensor indisponível ("N/A").
 * Nunca usamos NaN porque o SQLite converte NaN em NULL e quebraria leituras não-nulas.
 */
@Entity(
    tableName = "telemetry_points",
    foreignKeys = [
        ForeignKey(
            entity = RaceSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class TelemetryPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long = 0,
    val timestampEpochMs: Long,
    /** Tempo de corrida (ms) no momento da amostra, descontando pausas. */
    val elapsedMs: Long,
    val lapNumber: Int,
    val latitude: Double?,
    val longitude: Double?,
    val gpsAltitudeM: Double?,
    val gpsAccuracyM: Float?,
    val speedKmh: Float?,
    val accelX: Float?,
    val accelY: Float?,
    val accelZ: Float?,
    val gForce: Float?,
    val pressureHpa: Float?,
    val baroAltitudeM: Float?,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val temperatureC: Float?,
)

/** Ponto do traçado no mapa (somente posições válidas de GPS). */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
)

/**
 * Visão somente-leitura de um traçado que cresce apenas por acréscimo.
 * [points] pode ser a lista viva do ViewModel (mutada só na Main thread);
 * [size] é a quantidade válida no momento da emissão. Uma nova instância de lista
 * indica que o traçado foi reiniciado.
 */
class TrackSnapshot(val points: List<TrackPoint>, val size: Int) {
    companion object {
        val EMPTY = TrackSnapshot(emptyList(), 0)
    }
}
