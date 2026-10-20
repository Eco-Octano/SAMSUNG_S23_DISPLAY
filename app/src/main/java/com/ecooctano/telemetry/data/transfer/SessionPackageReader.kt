package com.ecooctano.telemetry.data.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** Corrida lida de um arquivo importado, ainda não gravada no banco. */
data class ParsedSession(
    val session: RaceSession,
    val laps: List<LapData>,
    val points: List<TelemetryPoint>,
    /** Arquivo IMU extraído para uma pasta temporária (o importador o move ou apaga). */
    val imuTemp: File?,
)

/**
 * Lê corridas de:
 *  - pacotes .ecooctano.zip (uma ou várias corridas + IMU), gerados pelo app;
 *  - o .json de uma corrida, exportado junto do CSV.
 *
 * Nunca usa nomes de entradas do zip como caminho de arquivo (sem risco de "zip slip"):
 * os arquivos IMU vão para nomes gerados em [tempDir].
 */
class SessionPackageReader(private val tempDir: File) {

    private companion object {
        const val TAG = "SessionPackageReader"
        const val MAX_ZIP_ENTRIES = 5_000
    }

    private class Slot {
        var parsed: ParsedSession? = null
        var imu: File? = null
    }

    fun read(input: InputStream): List<ParsedSession> {
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 'P'.code && b1 == 'K'.code) {
            readZip(buffered)
        } else {
            listOf(parseSessionJson(buffered))
        }
    }

    private fun readZip(input: InputStream): List<ParsedSession> {
        if (!tempDir.exists()) tempDir.mkdirs()
        val slots = LinkedHashMap<String, Slot>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            var count = 0
            while (entry != null && count < MAX_ZIP_ENTRIES) {
                count++
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    val dir = name.substringBeforeLast('/', missingDelimiterValue = "")
                    val file = name.substringAfterLast('/')
                    when {
                        file.equals("manifest.json", ignoreCase = true) -> Unit
                        file.endsWith(".json", ignoreCase = true) ->
                            slots.getOrPut(dir) { Slot() }.parsed = parseSessionJson(NonClosingStream(zip))
                        file.endsWith(".csv", ignoreCase = true) && file.contains("imu", ignoreCase = true) -> {
                            val tmp = File.createTempFile("import_imu_", ".csv", tempDir)
                            tmp.outputStream().use { NonClosingStream(zip).copyTo(it) }
                            slots.getOrPut(dir) { Slot() }.imu = tmp
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val result = ArrayList<ParsedSession>()
        for (slot in slots.values) {
            val parsed = slot.parsed
            if (parsed == null) {
                slot.imu?.delete()
            } else {
                result.add(parsed.copy(imuTemp = slot.imu))
            }
        }
        if (result.isEmpty()) throw IOException("O arquivo não contém corridas do Eco Octano.")
        return result
    }

    /** Formato escrito por TelemetryExporter.writeJsonTo (formatVersion 1). */
    private fun parseSessionJson(input: InputStream): ParsedSession {
        val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
        var session: RaceSession? = null
        val laps = ArrayList<LapData>()
        val points = ArrayList<TelemetryPoint>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "session" -> session = readSession(r)
                "laps" -> {
                    r.beginArray()
                    while (r.hasNext()) readLap(r)?.let(laps::add)
                    r.endArray()
                }
                "points" -> {
                    r.beginArray()
                    while (r.hasNext()) readPoint(r)?.let(points::add)
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val s = session ?: throw IOException("JSON sem o bloco \"session\".")
        points.sortBy { it.elapsedMs }
        return ParsedSession(s.copy(pointCount = points.size), laps, points, imuTemp = null)
    }

    private fun readSession(r: JsonReader): RaceSession {
        var name = "Corrida importada"
        var startedAt: Long? = null
        var duration = 0L
        var distance = 0.0
        var avg = 0f
        var max = 0f
        var lapCount = 0
        var bestLap: Long? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "name" -> name = r.optString() ?: name
                "startedAtEpochMs" -> startedAt = r.optLong()
                "durationMs" -> duration = r.optLong() ?: 0L
                "distanceMeters" -> distance = r.optDouble() ?: 0.0
                "avgSpeedKmh" -> avg = r.optFloat() ?: 0f
                "maxSpeedKmh" -> max = r.optFloat() ?: 0f
                "lapCount" -> lapCount = r.optLong()?.toInt() ?: 0
                "bestLapMs" -> bestLap = r.optLong()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RaceSession(
            name = name,
            startedAtEpochMs = startedAt ?: throw IOException("Corrida sem data de início."),
            durationMs = duration,
            distanceMeters = distance,
            avgSpeedKmh = avg,
            maxSpeedKmh = max,
            lapCount = lapCount,
            bestLapMs = bestLap,
            pointCount = 0,
        )
    }

    private fun readLap(r: JsonReader): LapData? {
        var number: Int? = null
        var start = 0L
        var end = 0L
        var duration = 0L
        var avg = 0f
        var max = 0f
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "lap" -> number = r.optLong()?.toInt()
                "startOffsetMs" -> start = r.optLong() ?: 0L
                "endOffsetMs" -> end = r.optLong() ?: 0L
                "durationMs" -> duration = r.optLong() ?: 0L
                "avgSpeedKmh" -> avg = r.optFloat() ?: 0f
                "maxSpeedKmh" -> max = r.optFloat() ?: 0f
                else -> r.skipValue()
            }
        }
        r.endObject()
        val n = number ?: return null
        return LapData(lapNumber = n, startOffsetMs = start, endOffsetMs = end, durationMs = duration, avgSpeedKmh = avg, maxSpeedKmh = max)
    }

    private fun readPoint(r: JsonReader): TelemetryPoint? {
        var t: Long? = null
        var elapsed: Long? = null
        var lap = 1
        var lat: Double? = null
        var lon: Double? = null
        var alt: Double? = null
        var acc: Float? = null
        var speed: Float? = null
        var ax: Float? = null
        var ay: Float? = null
        var az: Float? = null
        var g: Float? = null
        var pressure: Float? = null
        var baroAlt: Float? = null
        var pitch: Float? = null
        var roll: Float? = null
        var temp: Float? = null
        var latRaw: Double? = null
        var lonRaw: Double? = null
        var speedRaw: Float? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "t" -> t = r.optLong()
                "elapsedMs" -> elapsed = r.optLong()
                "lap" -> lap = r.optLong()?.toInt() ?: 1
                "lat" -> lat = r.optDouble()
                "lon" -> lon = r.optDouble()
                "gpsAltitudeM" -> alt = r.optDouble()
                "gpsAccuracyM" -> acc = r.optFloat()
                "speedKmh" -> speed = r.optFloat()
                "accelX" -> ax = r.optFloat()
                "accelY" -> ay = r.optFloat()
                "accelZ" -> az = r.optFloat()
                "gForce" -> g = r.optFloat()
                "pressureHpa" -> pressure = r.optFloat()
                "baroAltitudeM" -> baroAlt = r.optFloat()
                "pitchDeg" -> pitch = r.optFloat()
                "rollDeg" -> roll = r.optFloat()
                "temperatureC" -> temp = r.optFloat()
                "gpsLatRaw" -> latRaw = r.optDouble()
                "gpsLonRaw" -> lonRaw = r.optDouble()
                "gpsSpeedRawKmh" -> speedRaw = r.optFloat()
                else -> r.skipValue()
            }
        }
        r.endObject()
        val elapsedMs = elapsed ?: return null
        return TelemetryPoint(
            timestampEpochMs = t ?: 0L,
            elapsedMs = elapsedMs,
            lapNumber = lap,
            latitude = lat,
            longitude = lon,
            gpsAltitudeM = alt,
            gpsAccuracyM = acc,
            speedKmh = speed,
            accelX = ax,
            accelY = ay,
            accelZ = az,
            gForce = g,
            pressureHpa = pressure,
            baroAltitudeM = baroAlt,
            pitchDeg = pitch,
            rollDeg = roll,
            temperatureC = temp,
            gpsLatitudeRaw = latRaw,
            gpsLongitudeRaw = lonRaw,
            gpsSpeedRawKmh = speedRaw,
        )
    }

    // ---- Leitura tolerante: null, números ou texto numérico ----

    private fun JsonReader.optString(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.optDouble(): Double? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.NUMBER, JsonToken.STRING -> try {
            nextDouble().takeIf { it.isFinite() }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Número inválido ignorado", e)
            null
        }
        else -> {
            skipValue()
            null
        }
    }

    private fun JsonReader.optLong(): Long? = optDouble()?.toLong()

    private fun JsonReader.optFloat(): Float? = optDouble()?.toFloat()?.takeIf { it.isFinite() }

    /** Impede que o JsonReader/copyTo fechem o ZipInputStream ao terminar uma entrada. */
    private class NonClosingStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }
}
