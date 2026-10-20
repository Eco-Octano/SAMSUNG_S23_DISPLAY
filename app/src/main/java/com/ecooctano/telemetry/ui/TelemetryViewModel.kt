package com.ecooctano.telemetry.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecooctano.telemetry.EcoOctanoApp
import com.ecooctano.telemetry.data.TelemetryRepository
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import com.ecooctano.telemetry.data.model.TrackPoint
import com.ecooctano.telemetry.data.model.TrackSnapshot
import com.ecooctano.telemetry.recording.LiveSessionState
import com.ecooctano.telemetry.recording.RaceRecorder
import com.ecooctano.telemetry.recording.UiMessage
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.sensors.SensorReadings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

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

/**
 * ViewModel da UI. O modo ao vivo é delegado ao [RaceRecorder] (nível do Application),
 * que continua gravando mesmo sem tela; aqui fica apenas o estado do replay.
 */
class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TelemetryViewModel"
        private const val REPLAY_FRAME_MS = 33L
        val PLAYBACK_SPEEDS = listOf(1f, 2f, 5f)
    }

    private val app = application as EcoOctanoApp
    private val recorder: RaceRecorder = app.recorder
    private val repository: TelemetryRepository = app.repository

    val sensorReadings: StateFlow<SensorReadings> = recorder.sensorReadings
    val gpsReading: StateFlow<GpsReading?> = recorder.gpsReading
    val gpsStatus: StateFlow<GpsStatus> = recorder.gpsStatus
    val live: StateFlow<LiveSessionState> = recorder.live
    val track: StateFlow<TrackSnapshot> = recorder.track
    val message: StateFlow<UiMessage?> = recorder.message
    val shareRequests: SharedFlow<List<File>> = recorder.shareRequests

    val sessions: StateFlow<List<RaceSession>> = repository.observeSessions()
        .catch { e ->
            Log.e(TAG, "Falha ao ler sessões", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _replay = MutableStateFlow(ReplayState())
    val replay: StateFlow<ReplayState> = _replay.asStateFlow()

    private var playbackJob: Job? = null
    private var replayLastGpsIndex = IntArray(0)
    private var replayFirstGpsIndex = -1

    // =========================================================== Ao vivo (delegado)

    fun onAppForeground() = recorder.onUiVisible()

    fun onAppBackground() {
        pausePlayback()
        recorder.onUiHidden()
    }

    fun onLocationPermissionGranted() = recorder.onLocationPermissionGranted()

    fun toggleRace() = recorder.toggleRace()

    fun newLap() = recorder.newLap()

    fun resetSession() = recorder.resetSession()

    fun calibrateInclination() = recorder.calibrateInclination()

    fun saveAndExport() = recorder.saveAndExport()

    fun consumeMessage(id: Long) = recorder.consumeMessage(id)

    private fun postMessage(text: String) = recorder.postMessage(text)

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
                if (files.isEmpty()) postMessage("Corrida não encontrada.") else recorder.requestShare(files)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao exportar", e)
                postMessage("Falha ao exportar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
