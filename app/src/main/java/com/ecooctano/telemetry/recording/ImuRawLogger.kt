package com.ecooctano.telemetry.recording

import android.hardware.Sensor
import android.util.Log
import com.ecooctano.telemetry.sensors.RawSensorListener
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Grava as leituras BRUTAS (sem filtro) dos sensores inerciais em CSV, na taxa nativa
 * pedida ao hardware (~100 Hz), enquanto a corrida está rodando.
 *
 * Colunas: race_elapsed_ms, sensor_time_ms, sensor, x, y, z
 *  - ACC = acelerômetro com gravidade (m/s²)
 *  - LIN = aceleração linear (m/s², sem gravidade)
 *  - GYR = giroscópio (rad/s)
 *  - BAR = pressão (hPa, em x)
 *
 * Chamado na thread de sensores; início/pausa/fim vêm da Main thread (sincronizado).
 */
class ImuRawLogger(private val directory: File) : RawSensorListener {

    companion object {
        private const val TAG = "ImuRawLogger"
        const val HEADER = "race_elapsed_ms,sensor_time_ms,sensor,x,y,z"
    }

    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var active = false
    private var accumulatedMs = 0L
    private var runStartedRealtimeMs = 0L
    private val sb = StringBuilder(96)

    /** Abre um arquivo novo para a sessão (descarta qualquer arquivo anterior não salvo). */
    fun begin(sessionStartEpochMs: Long) {
        synchronized(lock) {
            closeLocked()
            file?.delete()
            try {
                if (!directory.exists()) directory.mkdirs()
                val f = File(directory, "imu_$sessionStartEpochMs.csv")
                val w = BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8), 64 * 1024)
                w.write(HEADER)
                w.newLine()
                writer = w
                file = f
            } catch (e: Exception) {
                Log.e(TAG, "Não foi possível criar o arquivo IMU", e)
                writer = null
                file = null
            }
            active = false
        }
    }

    /** Começa/retoma a gravação; o tempo de corrida = accumulated + (agora - runStarted). */
    fun resume(accumulatedMs: Long, runStartedRealtimeMs: Long) {
        synchronized(lock) {
            this.accumulatedMs = accumulatedMs
            this.runStartedRealtimeMs = runStartedRealtimeMs
            active = writer != null
        }
    }

    fun pause() {
        synchronized(lock) {
            active = false
            try {
                writer?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "flush falhou", e)
            }
        }
    }

    /** Fecha o arquivo e o entrega para quem vai salvá-lo. Null se nada foi gravado. */
    fun finish(): File? = synchronized(lock) {
        active = false
        closeLocked()
        val f = file
        file = null
        f?.takeIf { it.exists() && it.length() > HEADER.length + 1 }
    }

    /** Descarta a sessão atual (reset). */
    fun discard() {
        synchronized(lock) {
            active = false
            closeLocked()
            file?.delete()
            file = null
        }
    }

    override fun onRawSample(type: Int, timestampNs: Long, x: Float, y: Float, z: Float) {
        synchronized(lock) {
            if (!active) return
            val w = writer ?: return
            val tag = when (type) {
                Sensor.TYPE_ACCELEROMETER -> "ACC"
                Sensor.TYPE_LINEAR_ACCELERATION -> "LIN"
                Sensor.TYPE_GYROSCOPE -> "GYR"
                Sensor.TYPE_PRESSURE -> "BAR"
                else -> return
            }
            val sensorMs = timestampNs / 1_000_000L
            val raceMs = accumulatedMs + (sensorMs - runStartedRealtimeMs)
            sb.setLength(0)
            sb.append(raceMs).append(',')
                .append(sensorMs).append(',')
                .append(tag).append(',')
                .append(x).append(',')
                .append(y).append(',')
                .append(z)
            try {
                w.write(sb.toString())
                w.newLine()
            } catch (e: Exception) {
                Log.e(TAG, "Escrita IMU falhou; logger desativado", e)
                active = false
            }
        }
    }

    private fun closeLocked() {
        try {
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close falhou", e)
        }
        writer = null
    }
}
