package com.ecooctano.telemetry.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.asin
import kotlin.math.sqrt

enum class TemperatureSource { AMBIENT_SENSOR, BATTERY, UNAVAILABLE }

data class SensorAvailability(
    val linearAcceleration: Boolean,
    val gyroscope: Boolean,
    val rotationVector: Boolean,
    val accelerometer: Boolean,
    val pressure: Boolean,
    val ambientTemperature: Boolean,
)

/** Snapshot imutável dos sensores. `null` = indisponível ("N/A"). */
data class SensorReadings(
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val gForce: Float? = null,
    val pitchDeg: Float? = null,
    val rollDeg: Float? = null,
    val yawRateDegS: Float? = null,
    val pressureHpa: Float? = null,
    val baroAltitudeM: Float? = null,
    val temperatureC: Float? = null,
    val temperatureSource: TemperatureSource = TemperatureSource.UNAVAILABLE,
    val availability: SensorAvailability? = null,
)

/**
 * Gerencia acelerômetro linear, giroscópio, vetor de rotação, barômetro e temperatura.
 *
 * - Listeners rodam numa HandlerThread própria (não bloqueiam a UI).
 * - Usa apenas o applicationContext (sem leak de Activity).
 * - Todo sensor é verificado (`!= null`) antes de registrar; ausência vira `null` → "N/A".
 * - Publica no máximo a cada [PUBLISH_INTERVAL_MS] para não sobrecarregar a recomposição.
 *
 * Inclinação (pitch/roll): calculada a partir do vetor "para cima" do mundo expresso no
 * referencial do aparelho, comparado com um referencial do veículo capturado na calibração.
 * Isso funciona tanto com o celular em pé no painel (paisagem) quanto deitado, sem o
 * problema de gimbal-lock do getOrientation() com o aparelho na vertical.
 */
class SensorTelemetryManager(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "SensorTelemetry"
        private const val EMA_ALPHA = 0.2f
        private const val GRAVITY_MS2 = 9.81f
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()
        /** Peso do acelerômetro no filtro complementar (fallback sem ROTATION_VECTOR). */
        private const val COMPLEMENTARY_ACCEL_WEIGHT_WITH_GYRO = 0.02f
        private const val COMPLEMENTARY_ACCEL_WEIGHT_NO_GYRO = 0.1f
    }

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val linearAccelSensor: Sensor? = defaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyroscopeSensor: Sensor? = defaultSensor(Sensor.TYPE_GYROSCOPE)
    private val rotationVectorSensor: Sensor? = defaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometerSensor: Sensor? = defaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val pressureSensor: Sensor? = defaultSensor(Sensor.TYPE_PRESSURE)
    private val ambientTemperatureSensor: Sensor? = defaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)

    val availability = SensorAvailability(
        linearAcceleration = linearAccelSensor != null,
        gyroscope = gyroscopeSensor != null,
        rotationVector = rotationVectorSensor != null,
        accelerometer = accelerometerSensor != null,
        pressure = pressureSensor != null,
        ambientTemperature = ambientTemperatureSensor != null,
    )

    private val temperatureSource: TemperatureSource =
        if (ambientTemperatureSensor != null) TemperatureSource.AMBIENT_SENSOR else TemperatureSource.BATTERY

    private val _readings = MutableStateFlow(
        SensorReadings(availability = availability, temperatureSource = temperatureSource),
    )
    val readings: StateFlow<SensorReadings> = _readings.asStateFlow()

    private val lock = Any()
    private var running = false
    private var sensorThread: HandlerThread? = null
    private var batteryReceiverRegistered = false

    // ---- Valores filtrados (escritos na thread de sensores; temperatura também na Main) ----
    @Volatile private var accelX: Float? = null
    @Volatile private var accelY: Float? = null
    @Volatile private var accelZ: Float? = null
    @Volatile private var gForce: Float? = null
    @Volatile private var pitchDeg: Float? = null
    @Volatile private var rollDeg: Float? = null
    @Volatile private var yawRateDegS: Float? = null
    @Volatile private var pressureHpa: Float? = null
    @Volatile private var baroAltitudeM: Float? = null
    @Volatile private var temperatureC: Float? = null
    @Volatile private var lastPublishRealtime = 0L

    // ---- Barômetro ----
    private var baroBaselineAltitude: Float? = null
    @Volatile private var baroBaselineResetRequested = false

    // ---- Orientação (somente thread de sensores) ----
    private val rotationMatrix = FloatArray(9)
    private val up = FloatArray(3)
    private var hasUp = false
    private val refForward = FloatArray(3)
    private val refRight = FloatArray(3)
    private var hasReference = false
    @Volatile private var calibrationRequested = false
    private var lastGyroTimestampNs = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleBatteryIntent(intent)
        }
    }

    // ================================================================ Ciclo de vida

    fun start() {
        synchronized(lock) {
            if (running) return
            val sm = sensorManager
            if (sm != null) {
                val thread = HandlerThread("EcoOctano-Sensors").also { it.start() }
                sensorThread = thread
                val handler = Handler(thread.looper)
                register(sm, linearAccelSensor, handler)
                register(sm, gyroscopeSensor, handler)
                register(sm, pressureSensor, handler)
                register(sm, ambientTemperatureSensor, handler)
                if (rotationVectorSensor != null) {
                    register(sm, rotationVectorSensor, handler)
                } else {
                    // Fallback: acelerômetro bruto + giroscópio em filtro complementar.
                    register(sm, accelerometerSensor, handler)
                }
            } else {
                Log.w(TAG, "SensorManager indisponível")
            }
            if (ambientTemperatureSensor == null) registerBatteryReceiver()
            running = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            try {
                sensorManager?.unregisterListener(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Falha ao remover listeners", t)
            }
            sensorThread?.quitSafely()
            sensorThread = null
            unregisterBatteryReceiver()
            lastGyroTimestampNs = 0L
            running = false
        }
    }

    /** Define a altitude barométrica atual como zero (chamado ao iniciar uma corrida). */
    fun resetBarometricBaseline() {
        baroBaselineResetRequested = true
    }

    /** Usa a orientação atual do aparelho como referência "nível" (pitch = roll = 0). */
    fun calibrateInclination() {
        calibrationRequested = true
    }

    private fun register(sm: SensorManager, sensor: Sensor?, handler: Handler) {
        if (sensor == null) return
        try {
            val ok = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
            if (!ok) Log.w(TAG, "registerListener retornou false para ${sensor.name}")
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao registrar ${sensor.name}", t)
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        try {
            // ACTION_BATTERY_CHANGED é um broadcast protegido (só o sistema envia).
            // O retorno é o último intent "sticky": lemos imediatamente a temperatura.
            val sticky = ContextCompat.registerReceiver(
                appContext,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            batteryReceiverRegistered = true
            handleBatteryIntent(sticky)
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao registrar receiver de bateria", t)
        }
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) return
        try {
            appContext.unregisterReceiver(batteryReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "Receiver de bateria já removido", t)
        }
        batteryReceiverRegistered = false
    }

    private fun defaultSensor(type: Int): Sensor? =
        try {
            sensorManager?.getDefaultSensor(type)
        } catch (t: Throwable) {
            null
        }

    // ================================================================ Eventos

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        val values = e.values ?: return
        try {
            when (e.sensor?.type) {
                Sensor.TYPE_LINEAR_ACCELERATION -> if (values.size >= 3) handleLinearAcceleration(values)
                Sensor.TYPE_GYROSCOPE -> if (values.size >= 3) handleGyroscope(values, e.timestamp)
                Sensor.TYPE_ROTATION_VECTOR -> if (values.size >= 3) handleRotationVector(values)
                Sensor.TYPE_ACCELEROMETER -> if (values.size >= 3) handleAccelerometer(values)
                Sensor.TYPE_PRESSURE -> if (values.isNotEmpty()) handlePressure(values[0])
                Sensor.TYPE_AMBIENT_TEMPERATURE -> if (values.isNotEmpty()) handleAmbientTemperature(values[0])
            }
            publish(force = false)
        } catch (t: Throwable) {
            Log.w(TAG, "Leitura de sensor descartada", t)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleLinearAcceleration(v: FloatArray) {
        val x = v[0]
        val y = v[1]
        val z = v[2]
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        val fx = ema(accelX, x)
        val fy = ema(accelY, y)
        val fz = ema(accelZ, z)
        accelX = fx
        accelY = fy
        accelZ = fz
        gForce = sqrt(fx * fx + fy * fy + fz * fz) / GRAVITY_MS2
    }

    private fun handleRotationVector(v: FloatArray) {
        // Alguns firmwares entregam 5 valores; getRotationMatrixFromVector usa só os 4 primeiros.
        val vec = if (v.size > 4) v.copyOf(4) else v
        SensorManager.getRotationMatrixFromVector(rotationMatrix, vec)
        // 3ª linha da matriz = eixo Z do mundo ("para cima") no referencial do aparelho.
        up[0] = rotationMatrix[6]
        up[1] = rotationMatrix[7]
        up[2] = rotationMatrix[8]
        if (!normalize(up)) return
        hasUp = true
        updateInclination()
    }

    private fun handleGyroscope(v: FloatArray, timestampNs: Long) {
        val wx = v[0]
        val wy = v[1]
        val wz = v[2]
        if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) return

        if (hasUp) {
            // Taxa de guinada = projeção da velocidade angular no eixo vertical.
            yawRateDegS = (wx * up[0] + wy * up[1] + wz * up[2]) * RAD_TO_DEG
        }

        if (rotationVectorSensor == null && hasUp) {
            val last = lastGyroTimestampNs
            if (last != 0L) {
                val dt = (timestampNs - last) * 1e-9f
                if (dt > 0f && dt < 0.5f) {
                    // Vetor fixo no mundo visto do aparelho em rotação: du/dt = -ω × u
                    val cx = wy * up[2] - wz * up[1]
                    val cy = wz * up[0] - wx * up[2]
                    val cz = wx * up[1] - wy * up[0]
                    up[0] -= cx * dt
                    up[1] -= cy * dt
                    up[2] -= cz * dt
                    if (normalize(up)) updateInclination()
                }
            }
        }
        lastGyroTimestampNs = timestampNs
    }

    private fun handleAccelerometer(v: FloatArray) {
        if (rotationVectorSensor != null) return
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (!n.isFinite() || n < 1e-3f) return
        val ax = v[0] / n
        val ay = v[1] / n
        val az = v[2] / n
        if (!hasUp) {
            up[0] = ax
            up[1] = ay
            up[2] = az
            hasUp = true
        } else {
            val k = if (gyroscopeSensor != null) COMPLEMENTARY_ACCEL_WEIGHT_WITH_GYRO else COMPLEMENTARY_ACCEL_WEIGHT_NO_GYRO
            up[0] = up[0] * (1f - k) + ax * k
            up[1] = up[1] * (1f - k) + ay * k
            up[2] = up[2] * (1f - k) + az * k
            if (!normalize(up)) return
        }
        updateInclination()
    }

    private fun handlePressure(raw: Float) {
        if (!raw.isFinite() || raw <= 0f) return
        val filtered = ema(pressureHpa, raw)
        pressureHpa = filtered
        val absoluteAltitude = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, filtered)
        if (!absoluteAltitude.isFinite()) return
        val baseline = baroBaselineAltitude
        if (baseline == null || baroBaselineResetRequested) {
            baroBaselineAltitude = absoluteAltitude
            baroBaselineResetRequested = false
            baroAltitudeM = 0f
        } else {
            baroAltitudeM = absoluteAltitude - baseline
        }
    }

    private fun handleAmbientTemperature(value: Float) {
        if (value.isFinite()) temperatureC = value
    }

    private fun handleBatteryIntent(intent: Intent?) {
        if (intent == null) return
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (raw == Int.MIN_VALUE) return
        temperatureC = raw / 10f
        publish(force = true)
    }

    // ================================================================ Inclinação

    private fun updateInclination() {
        if (calibrationRequested || !hasReference) {
            captureReference()
            calibrationRequested = false
        }
        if (!hasReference) return
        val sinPitch = (up[0] * refForward[0] + up[1] * refForward[1] + up[2] * refForward[2]).coerceIn(-1f, 1f)
        val sinRoll = -(up[0] * refRight[0] + up[1] * refRight[1] + up[2] * refRight[2]).coerceIn(-1f, 1f)
        pitchDeg = asin(sinPitch) * RAD_TO_DEG // + = subida (frente para cima)
        rollDeg = asin(sinRoll) * RAD_TO_DEG // + = inclinado para a direita
    }

    /**
     * Define o referencial do veículo a partir da orientação atual:
     * - em pé no painel (paisagem, tela para o piloto): frente do carro = -Z do aparelho;
     * - deitado (tela para cima, topo à esquerda): frente do carro = +X do aparelho.
     */
    private fun captureReference() {
        if (!hasUp) return
        val forward = projectOnPlane(floatArrayOf(0f, 0f, -1f), up)
        if (vectorLength(forward) < 0.5f) {
            val alt = projectOnPlane(floatArrayOf(1f, 0f, 0f), up)
            forward[0] = alt[0]
            forward[1] = alt[1]
            forward[2] = alt[2]
        }
        if (!normalize(forward)) return
        // direita = frente × cima
        val right = floatArrayOf(
            forward[1] * up[2] - forward[2] * up[1],
            forward[2] * up[0] - forward[0] * up[2],
            forward[0] * up[1] - forward[1] * up[0],
        )
        if (!normalize(right)) return
        forward.copyInto(refForward)
        right.copyInto(refRight)
        hasReference = true
    }

    private fun projectOnPlane(v: FloatArray, normal: FloatArray): FloatArray {
        val d = v[0] * normal[0] + v[1] * normal[1] + v[2] * normal[2]
        return floatArrayOf(v[0] - d * normal[0], v[1] - d * normal[1], v[2] - d * normal[2])
    }

    private fun vectorLength(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: FloatArray): Boolean {
        val n = vectorLength(v)
        if (!n.isFinite() || n < 1e-6f) return false
        v[0] /= n
        v[1] /= n
        v[2] /= n
        return true
    }

    // ================================================================ Utilitários

    /** Filtro passa-baixa (média móvel exponencial, α = 0.2). */
    private fun ema(previous: Float?, raw: Float): Float =
        if (previous == null || !previous.isFinite()) raw else previous + EMA_ALPHA * (raw - previous)

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublishRealtime < PUBLISH_INTERVAL_MS) return
        lastPublishRealtime = now
        _readings.value = SensorReadings(
            accelX = accelX,
            accelY = accelY,
            accelZ = accelZ,
            gForce = gForce,
            pitchDeg = pitchDeg,
            rollDeg = rollDeg,
            yawRateDegS = yawRateDegS,
            pressureHpa = pressureHpa,
            baroAltitudeM = baroAltitudeM,
            temperatureC = temperatureC,
            temperatureSource = if (temperatureC != null) temperatureSource else TemperatureSource.UNAVAILABLE,
            availability = availability,
        )
    }
}
