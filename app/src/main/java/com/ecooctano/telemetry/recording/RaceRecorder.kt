package com.ecooctano.telemetry.recording

import android.app.Application
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ecooctano.telemetry.data.TelemetryRepository
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import com.ecooctano.telemetry.data.model.TrackPoint
import com.ecooctano.telemetry.data.model.TrackSnapshot
import com.ecooctano.telemetry.sensors.FusedState
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.sensors.KalmanTrackFilter
import com.ecooctano.telemetry.sensors.LocationManager
import com.ecooctano.telemetry.sensors.SensorReadings
import com.ecooctano.telemetry.sensors.SensorTelemetryManager
import com.ecooctano.telemetry.sensors.WorldAccelListener
import com.ecooctano.telemetry.ui.UiEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RaceStatus { IDLE, RUNNING, PAUSED }

data class LiveSessionState(
    val status: RaceStatus = RaceStatus.IDLE,
    val elapsedMs: Long = 0L,
    val lapElapsedMs: Long = 0L,
    val lapNumber: Int = 1,
    val bestLapMs: Long? = null,
    val lastLapMs: Long? = null,
    val completedLaps: List<LapData> = emptyList(),
    val avgSpeedKmh: Float? = null,
    val maxSpeedKmh: Float? = null,
    val distanceMeters: Double = 0.0,
    val pointCount: Int = 0,
    val gpsFresh: Boolean = false,
    val isSaving: Boolean = false,
    /** Velocidade exibida: estimativa fundida (Kalman), null sem GPS recente. */
    val speedKmh: Float? = null,
    /** Posição exibida no mapa: estimativa fundida. */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val isRecording: Boolean get() = status == RaceStatus.RUNNING
    val isSessionActive: Boolean get() = status != RaceStatus.IDLE
}

/**
 * Dono da corrida em andamento. Vive no nível do Application (não da tela), então a gravação
 * continua com a tela apagada, com outro app aberto ou se a Activity for recriada.
 * O [TelemetryRecordingService] mantém o processo em primeiro plano enquanto há sessão ativa.
 *
 * Todo o estado mutável da corrida é acessado na Main thread (scope com Dispatchers.Main).
 */
class RaceRecorder(
    private val app: Application,
    private val repository: TelemetryRepository,
    private val events: UiEvents,
) {
    companion object {
        private const val TAG = "RaceRecorder"
        /** 5 Hz */
        const val SAMPLE_INTERVAL_MS = 200L
        private const val UI_TICK_MS = 100L
        private const val GPS_STALE_MS = 2_500L
        private const val MIN_LAP_MS = 2_000L
        private const val MAX_DISTANCE_ACCURACY_M = 30f
        /** ~200 km/h: acima disso o deslocamento entre amostras é ruído. */
        private const val MAX_PLAUSIBLE_SPEED_MS = 55.0
        /** Movimento mínimo entre pontos do traçado no mapa. */
        private const val MIN_TRACK_STEP_M = 0.3f
        /** Abaixo disso a velocidade exibida é zero (ruído parado). */
        private const val DISPLAY_DEADBAND_KMH = 0.5f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sensorManager = SensorTelemetryManager(app)
    val locationManager = LocationManager(app)
    private val fusion = KalmanTrackFilter()
    private val imuLogger = ImuRawLogger(File(app.filesDir, "raw"))

    val sensorReadings: StateFlow<SensorReadings> = sensorManager.readings
    val gpsReading: StateFlow<GpsReading?> = locationManager.location
    val gpsStatus: StateFlow<GpsStatus> = locationManager.status

    private val _live = MutableStateFlow(LiveSessionState())
    val live: StateFlow<LiveSessionState> = _live.asStateFlow()

    private val _track = MutableStateFlow(TrackSnapshot.EMPTY)
    val track: StateFlow<TrackSnapshot> = _track.asStateFlow()

    // ---- Estado da corrida (somente Main thread) ----
    private var recordedPoints = ArrayList<TelemetryPoint>()
    private var trackPoints = ArrayList<TrackPoint>()
    private val completedLaps = ArrayList<LapData>()
    private var accumulatedMs = 0L
    private var runStartedRealtime = 0L
    private var lapStartElapsedMs = 0L
    private var lapStartPointIndex = 0
    private var lapNumber = 1
    private var sessionStartEpochMs = 0L
    private var distanceMeters = 0.0
    private var lastDistanceSample: FusedState? = null
    private var lastDistanceRealtime = 0L
    private var speedSum = 0.0
    private var speedSamples = 0
    private var maxSpeedKmh: Float? = null
    /** Arquivo IMU de uma tentativa de salvamento que falhou (reaproveitado na próxima). */
    private var pendingImuFile: File? = null

    private var recordJob: Job? = null
    private var uiTickJob: Job? = null
    private var uiVisible = false

    init {
        sensorManager.worldAccelListener = WorldAccelListener { east, north, ts ->
            fusion.predict(east.toDouble(), north.toDouble(), ts)
        }
        sensorManager.rawListener = imuLogger
        locationManager.fixListener = { gps -> fusion.update(gps) }
    }

    // ================================================================ Ciclo de vida

    fun onUiVisible() {
        uiVisible = true
        startInputs()
        startUiTicker()
    }

    fun onUiHidden() {
        uiVisible = false
        if (!_live.value.isSessionActive) {
            uiTickJob?.cancel()
            uiTickJob = null
            stopInputs()
        }
    }

    fun onLocationPermissionGranted() {
        locationManager.start()
    }

    private fun startInputs() {
        sensorManager.start()
        locationManager.start()
    }

    private fun stopInputs() {
        sensorManager.stop()
        locationManager.stop()
        fusion.reset()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, TelemetryRecordingService::class.java))
        } catch (e: Exception) {
            // Ex.: ForegroundServiceStartNotAllowedException. A corrida segue com a tela ligada.
            Log.e(TAG, "Não foi possível iniciar o serviço de gravação", e)
            postMessage("Aviso: gravação com tela apagada indisponível (${e.javaClass.simpleName}).")
        }
    }

    private fun stopService() {
        try {
            app.stopService(Intent(app, TelemetryRecordingService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService falhou", e)
        }
    }

    // ================================================================ Controles

    fun toggleRace() {
        val st = _live.value
        if (st.isSaving) return
        when (st.status) {
            RaceStatus.IDLE -> {
                resetSessionData()
                sessionStartEpochMs = System.currentTimeMillis()
                sensorManager.resetBarometricBaseline()
                imuLogger.begin(sessionStartEpochMs)
                startInputs()
                resumeRunning()
                startService()
            }
            RaceStatus.RUNNING -> pauseRunning()
            RaceStatus.PAUSED -> resumeRunning()
        }
    }

    fun newLap() {
        if (_live.value.status != RaceStatus.RUNNING) return
        val elapsed = currentElapsedMs()
        if (elapsed - lapStartElapsedMs < MIN_LAP_MS) {
            postMessage("Volta ignorada (menos de ${MIN_LAP_MS / 1000} s).")
            return
        }
        val lap = buildLap(lapNumber, lapStartElapsedMs, elapsed, lapStartPointIndex)
        completedLaps.add(lap)
        lapNumber++
        lapStartElapsedMs = elapsed
        lapStartPointIndex = recordedPoints.size
        _live.update {
            it.copy(
                lapNumber = lapNumber,
                completedLaps = completedLaps.toList(),
                lastLapMs = lap.durationMs,
                bestLapMs = completedLaps.minOf { l -> l.durationMs },
            )
        }
        refreshLive()
    }

    fun resetSession() {
        if (_live.value.isSaving) return
        imuLogger.discard()
        pendingImuFile?.delete()
        pendingImuFile = null
        resetSessionData()
        stopService()
        if (!uiVisible) stopInputs()
        postMessage("Sessão zerada.")
    }

    fun calibrateInclination() {
        sensorManager.calibrateInclination()
        postMessage("Inclinação zerada na posição atual.")
    }

    fun saveAndExport() {
        val st = _live.value
        if (st.isSaving) return
        if (recordedPoints.isEmpty()) {
            postMessage("Nada para salvar: inicie uma corrida primeiro.")
            return
        }
        if (st.status == RaceStatus.RUNNING) pauseRunning()

        val elapsed = currentElapsedMs()
        val laps = ArrayList(completedLaps)
        if (lapStartPointIndex < recordedPoints.size && elapsed > lapStartElapsedMs) {
            laps.add(buildLap(lapNumber, lapStartElapsedMs, elapsed, lapStartPointIndex))
        }
        val points = ArrayList(recordedPoints)
        val session = RaceSession(
            name = "Corrida " + SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(sessionStartEpochMs)),
            startedAtEpochMs = sessionStartEpochMs,
            durationMs = elapsed,
            distanceMeters = distanceMeters,
            avgSpeedKmh = if (speedSamples > 0) (speedSum / speedSamples).toFloat() else 0f,
            maxSpeedKmh = maxSpeedKmh ?: 0f,
            lapCount = laps.size,
            bestLapMs = completedLaps.minOfOrNull { it.durationMs },
            pointCount = points.size,
        )
        val imuFile = imuLogger.finish() ?: pendingImuFile
        pendingImuFile = imuFile

        _live.update { it.copy(isSaving = true) }
        scope.launch {
            try {
                val result = repository.saveRace(session, laps, points, imuFile)
                pendingImuFile = null
                resetSessionData()
                stopService()
                if (!uiVisible) {
                    uiTickJob?.cancel()
                    uiTickJob = null
                    stopInputs()
                }
                val text = buildString {
                    append("Corrida #${result.session.id} salva (${points.size} pontos")
                    if (result.session.imuFileName != null) append(" + IMU 100 Hz")
                    append(")")
                    when {
                        result.exportFailed -> append(" — exportação CSV falhou")
                        result.publicLocation != null -> append(" em ${result.publicLocation}")
                    }
                }
                // Pacote .ecooctano.zip (para outro aparelho) + CSV (planilha).
                postMessage(text, result.files.filter { it.name.endsWith(".ecooctano.zip") || it.name.endsWith(".csv") && !it.name.endsWith("_imu.csv") })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao salvar corrida", e)
                _live.update { it.copy(isSaving = false) }
                postMessage("Falha ao salvar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ================================================================ Mensagens

    private fun postMessage(text: String, shareFiles: List<File> = emptyList()) = events.post(text, shareFiles)

    // ================================================================ Internos

    private fun resumeRunning() {
        runStartedRealtime = SystemClock.elapsedRealtime()
        lastDistanceSample = null // não contabiliza deslocamento feito durante a pausa
        _live.update { it.copy(status = RaceStatus.RUNNING) }
        imuLogger.resume(accumulatedMs, runStartedRealtime)
        startRecordingLoop()
        startUiTicker()
    }

    private fun pauseRunning() {
        accumulatedMs = currentElapsedMs() // calculado ainda com status RUNNING
        recordJob?.cancel()
        recordJob = null
        imuLogger.pause()
        _live.update { it.copy(status = RaceStatus.PAUSED) }
        refreshLive()
    }

    private fun currentElapsedMs(): Long =
        if (_live.value.status == RaceStatus.RUNNING) {
            accumulatedMs + (SystemClock.elapsedRealtime() - runStartedRealtime)
        } else {
            accumulatedMs
        }

    private fun isGpsFresh(gps: GpsReading?): Boolean =
        gps != null && SystemClock.elapsedRealtime() - gps.receivedAtRealtimeMs <= GPS_STALE_MS

    /** Estimativa fundida atual, ou null se o GPS não estiver recente. */
    private fun currentFused(): FusedState? {
        if (!isGpsFresh(locationManager.location.value)) return null
        return fusion.snapshot(SystemClock.elapsedRealtimeNanos())
    }

    private fun displaySpeed(fused: FusedState?): Float? =
        fused?.speedKmh?.let { if (it < DISPLAY_DEADBAND_KMH) 0f else it }

    private fun startUiTicker() {
        if (uiTickJob?.isActive == true) return
        uiTickJob = scope.launch {
            while (isActive) {
                refreshLive()
                delay(UI_TICK_MS)
            }
        }
    }

    private fun refreshLive() {
        val elapsed = currentElapsedMs()
        val gps = locationManager.location.value
        val fused = currentFused()
        _live.update {
            it.copy(
                elapsedMs = elapsed,
                lapElapsedMs = (elapsed - lapStartElapsedMs).coerceAtLeast(0L),
                gpsFresh = isGpsFresh(gps),
                speedKmh = displaySpeed(fused),
                latitude = fused?.latitude ?: gps?.latitude,
                longitude = fused?.longitude ?: gps?.longitude,
            )
        }
    }

    /** Gravação a 5 Hz com agendamento absoluto (sem deriva acumulada). */
    private fun startRecordingLoop() {
        recordJob?.cancel()
        recordJob = scope.launch {
            var nextTick = SystemClock.elapsedRealtime()
            while (isActive) {
                try {
                    recordSample()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Amostra descartada", e)
                }
                nextTick += SAMPLE_INTERVAL_MS
                val wait = nextTick - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    delay(wait)
                } else {
                    nextTick = SystemClock.elapsedRealtime()
                    yield()
                }
            }
        }
    }

    private fun recordSample() {
        val nowRealtime = SystemClock.elapsedRealtime()
        val elapsed = currentElapsedMs()
        val s = sensorManager.readings.value
        val gps = locationManager.location.value?.takeIf { isGpsFresh(it) }
        val fused = if (gps != null) fusion.snapshot(SystemClock.elapsedRealtimeNanos()) else null
        val speed = displaySpeed(fused)

        recordedPoints.add(
            TelemetryPoint(
                timestampEpochMs = System.currentTimeMillis(),
                elapsedMs = elapsed,
                lapNumber = lapNumber,
                latitude = fused?.latitude,
                longitude = fused?.longitude,
                gpsAltitudeM = gps?.altitudeM,
                gpsAccuracyM = gps?.accuracyM,
                speedKmh = speed,
                accelX = s.accelX,
                accelY = s.accelY,
                accelZ = s.accelZ,
                gForce = s.gForce,
                pressureHpa = s.pressureHpa,
                baroAltitudeM = s.baroAltitudeM,
                pitchDeg = s.pitchDeg,
                rollDeg = s.rollDeg,
                temperatureC = s.temperatureC,
                gpsLatitudeRaw = gps?.latitude,
                gpsLongitudeRaw = gps?.longitude,
                gpsSpeedRawKmh = gps?.speedKmh,
            ),
        )

        if (speed != null) {
            speedSum += speed
            speedSamples++
            maxSpeedKmh = maxOf(maxSpeedKmh ?: 0f, speed)
        }
        if (fused != null && gps != null) {
            accumulateDistance(fused, gps, nowRealtime)
            appendTrack(fused, speed ?: 0f)
        }

        _live.update {
            it.copy(
                pointCount = recordedPoints.size,
                avgSpeedKmh = if (speedSamples > 0) (speedSum / speedSamples).toFloat() else null,
                maxSpeedKmh = maxSpeedKmh,
                distanceMeters = distanceMeters,
            )
        }
    }

    private fun appendTrack(fused: FusedState, speedKmh: Float) {
        val last = trackPoints.lastOrNull()
        if (last != null && metersBetween(last.latitude, last.longitude, fused.latitude, fused.longitude) < MIN_TRACK_STEP_M) {
            return
        }
        trackPoints.add(TrackPoint(fused.latitude, fused.longitude, speedKmh))
        _track.value = TrackSnapshot(trackPoints, trackPoints.size)
    }

    private fun accumulateDistance(fused: FusedState, gps: GpsReading, nowRealtime: Long) {
        val acc = gps.accuracyM
        if (acc != null && acc > MAX_DISTANCE_ACCURACY_M) {
            lastDistanceSample = null
            return
        }
        val prev = lastDistanceSample
        if (prev != null) {
            val d = metersBetween(prev.latitude, prev.longitude, fused.latitude, fused.longitude).toDouble()
            val dtS = (nowRealtime - lastDistanceRealtime) / 1000.0
            if (d.isFinite() && dtS > 0.0 && d / dtS < MAX_PLAUSIBLE_SPEED_MS) distanceMeters += d
        }
        lastDistanceSample = fused
        lastDistanceRealtime = nowRealtime
    }

    private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    private fun buildLap(number: Int, startMs: Long, endMs: Long, fromIndex: Int): LapData {
        var sum = 0.0
        var n = 0
        var max = 0f
        for (i in fromIndex until recordedPoints.size) {
            val sp = recordedPoints[i].speedKmh ?: continue
            sum += sp
            n++
            if (sp > max) max = sp
        }
        return LapData(
            lapNumber = number,
            startOffsetMs = startMs,
            endOffsetMs = endMs,
            durationMs = endMs - startMs,
            avgSpeedKmh = if (n > 0) (sum / n).toFloat() else 0f,
            maxSpeedKmh = max,
        )
    }

    private fun resetSessionData() {
        recordJob?.cancel()
        recordJob = null
        recordedPoints = ArrayList()
        trackPoints = ArrayList() // nova instância => o mapa limpa o traçado
        completedLaps.clear()
        accumulatedMs = 0L
        runStartedRealtime = 0L
        lapStartElapsedMs = 0L
        lapStartPointIndex = 0
        lapNumber = 1
        distanceMeters = 0.0
        lastDistanceSample = null
        lastDistanceRealtime = 0L
        speedSum = 0.0
        speedSamples = 0
        maxSpeedKmh = null
        _track.value = TrackSnapshot(trackPoints, 0)
        _live.value = LiveSessionState()
        refreshLive()
    }
}
