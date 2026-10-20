package com.ecooctano.telemetry.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.JsonWriter
import android.util.Log
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Gera os arquivos brutos (.csv e .json) de uma corrida.
 *
 * 1. Grava em `filesDir/exports` (compartilhável via FileProvider).
 * 2. Publica uma cópia em `Documents/EcoOctano_Telemetry` via MediaStore (sem permissão).
 *    Se o MediaStore falhar, usa `Android/data/<pkg>/files/Documents/EcoOctano_Telemetry`.
 */
class TelemetryExporter(context: Context) {

    companion object {
        const val PUBLIC_FOLDER = "EcoOctano_Telemetry"
        private const val TAG = "TelemetryExporter"
        private const val MIME_CSV = "text/csv"
        private const val MIME_JSON = "application/json"

        private val CSV_HEADER = listOf(
            "timestamp_epoch_ms", "timestamp_iso", "elapsed_ms", "lap",
            "latitude", "longitude", "gps_altitude_m", "gps_accuracy_m", "speed_kmh",
            "accel_x_ms2", "accel_y_ms2", "accel_z_ms2", "g_force",
            "pressure_hpa", "baro_altitude_m", "pitch_deg", "roll_deg", "temperature_c",
        ).joinToString(",")
    }

    data class ExportResult(
        val localFiles: List<File>,
        val publicLocation: String?,
    )

    private val appContext = context.applicationContext

    suspend fun export(
        session: RaceSession,
        laps: List<LapData>,
        points: List<TelemetryPoint>,
    ): ExportResult = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, "exports")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Não foi possível criar ${dir.absolutePath}")

        val baseName = "EcoOctano_${fileStamp(session.startedAtEpochMs)}_S${session.id}"
        val csv = File(dir, "$baseName.csv")
        val json = File(dir, "$baseName.json")
        writeCsv(csv, points)
        writeJson(json, session, laps, points)

        val publicLocation = publish(listOf(csv to MIME_CSV, json to MIME_JSON))
        ExportResult(listOf(csv, json), publicLocation)
    }

    // ---------------------------------------------------------------- CSV

    private fun writeCsv(file: File, points: List<TelemetryPoint>) {
        val symbols = DecimalFormatSymbols(Locale.US)
        val coord = DecimalFormat("0.0000000", symbols)
        val d3 = DecimalFormat("0.000", symbols)
        val d2 = DecimalFormat("0.00", symbols)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

        fun StringBuilder.cell(value: Double?, fmt: DecimalFormat) {
            append(',')
            if (value != null && value.isFinite()) append(fmt.format(value))
        }

        fun StringBuilder.cell(value: Float?, fmt: DecimalFormat) =
            cell(value?.toDouble(), fmt)

        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { out ->
            out.write(CSV_HEADER)
            out.newLine()
            val sb = StringBuilder(256)
            for (p in points) {
                sb.setLength(0)
                sb.append(p.timestampEpochMs)
                sb.append(',').append(iso.format(Date(p.timestampEpochMs)))
                sb.append(',').append(p.elapsedMs)
                sb.append(',').append(p.lapNumber)
                sb.cell(p.latitude, coord)
                sb.cell(p.longitude, coord)
                sb.cell(p.gpsAltitudeM, d2)
                sb.cell(p.gpsAccuracyM, d2)
                sb.cell(p.speedKmh, d2)
                sb.cell(p.accelX, d3)
                sb.cell(p.accelY, d3)
                sb.cell(p.accelZ, d3)
                sb.cell(p.gForce, d3)
                sb.cell(p.pressureHpa, d2)
                sb.cell(p.baroAltitudeM, d2)
                sb.cell(p.pitchDeg, d2)
                sb.cell(p.rollDeg, d2)
                sb.cell(p.temperatureC, d2)
                out.write(sb.toString())
                out.newLine()
            }
        }
    }

    // --------------------------------------------------------------- JSON

    private fun writeJson(
        file: File,
        session: RaceSession,
        laps: List<LapData>,
        points: List<TelemetryPoint>,
    ) {
        JsonWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("app").value("Eco Octano Telemetry")
            w.name("formatVersion").value(1L)

            w.name("session").beginObject()
            w.name("id").value(session.id)
            w.name("name").value(session.name)
            w.name("startedAtEpochMs").value(session.startedAtEpochMs)
            w.name("durationMs").value(session.durationMs)
            w.num("distanceMeters", session.distanceMeters)
            w.num("avgSpeedKmh", session.avgSpeedKmh)
            w.num("maxSpeedKmh", session.maxSpeedKmh)
            w.name("lapCount").value(session.lapCount)
            w.num("bestLapMs", session.bestLapMs)
            w.name("pointCount").value(session.pointCount)
            w.endObject()

            w.name("laps").beginArray()
            for (lap in laps) {
                w.beginObject()
                w.name("lap").value(lap.lapNumber)
                w.name("startOffsetMs").value(lap.startOffsetMs)
                w.name("endOffsetMs").value(lap.endOffsetMs)
                w.name("durationMs").value(lap.durationMs)
                w.num("avgSpeedKmh", lap.avgSpeedKmh)
                w.num("maxSpeedKmh", lap.maxSpeedKmh)
                w.endObject()
            }
            w.endArray()

            w.name("points").beginArray()
            for (p in points) {
                w.beginObject()
                w.name("t").value(p.timestampEpochMs)
                w.name("elapsedMs").value(p.elapsedMs)
                w.name("lap").value(p.lapNumber)
                w.num("lat", p.latitude)
                w.num("lon", p.longitude)
                w.num("gpsAltitudeM", p.gpsAltitudeM)
                w.num("gpsAccuracyM", p.gpsAccuracyM)
                w.num("speedKmh", p.speedKmh)
                w.num("accelX", p.accelX)
                w.num("accelY", p.accelY)
                w.num("accelZ", p.accelZ)
                w.num("gForce", p.gForce)
                w.num("pressureHpa", p.pressureHpa)
                w.num("baroAltitudeM", p.baroAltitudeM)
                w.num("pitchDeg", p.pitchDeg)
                w.num("rollDeg", p.rollDeg)
                w.num("temperatureC", p.temperatureC)
                w.endObject()
            }
            w.endArray()

            w.endObject()
        }
    }

    /** JsonWriter lança exceção para NaN/Infinito: grava `null` nesses casos. */
    private fun JsonWriter.num(name: String, value: Number?) {
        name(name)
        if (value == null || (value is Double && !value.isFinite()) || (value is Float && !value.isFinite())) {
            nullValue()
        } else {
            value(value)
        }
    }

    // ------------------------------------------------------- Publicação

    private fun publish(files: List<Pair<File, String>>): String? {
        var allInMediaStore = true
        for ((file, mime) in files) {
            if (!insertIntoDocuments(file, mime)) allInMediaStore = false
        }
        if (allInMediaStore) return "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_FOLDER"

        val fallbackRoot = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return null
        val fallbackDir = File(fallbackRoot, PUBLIC_FOLDER)
        return try {
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            for ((file, _) in files) file.copyTo(File(fallbackDir, file.name), overwrite = true)
            fallbackDir.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Falha no fallback de exportação", e)
            null
        }
    }

    private fun insertIntoDocuments(file: File, mime: String): Boolean {
        val resolver = appContext.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_FOLDER",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val inserted = resolver.insert(collection, values) ?: return false
            uri = inserted
            val stream = resolver.openOutputStream(inserted) ?: throw IOException("Stream nulo")
            stream.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(inserted, done, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore falhou para ${file.name}", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            false
        }
    }

    private fun fileStamp(epochMs: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(epochMs))
}
