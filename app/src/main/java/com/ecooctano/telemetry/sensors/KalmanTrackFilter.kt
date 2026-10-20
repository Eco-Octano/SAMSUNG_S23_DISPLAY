package com.ecooctano.telemetry.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Estimativa fundida (GPS + acelerômetro) num instante. */
data class FusedState(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
)

/**
 * Filtro de Kalman 2D (leste/norte) com a aceleração linear do aparelho (já rotacionada para
 * o referencial do mundo) como entrada de controle e o VIÉS do acelerômetro estimado como
 * estado. Estado por eixo: [posição, velocidade, viés]. Estimar o viés é essencial: 1° de
 * erro de orientação já "vaza" ~0,17 m/s² da gravidade, o que estragaria a velocidade.
 *
 * - Predição: a cada amostra do acelerômetro (~100 Hz), na thread de sensores.
 * - Correção: a cada fix de GPS (posição e, se houver, velocidade + rumo), na Main thread.
 *
 * Os eixos leste e norte são filtrados de forma independente (estado [posição, velocidade]
 * por eixo), o que mantém a matemática simples e estável. Todos os tempos usam a base
 * SystemClock.elapsedRealtimeNanos (a mesma de SensorEvent.timestamp e
 * Location.elapsedRealtimeNanos).
 */
class KalmanTrackFilter(
    /** Desvio-padrão do ruído de aceleração (m/s²) após remover o viés. */
    private val accelNoise: Double = 0.5,
    /** Passeio aleatório do viés (m/s² por √s): o viés no referencial do mundo muda em curvas. */
    private val biasDrift: Double = 0.02,
) {
    private companion object {
        const val EARTH_RADIUS_M = 6_371_000.0
        const val MAX_PREDICT_STEP_S = 0.5
        const val MAX_GAP_S = 2.0
        /** Sem GPS por mais que isso, para de integrar o acelerômetro (evita deriva). */
        const val ACCEL_TRUST_WINDOW_NS = 3_000_000_000L
        /** Salto entre predição e GPS acima disso = filtro reiniciado no fix. */
        const val RESET_JUMP_M = 150.0
        const val MIN_POSITION_SIGMA_M = 1.5
        const val MIN_SPEED_SIGMA_MS = 0.3
        const val STATIONARY_SPEED_MS = 0.3f
        const val INITIAL_BIAS_SIGMA = 0.3
        const val P = 0
        const val V = 1
        const val B = 2
    }

    /** Estado de um eixo: x = [posição, velocidade, viés] e covariância 3x3 (linha a linha). */
    private class Axis {
        val x = DoubleArray(3)
        val cov = DoubleArray(9)
        val tmp = DoubleArray(9)
        val p: Double get() = x[P]
        val v: Double get() = x[V]
    }

    private val east = Axis()
    private val north = Axis()
    private var initialized = false
    private var originLat = 0.0
    private var originLon = 0.0
    private var metersPerDegLon = 0.0
    private val metersPerDegLat = EARTH_RADIUS_M * PI / 180.0
    private var lastTimeNs = 0L
    private var lastGpsTimeNs = 0L

    @Synchronized
    fun reset() {
        initialized = false
        lastTimeNs = 0L
        lastGpsTimeNs = 0L
    }

    /** Predição com aceleração no referencial do mundo (leste, norte) em m/s². */
    @Synchronized
    fun predict(accelEast: Double, accelNorth: Double, timestampNs: Long) {
        if (!initialized) return
        if (!accelEast.isFinite() || !accelNorth.isFinite()) return
        val dt = (timestampNs - lastTimeNs) / 1e9
        if (dt <= 0.0) return
        lastTimeNs = timestampNs
        if (dt > MAX_PREDICT_STEP_S) {
            // Buraco no fluxo do sensor: só avança o tempo, sem integrar aceleração.
            propagate(east, null, minOf(dt, MAX_GAP_S))
            propagate(north, null, minOf(dt, MAX_GAP_S))
            return
        }
        val trustAccel = timestampNs - lastGpsTimeNs <= ACCEL_TRUST_WINDOW_NS
        propagate(east, if (trustAccel) accelEast else null, dt)
        propagate(north, if (trustAccel) accelNorth else null, dt)
    }

    /** Correção com um fix de GPS. */
    @Synchronized
    fun update(gps: GpsReading) {
        val nowNs = gps.elapsedRealtimeNanos
        val sigmaPos = max(gps.accuracyM?.toDouble() ?: 10.0, MIN_POSITION_SIGMA_M)
        val speedMs = gps.rawSpeedMs
        val bearing = gps.bearingDeg

        if (!initialized) {
            initialize(gps, sigmaPos)
            return
        }

        // Avança até o instante do fix (sem aceleração) se o IMU não fez isso.
        val gap = (nowNs - lastTimeNs) / 1e9
        if (gap > 0.0) {
            propagate(east, null, minOf(gap, MAX_GAP_S))
            propagate(north, null, minOf(gap, MAX_GAP_S))
            lastTimeNs = nowNs
        }

        val x = (gps.longitude - originLon) * metersPerDegLon
        val y = (gps.latitude - originLat) * metersPerDegLat
        if (hypot(x - east.p, y - north.p) > RESET_JUMP_M || hypot(x, y) > 50_000.0) {
            initialize(gps, sigmaPos)
            return
        }

        val r = sigmaPos * sigmaPos
        updatePosition(east, x, r)
        updatePosition(north, y, r)

        if (speedMs != null && speedMs.isFinite()) {
            val sigmaV = max(gps.speedAccuracyMs?.toDouble() ?: 0.5, MIN_SPEED_SIGMA_MS)
            val rv = sigmaV * sigmaV
            if (speedMs < STATIONARY_SPEED_MS) {
                updateVelocity(east, 0.0, rv)
                updateVelocity(north, 0.0, rv)
            } else if (bearing != null && bearing.isFinite()) {
                val rad = Math.toRadians(bearing.toDouble())
                updateVelocity(east, speedMs * sin(rad), rv)
                updateVelocity(north, speedMs * cos(rad), rv)
            }
        }
        lastGpsTimeNs = nowNs
    }

    /** Estado extrapolado até [nowNs] (sem alterar o filtro). Null antes do primeiro fix. */
    @Synchronized
    fun snapshot(nowNs: Long): FusedState? {
        if (!initialized) return null
        val dt = ((nowNs - lastTimeNs) / 1e9).coerceIn(0.0, 1.0)
        val x = east.p + east.v * dt
        val y = north.p + north.v * dt
        val lat = originLat + y / metersPerDegLat
        val lon = originLon + if (metersPerDegLon > 0.0) x / metersPerDegLon else 0.0
        val speedKmh = (hypot(east.v, north.v) * 3.6).toFloat()
        if (!lat.isFinite() || !lon.isFinite() || !speedKmh.isFinite()) return null
        return FusedState(lat, lon, speedKmh)
    }

    private fun initialize(gps: GpsReading, sigmaPos: Double) {
        originLat = gps.latitude
        originLon = gps.longitude
        metersPerDegLon = metersPerDegLat * cos(Math.toRadians(originLat))
        val speed = gps.rawSpeedMs?.toDouble()?.takeIf { it.isFinite() } ?: 0.0
        val bearing = gps.bearingDeg?.toDouble()
        val (ve, vn) = if (bearing != null && bearing.isFinite() && speed > STATIONARY_SPEED_MS) {
            val rad = Math.toRadians(bearing)
            speed * sin(rad) to speed * cos(rad)
        } else {
            0.0 to 0.0
        }
        for ((axis, v) in listOf(east to ve, north to vn)) {
            axis.x[P] = 0.0
            axis.x[V] = v
            axis.x[B] = 0.0
            axis.cov.fill(0.0)
            axis.cov[P * 3 + P] = sigmaPos * sigmaPos
            axis.cov[V * 3 + V] = 4.0
            axis.cov[B * 3 + B] = INITIAL_BIAS_SIGMA * INITIAL_BIAS_SIGMA
        }
        lastTimeNs = gps.elapsedRealtimeNanos
        lastGpsTimeNs = gps.elapsedRealtimeNanos
        initialized = true
    }

    /**
     * Predição: aceleração corrigida = medida - viés.
     * F = [[1, dt, -dt²/2], [0, 1, -dt], [0, 0, 1]];  P = F P Fᵀ + Q.
     */
    private fun propagate(a: Axis, accel: Double?, dt: Double) {
        val x = a.x
        val dt2 = dt * dt
        // Sem aceleração medida (null): velocidade constante, o viés não participa do modelo.
        val useBias = accel != null
        val corrected = if (accel != null) accel - x[B] else 0.0
        x[P] += x[V] * dt + 0.5 * corrected * dt2
        x[V] += corrected * dt
        val f02 = if (useBias) -0.5 * dt2 else 0.0
        val f12 = if (useBias) -dt else 0.0
        val c = a.cov
        val t = a.tmp
        // t = F * P
        for (j in 0..2) {
            t[0 * 3 + j] = c[0 * 3 + j] + dt * c[1 * 3 + j] + f02 * c[2 * 3 + j]
            t[1 * 3 + j] = c[1 * 3 + j] + f12 * c[2 * 3 + j]
            t[2 * 3 + j] = c[2 * 3 + j]
        }
        // P = t * Fᵀ
        for (i in 0..2) {
            val r0 = t[i * 3 + 0]
            val r1 = t[i * 3 + 1]
            val r2 = t[i * 3 + 2]
            c[i * 3 + 0] = r0 + dt * r1 + f02 * r2
            c[i * 3 + 1] = r1 + f12 * r2
            c[i * 3 + 2] = r2
        }
        val q = accelNoise * accelNoise
        c[P * 3 + P] += q * dt2 * dt2 / 4.0
        c[P * 3 + V] += q * dt2 * dt / 2.0
        c[V * 3 + P] += q * dt2 * dt / 2.0
        c[V * 3 + V] += q * dt2
        c[B * 3 + B] += biasDrift * biasDrift * dt
    }

    private fun updatePosition(a: Axis, z: Double, r: Double) = scalarUpdate(a, P, z, r)

    private fun updateVelocity(a: Axis, z: Double, r: Double) = scalarUpdate(a, V, z, r)

    /** Atualização escalar com H = e_i:  K = P[:, i] / S;  x += K·(z - x_i);  P -= K·P[i, :]. */
    private fun scalarUpdate(a: Axis, i: Int, z: Double, r: Double) {
        val c = a.cov
        val s = c[i * 3 + i] + r
        if (s <= 0.0 || !s.isFinite()) return
        val k0 = c[0 * 3 + i] / s
        val k1 = c[1 * 3 + i] / s
        val k2 = c[2 * 3 + i] / s
        val innovation = z - a.x[i]
        a.x[0] += k0 * innovation
        a.x[1] += k1 * innovation
        a.x[2] += k2 * innovation
        val row0 = c[i * 3 + 0]
        val row1 = c[i * 3 + 1]
        val row2 = c[i * 3 + 2]
        val k = doubleArrayOf(k0, k1, k2)
        for (j in 0..2) {
            c[j * 3 + 0] -= k[j] * row0
            c[j * 3 + 1] -= k[j] * row1
            c[j * 3 + 2] -= k[j] * row2
        }
    }
}
