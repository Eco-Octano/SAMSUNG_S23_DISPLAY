package com.ecooctano.telemetry.ui

import android.app.Application
import android.location.Location
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecooctano.telemetry.data.TelemetryRepository
import com.ecooctano.telemetry.data.db.AppDatabase
import com.ecooctano.telemetry.data.export.TelemetryExporter
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import com.ecooctano.telemetry.data.model.TrackPoint
import com.ecooctano.telemetry.data.model.TrackSnapshot
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.sensors.LocationManager
import com.ecooctano.telemetry.sensors.SensorReadings
import com.ecooctano.telemetry.sensors.SensorTelemetryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
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
) {
    val isRecording: Boolean get() = status == RaceStatus.RUNNING
}

data class UiMessage(
    val id: Long,
    val text: String,
    val shareFiles: List<File> = emptyList(),
)

data class ReplayState(
    val isLoading: Boolean = false,
    val session: RaceSession? = null,
    val points: List<TelemetryPoint> = emptyList(),
    val laps: List<LapData> = emptyList(),
    val track: TrackSnapshot = TrackSnapshot.EMPTY,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val positionMs: Long = 0L,
    val currentIndex: Int = -1,
    /** Índice do último ponto com GPS válido até a posição atual (para o pino no mapa). */
    val markerIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1f,
) {
    val currentPoint: TelemetryPoint? get() = points.getOrNull(currentIndex)
    val markerPoint: TelemetryPoint? get() = points.getOrNull(markerIndex)
    val currentLap: LapData?
        get() {
            val p = currentPoint ?: return null
            return laps.firstOrNull { it.lapNumber == p.lapNumber }
        }
}

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TelemetryViewModel"
        /** 5 Hz */
        const val SAMPLE_INTERVAL_MS = 200L
        private const val UI_TICK_MS = 33L
        private const val GPS_STALE_MS = 2_500L
        /** Evita voltas fantasmas por toque duplo. */
        private const val MIN_LAP_MS = 2_000L
        private const val MAX_DISTANCE_ACCURACY_M = 30f
        /** ~200 km/h: acima disso o salto entre fixes é ruído de multipath. */
        private const val MAX_PLAUSIBLE_SPEED_MS = 55f
        private const val REPLAY_FRAME_MS = 33L
        val PLAYBACK_SPEEDS = listOf(1f, 2f, 5f)
    }

    private val sensorManager = SensorTelemetryManager(application)
    private val locationManager = LocationManager(application)
    private val repository = TelemetryRepository(
        AppDatabase.get(application),
        TelemetryExporter(application),
    )

    val sensorReadings: StateFlow<SensorReadings> = sensorManager.readings
    val gpsReading: StateFlow<GpsReading?> = locationManager.location
    val gpsStatus: StateFlow<GpsStatus> = locationManager.status

    private val _live = MutableStateFlow(LiveSessionState())
    val live: StateFlow<LiveSessionState> = _live.asStateFlow()

    private val _track = MutableStateFlow(TrackSnapshot.EMPTY)
    val track: StateFlow<TrackSnapshot> = _track.asStateFlow()

    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _shareRequests = MutableSharedFlow<List<File>>(extraBufferCapacity = 4)
    val shareRequests: SharedFlow<List<File>> = _shareRequests.asSharedFlow()

    val sessions: StateFlow<List<RaceSession>> = repository.observeSessions()
        .catch { e ->
            Log.e(TAG, "Falha ao ler sessões", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _replay = MutableStateFlow(ReplayState())
    val replay: StateFlow<ReplayState> = _replay.asStateFlow()

    // ---- Estado interno da corrida (acessado somente na Main thread) ----
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
    private var lastDistanceFix: GpsReading? = null
    private var lastTrackFixTime = Long.MIN_VALUE
    private var speedSum = 0.0
    private var speedSamples = 0
    private var maxSpeedKmh: Float? = null

    private var recordJob: Job? = null
    private var uiTickJob: Job? = null
    private var playbackJob: Job? = null
    private var replayLastGpsIndex = IntArray(0)
    private var replayFirstGpsIndex = -1
    private var messageCounter = 0L

    // =========================================================== Ciclo de vida

    fun onAppForeground() {
        sensorManager.start()
        locationManager.start()
        startUiTicker()
    }

    fun onAppBackground() {
        uiTickJob?.cancel()
        uiTickJob = null
        pausePlayback()
        // Durante a corrida os sensores continuam ativos (melhor esforço).
        if (_live.value.status != RaceStatus.RUNNING) {
            sensorManager.stop()
            locationManager.stop()
        }
    }

    fun onLocationPermissionGranted() {
        locationManager.start()
    }

    override fun onCleared() {
        sensorManager.stop()
        locationManager.stop()
        super.onCleared()
    }

    // =========================================================== Modo ao vivo

    fun toggleRace() {
        val st = _live.value
        if (st.isSaving) return
        when (st.status) {
            RaceStatus.IDLE -> {
                resetSessionData()
                sessionStartEpochMs = System.currentTimeMillis()
                sensorManager.resetBarometricBaseline()
                resumeRunning()
            }
            RaceStatus.RUNNING -> pauseRunning()
            RaceStatus.PAUSED -> resumeRunning()
        }
    }

    fun newLap() {
        if (_live.value.status != RaceStatus.RUNNING) return
        val elapsed = currentElapsedMs()
        val duration = elapsed - lapStartElapsedMs
        if (duration < MIN_LAP_MS) {
            postMessage("Volta ignorada (menos de ${MIN_LAP_MS / 1000} s).")
            return
        }
        val lap = buildLap(lapNumber, lapStartElapsedMs, elapsed, lapStartPointIndex)
        completedLaps.add(lap)
        lapNumber++
        lapStartElapsedMs = elapsed
        lapStartPointIndex = recordedPoints.size
        val best = completedLaps.minOf { it.durationMs }
        _live.update {
            it.copy(
                lapNumber = lapNumber,
                completedLaps = completedLaps.toList(),
                lastLapMs = lap.durationMs,
                bestLapMs = best,
            )
        }
        refreshLiveClock()
    }

    fun resetSession() {
        if (_live.value.isSaving) return
        resetSessionData()
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
        val avg = if (speedSamples > 0) (speedSum / speedSamples).toFloat() else 0f
        val session = RaceSession(
            name = "Corrida " + SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(sessionStartEpochMs)),
            startedAtEpochMs = sessionStartEpochMs,
            durationMs = elapsed,
            distanceMeters = distanceMeters,
            avgSpeedKmh = avg,
            maxSpeedKmh = maxSpeedKmh ?: 0f,
            lapCount = laps.size,
            bestLapMs = completedLaps.minOfOrNull { it.durationMs },
            pointCount = points.size,
        )

        _live.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val result = repository.saveRace(session, laps, points)
                resetSessionData()
                val text = buildString {
                    append("Corrida #${result.session.id} salva (${points.size} pontos)")
                    when {
                        result.exportFailed -> append(" — exportação CSV falhou")
                        result.publicLocation != null -> append(" em ${result.publicLocation}")
                    }
                }
                postMessage(text, result.files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao salvar corrida", e)
                _live.update { it.copy(isSaving = false) }
                postMessage("Falha ao salvar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun resumeRunning() {
        runStartedRealtime = SystemClock.elapsedRealtime()
        lastDistanceFix = null // não contabiliza deslocamento feito durante a pausa
        _live.update { it.copy(status = RaceStatus.RUNNING) }
        startRecordingLoop()
    }

    private fun pauseRunning() {
        accumulatedMs = currentElapsedMs() // calculado ainda com status RUNNING
        recordJob?.cancel()
        recordJob = null
        _live.update { it.copy(status = RaceStatus.PAUSED) }
        refreshLiveClock()
    }

    private fun currentElapsedMs(): Long =
        if (_live.value.status == RaceStatus.RUNNING) {
            accumulatedMs + (SystemClock.elapsedRealtime() - runStartedRealtime)
        } else {
            accumulatedMs
        }

    private fun isGpsFresh(gps: GpsReading?): Boolean =
        gps != null && SystemClock.elapsedRealtime() - gps.receivedAtRealtimeMs <= GPS_STALE_MS

    private fun startUiTicker() {
        if (uiTickJob?.isActive == true) return
        uiTickJob = viewModelScope.launch {
            while (isActive) {
                refreshLiveClock()
                delay(UI_TICK_MS)
            }
        }
    }

    private fun refreshLiveClock() {
        val elapsed = currentElapsedMs()
        val fresh = isGpsFresh(locationManager.location.value)
        _live.update {
            it.copy(
                elapsedMs = elapsed,
                lapElapsedMs = (elapsed - lapStartElapsedMs).coerceAtLeast(0L),
                gpsFresh = fresh,
            )
        }
    }

    /** Laço de gravação a 5 Hz com agendamento absoluto (sem deriva acumulada). */
    private fun startRecordingLoop() {
        recordJob?.cancel()
        recordJob = viewModelScope.launch {
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
        val elapsed = currentElapsedMs()
        val s = sensorManager.readings.value
        val gps = locationManager.location.value?.takeIf { isGpsFresh(it) }
        val speed = gps?.speedKmh

        recordedPoints.add(
            TelemetryPoint(
                timestampEpochMs = System.currentTimeMillis(),
                elapsedMs = elapsed,
                lapNumber = lapNumber,
                latitude = gps?.latitude,
                longitude = gps?.longitude,
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
            ),
        )

        if (speed != null) {
            speedSum += speed
            speedSamples++
            maxSpeedKmh = maxOf(maxSpeedKmh ?: 0f, speed)
        }
        if (gps != null) {
            accumulateDistance(gps)
            appendTrack(gps)
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

    private fun appendTrack(gps: GpsReading) {
        if (gps.fixTimeMs == lastTrackFixTime) return // mesmo fix repetido entre amostras
        lastTrackFixTime = gps.fixTimeMs
        trackPoints.add(TrackPoint(gps.latitude, gps.longitude, gps.speedKmh))
        _track.value = TrackSnapshot(trackPoints, trackPoints.size)
    }

    private fun accumulateDistance(gps: GpsReading) {
        val acc = gps.accuracyM
        if (acc != null && acc > MAX_DISTANCE_ACCURACY_M) return
        val prev = lastDistanceFix
        if (prev != null && prev.fixTimeMs == gps.fixTimeMs) return
        if (prev != null) {
            val results = FloatArray(1)
            Location.distanceBetween(prev.latitude, prev.longitude, gps.latitude, gps.longitude, results)
            val d = results[0]
            val dtS = (gps.fixTimeMs - prev.fixTimeMs) / 1000f
            if (d.isFinite() && dtS > 0f && d / dtS < MAX_PLAUSIBLE_SPEED_MS) distanceMeters += d
        }
        lastDistanceFix = gps
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
        lastDistanceFix = null
        lastTrackFixTime = Long.MIN_VALUE
        speedSum = 0.0
        speedSamples = 0
        maxSpeedKmh = null
        _track.value = TrackSnapshot(trackPoints, 0)
        _live.value = LiveSessionState(gpsFresh = isGpsFresh(locationManager.location.value))
    }

    // =========================================================== Mensagens

    private fun postMessage(text: String, shareFiles: List<File> = emptyList()) {
        messageCounter++
        _message.value = UiMessage(messageCounter, text, shareFiles)
    }

    fun consumeMessage(id: Long) {
        _message.update { if (it?.id == id) null else it }
    }

    // =========================================================== Retrospectiva

    fun openSession(sessionId: Long) {
        pausePlayback()
        _replay.value = ReplayState(isLoading = true)
        viewModelScope.launch {
            val loaded = try {
                repository.loadSession(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir sessão", e)
                null
            }
            if (loaded == null || loaded.points.isEmpty()) {
                _replay.value = ReplayState()
                postMessage("Não foi possível abrir a corrida (sem dados).")
                return@launch
            }

            val pts = loaded.points
            val lastGps = IntArray(pts.size)
            var last = -1
            var first = -1
            val track = ArrayList<TrackPoint>()
            pts.forEachIndexed { i, p ->
                val lat = p.latitude
                val lon = p.longitude
                if (lat != null && lon != null) {
                    last = i
                    if (first < 0) first = i
                    val prev = track.lastOrNull()
                    if (prev == null || prev.latitude != lat || prev.longitude != lon) {
                        track.add(TrackPoint(lat, lon, p.speedKmh ?: 0f))
                    }
                }
                lastGps[i] = last
            }
            replayLastGpsIndex = lastGps
            replayFirstGpsIndex = first

            val start = pts.first().elapsedMs
            _replay.value = ReplayState(
                session = loaded.session,
                points = pts,
                laps = loaded.laps,
                track = TrackSnapshot(track, track.size),
                startMs = start,
                endMs = maxOf(start, pts.last().elapsedMs),
                positionMs = start,
                currentIndex = 0,
                markerIndex = if (lastGps[0] >= 0) lastGps[0] else first,
            )
        }
    }

    fun closeReplay() {
        pausePlayback()
        replayLastGpsIndex = IntArray(0)
        replayFirstGpsIndex = -1
        _replay.value = ReplayState()
    }

    /** Chamado pelo Slider: pausa a reprodução e posiciona no instante exato. */
    fun seekTo(positionMs: Long) {
        pausePlayback()
        applyReplayPosition(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed <= 0f || !speed.isFinite()) return
        _replay.update { it.copy(playbackSpeed = speed) }
    }

    fun togglePlayback() {
        val st = _replay.value
        if (st.points.isEmpty()) return
        if (st.isPlaying) {
            pausePlayback()
            return
        }
        if (st.positionMs >= st.endMs) applyReplayPosition(st.startMs)
        _replay.update { it.copy(isPlaying = true) }
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(REPLAY_FRAME_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = now - last
                last = now
                val cur = _replay.value
                if (!cur.isPlaying || cur.points.isEmpty()) break
                val next = cur.positionMs + (dt * cur.playbackSpeed).toLong()
                applyReplayPosition(next)
                if (next >= cur.endMs) {
                    _replay.update { it.copy(isPlaying = false) }
                    break
                }
            }
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        if (_replay.value.isPlaying) _replay.update { it.copy(isPlaying = false) }
    }

    private fun applyReplayPosition(positionMs: Long) {
        val st = _replay.value
        if (st.points.isEmpty()) return
        val clamped = positionMs.coerceIn(st.startMs, st.endMs)
        val idx = indexAtOrBefore(st.points, clamped)
        val gpsIdx = replayLastGpsIndex.getOrElse(idx) { -1 }
        _replay.value = st.copy(
            positionMs = clamped,
            currentIndex = idx,
            markerIndex = if (gpsIdx >= 0) gpsIdx else replayFirstGpsIndex,
        )
    }

    /** Busca binária: maior índice com elapsedMs <= t. */
    private fun indexAtOrBefore(points: List<TelemetryPoint>, t: Long): Int {
        var lo = 0
        var hi = points.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].elapsedMs <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteSession(sessionId)
                if (_replay.value.session?.id == sessionId) closeReplay()
                postMessage("Corrida excluída.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao excluir", e)
                postMessage("Falha ao excluir corrida.")
            }
        }
    }

    fun shareSession(sessionId: Long) {
        viewModelScope.launch {
            try {
                val files = repository.exportSession(sessionId)
                if (files.isEmpty()) postMessage("Corrida não encontrada.") else _shareRequests.emit(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao exportar", e)
                postMessage("Falha ao exportar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
