package com.ecooctano.telemetry.ui.analysis

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecooctano.telemetry.EcoOctanoApp
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TelemetryPoint
import com.ecooctano.telemetry.data.model.TrackPoint
import com.ecooctano.telemetry.data.model.TrackSnapshot
import com.ecooctano.telemetry.data.transfer.ParsedSession
import com.ecooctano.telemetry.data.transfer.SessionPackageReader
import com.ecooctano.telemetry.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.withContext
import java.io.File

/** Séries prontas para os gráficos (NaN = sem dado). Calculadas uma vez por corrida. */
class SessionSeries(
    val timesMs: LongArray,
    val speedFused: FloatArray,
    val speedGpsRaw: FloatArray,
    val gForce: FloatArray,
    val accelX: FloatArray,
    val accelY: FloatArray,
    val accelZ: FloatArray,
    val baroAltitude: FloatArray,
    val pitch: FloatArray,
    val roll: FloatArray,
    val temperature: FloatArray,
    val gpsAccuracy: FloatArray,
) {
    companion object {
        val EMPTY = from(emptyList())

        fun from(points: List<TelemetryPoint>): SessionSeries {
            val n = points.size
            fun f(sel: (TelemetryPoint) -> Float?) = FloatArray(n) { i -> sel(points[i]) ?: Float.NaN }
            return SessionSeries(
                timesMs = LongArray(n) { points[it].elapsedMs },
                speedFused = f { it.speedKmh },
                speedGpsRaw = f { it.gpsSpeedRawKmh },
                gForce = f { it.gForce },
                accelX = f { it.accelX },
                accelY = f { it.accelY },
                accelZ = f { it.accelZ },
                baroAltitude = f { it.baroAltitudeM },
                pitch = f { it.pitchDeg },
                roll = f { it.rollDeg },
                temperature = f { it.temperatureC },
                gpsAccuracy = f { it.gpsAccuracyM },
            )
        }
    }
}

data class AnalysisState(
    val isLoading: Boolean = false,
    val session: RaceSession? = null,
    val points: List<TelemetryPoint> = emptyList(),
    val laps: List<LapData> = emptyList(),
    val series: SessionSeries = SessionSeries.EMPTY,
    val track: TrackSnapshot = TrackSnapshot.EMPTY,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val positionMs: Long = 0L,
    val currentIndex: Int = -1,
    /** Último ponto com GPS válido até a posição atual (pino no mapa). */
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

/** ViewModel da versão ANÁLISE: corridas importadas/salvas, replay sincronizado e gráficos. */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AnalysisViewModel"
        private const val FRAME_MS = 33L
        val PLAYBACK_SPEEDS = listOf(1f, 2f, 5f, 10f)
    }

    private val app = application as EcoOctanoApp
    private val repository = app.repository
    private val events = app.events
    private val reader = SessionPackageReader(File(application.cacheDir, "import"))

    val message: StateFlow<UiMessage?> = events.message
    val shareRequests: SharedFlow<List<File>> = events.shareRequests

    val sessions: StateFlow<List<RaceSession>> = repository.observeSessions()
        .catch { e ->
            Log.e(TAG, "Falha ao ler sessões", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private var playbackJob: Job? = null
    private var lastGpsIndex = IntArray(0)
    private var firstGpsIndex = -1

    fun consumeMessage(id: Long) = events.consume(id)

    // =========================================================== Importação

    /** Importa pacotes .ecooctano.zip ou .json (seletor de arquivos ou "Abrir com"). */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = true
            val resolver = app.contentResolver
            val parsed = ArrayList<ParsedSession>()
            var failed = 0
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        val stream = resolver.openInputStream(uri) ?: throw java.io.IOException("Arquivo inacessível")
                        stream.use { parsed.addAll(reader.read(it)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Importação falhou para $uri", e)
                        failed++
                    }
                }
            }
            try {
                val summary = repository.importSessions(parsed)
                val text = buildString {
                    append("${summary.imported} corrida(s) importada(s)")
                    if (summary.duplicates > 0) append(", ${summary.duplicates} já existia(m)")
                    if (failed > 0) append(", $failed arquivo(s) inválido(s)")
                }
                events.post(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao gravar importação", e)
                events.post("Falha ao importar: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _importing.value = false
            }
        }
    }

    fun sharePackage(sessionId: Long?) = launchSafely("Falha ao gerar pacote") {
        val file = repository.exportPackage(sessionId?.let { listOf(it) })
        if (file == null) events.post("Nenhuma corrida para compartilhar.") else events.requestShare(listOf(file))
    }

    fun shareCsv(sessionId: Long) = launchSafely("Falha ao exportar CSV") {
        val files = repository.exportSession(sessionId).filter { it.name.endsWith(".csv") }
        if (files.isEmpty()) events.post("Corrida não encontrada.") else events.requestShare(files)
    }

    fun deleteSession(sessionId: Long) = launchSafely("Falha ao excluir corrida") {
        repository.deleteSession(sessionId)
        if (_state.value.session?.id == sessionId) closeSession()
        events.post("Corrida excluída.")
    }

    // =========================================================== Replay

    fun openSession(sessionId: Long) {
        if (_state.value.session?.id == sessionId) return
        pausePlayback()
        _state.value = AnalysisState(isLoading = true)
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
                _state.value = AnalysisState()
                events.post("Não foi possível abrir a corrida (sem dados).")
                return@launch
            }
            val prepared = withContext(Dispatchers.Default) { prepare(loaded.points) }
            lastGpsIndex = prepared.lastGps
            firstGpsIndex = prepared.firstGps
            val pts = loaded.points
            val start = pts.first().elapsedMs
            _state.value = AnalysisState(
                session = loaded.session,
                points = pts,
                laps = loaded.laps,
                series = prepared.series,
                track = TrackSnapshot(prepared.track, prepared.track.size),
                startMs = start,
                endMs = maxOf(start, pts.last().elapsedMs),
                positionMs = start,
                currentIndex = 0,
                markerIndex = if (prepared.lastGps[0] >= 0) prepared.lastGps[0] else prepared.firstGps,
            )
        }
    }

    private class Prepared(
        val series: SessionSeries,
        val track: ArrayList<TrackPoint>,
        val lastGps: IntArray,
        val firstGps: Int,
    )

    private fun prepare(pts: List<TelemetryPoint>): Prepared {
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
        return Prepared(SessionSeries.from(pts), track, lastGps, first)
    }

    fun closeSession() {
        pausePlayback()
        lastGpsIndex = IntArray(0)
        firstGpsIndex = -1
        _state.value = AnalysisState()
    }

    fun seekTo(positionMs: Long) {
        pausePlayback()
        applyPosition(positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        if (speed <= 0f || !speed.isFinite()) return
        _state.update { it.copy(playbackSpeed = speed) }
    }

    fun togglePlayback() {
        val st = _state.value
        if (st.points.isEmpty()) return
        if (st.isPlaying) {
            pausePlayback()
            return
        }
        if (st.positionMs >= st.endMs) applyPosition(st.startMs)
        _state.update { it.copy(isPlaying = true) }
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var last = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(FRAME_MS)
                val now = SystemClock.elapsedRealtime()
                val dt = now - last
                last = now
                val cur = _state.value
                if (!cur.isPlaying || cur.points.isEmpty()) break
                val next = cur.positionMs + (dt * cur.playbackSpeed).toLong()
                applyPosition(next)
                if (next >= cur.endMs) {
                    _state.update { it.copy(isPlaying = false) }
                    break
                }
            }
        }
    }

    fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        if (_state.value.isPlaying) _state.update { it.copy(isPlaying = false) }
    }

    private fun applyPosition(positionMs: Long) {
        val st = _state.value
        if (st.points.isEmpty()) return
        val clamped = positionMs.coerceIn(st.startMs, st.endMs)
        val idx = indexAtOrBefore(st.points, clamped)
        val gpsIdx = lastGpsIndex.getOrElse(idx) { -1 }
        _state.value = st.copy(
            positionMs = clamped,
            currentIndex = idx,
            markerIndex = if (gpsIdx >= 0) gpsIdx else firstGpsIndex,
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

    private fun launchSafely(errorText: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, errorText, e)
                events.post("$errorText: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
