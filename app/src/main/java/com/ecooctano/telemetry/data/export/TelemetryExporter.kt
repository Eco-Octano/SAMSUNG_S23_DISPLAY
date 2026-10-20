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
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        private const val MIME_ZIP = "application/zip"
        /** Extensão do pacote de transferência entre aparelhos (é um .zip comum). */
        const val PACKAGE_EXTENSION = ".ecooctano.zip"
        const val PACKAGE_FORMAT = "ecooctano-package"

        private val CSV_HEADER = listOf(
            "timestamp_epoch_ms", "timestamp_iso", "elapsed_ms", "lap",
            "latitude", "longitude", "gps_altitude_m", "gps_accuracy_m", "speed_kmh",
            "accel_x_ms2", "accel_y_ms2", "accel_z_ms2", "g_force",
            "pressure_hpa", "baro_altitude_m", "pitch_deg", "roll_deg", "temperature_c",
            "gps_latitude_raw", "gps_longitude_raw", "gps_speed_raw_kmh",
        ).joinToString(",")
    }

    data class ExportResult(
        val localFiles: List<File>,
        val publicLocation: String?,
        val imuFileName: String?,
    )

    /** Pasta interna dos arquivos exportados (compartilhada via FileProvider). */
    fun exportsDir(): File = File(appContext.filesDir, "exports")

    /** Nome-base dos arquivos de uma corrida (sem extensão). */
    fun baseNameFor(session: RaceSession): String =
        "EcoOctano_${fileStamp(session.startedAtEpochMs)}_S${session.id}"

    /** Uma corrida dentro de um pacote de transferência. */
    data class PackageEntry(
        val session: RaceSession,
        val laps: List<LapData>,
        val points: List<TelemetryPoint>,
        val imu: File?,
    )

    /**
     * Gera um pacote .ecooctano.zip com uma ou mais corridas:
     *   manifest.json
     *   session_1/session.json   (resumo, voltas e todos os pontos de 5 Hz)
     *   session_1/imu.csv        (dados brutos ~100 Hz, se houver)
     * É o formato usado para importar em outro aparelho (versão de análise).
     */
    fun writePackage(target: File, entries: List<PackageEntry>) {
        target.parentFile?.mkdirs()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target), 64 * 1024)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = OutputStreamWriter(zip, Charsets.UTF_8)
            val mw = JsonWriter(manifest)
            mw.beginObject()
            mw.name("format").value(PACKAGE_FORMAT)
            mw.name("formatVersion").value(1L)
            mw.name("createdAtEpochMs").value(System.currentTimeMillis())
            mw.name("sessionCount").value(entries.size.toLong())
            mw.endObject()
            mw.flush()
            zip.closeEntry()

            entries.forEachIndexed { index, e ->
                val dir = "session_${index + 1}"
                zip.putNextEntry(ZipEntry("$dir/session.json"))
                val writer = OutputStreamWriter(zip, Charsets.UTF_8)
                writeJsonTo(writer, e.session, e.laps, e.points)
                writer.flush()
                zip.closeEntry()
                val imu = e.imu
                if (imu != null && imu.exists()) {
                    zip.putNextEntry(ZipEntry("$dir/imu.csv"))
                    imu.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private val appContext = context.applicationContext

    suspend fun export(
        session: RaceSession,
        laps: List<LapData>,
        points: List<TelemetryPoint>,
        rawImu: File? = null,
    ): ExportResult = withContext(Dispatchers.IO) {
        val dir = exportsDir()
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Não foi possível criar ${dir.absolutePath}")

        val baseName = baseNameFor(session)
        val csv = File(dir, "$baseName.csv")
        val json = File(dir, "$baseName.json")
        writeCsv(csv, points)
        writeJson(json, session, laps, points)

        val files = mutableListOf(csv to MIME_CSV, json to MIME_JSON)
        val imu = rawImu?.takeIf { it.exists() }?.let { src -> moveInto(src, File(dir, "${baseName}_imu.csv")) }
        if (imu != null) files.add(imu to MIME_CSV)

        // Pacote para importar em outro aparelho (vai primeiro na lista de compartilhamento).
        try {
            val pkg = File(dir, "$baseName$PACKAGE_EXTENSION")
            writePackage(pkg, listOf(PackageEntry(session, laps, points, imu)))
            files.add(0, pkg to MIME_ZIP)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao gerar pacote", e)
        }

        val publicLocation = publish(files)
        ExportResult(files.map { it.first }, publicLocation, imu?.name)
    }

    /** Move (ou copia, se o rename falhar) o arquivo IMU temporário para a pasta de exportação. */
    private fun moveInto(src: File, target: File): File? {
        if (src.absolutePath == target.absolutePath) return target
        return try {
            if (target.exists()) target.delete()
            if (!src.renameTo(target)) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            target
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao mover arquivo IMU", e)
            null
        }
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
                sb.cell(p.gpsLatitudeRaw, coord)
                sb.cell(p.gpsLongitudeRaw, coord)
                sb.cell(p.gpsSpeedRawKmh, d2)
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
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { writeJsonTo(it, session, laps, points) }
    }

    /** Escreve o JSON da corrida sem fechar [out] (permite gravar dentro de um zip). */
    private fun writeJsonTo(
        out: Writer,
        session: RaceSession,
        laps: List<LapData>,
        points: List<TelemetryPoint>,
    ) {
        JsonWriter(out).let { w ->
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
            w.name("imuFile").value(session.imuFileName)
            w.name("positionSource").value("kalman_gps_imu")
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
                w.num("gpsLatRaw", p.gpsLatitudeRaw)
                w.num("gpsLonRaw", p.gpsLongitudeRaw)
                w.num("gpsSpeedRawKmh", p.gpsSpeedRawKmh)
                w.endObject()
            }
            w.endArray()

            w.endObject()
            w.flush()
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
