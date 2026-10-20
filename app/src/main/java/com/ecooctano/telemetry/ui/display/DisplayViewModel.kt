package com.ecooctano.telemetry.ui.display

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ecooctano.telemetry.EcoOctanoApp
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.data.model.TrackSnapshot
import com.ecooctano.telemetry.recording.LiveSessionState
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.ui.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** ViewModel da versão DISPLAY: delega a corrida ao RaceRecorder e gerencia as corridas salvas. */
class DisplayViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "DisplayViewModel"
    }

    private val app = application as EcoOctanoApp
    private val recorder = app.recorder
    private val repository = app.repository
    private val events = app.events

    val gpsReading: StateFlow<GpsReading?> = recorder.gpsReading
    val gpsStatus: StateFlow<GpsStatus> = recorder.gpsStatus
    val live: StateFlow<LiveSessionState> = recorder.live
    val track: StateFlow<TrackSnapshot> = recorder.track
    val message: StateFlow<UiMessage?> = events.message
    val shareRequests: SharedFlow<List<File>> = events.shareRequests

    val sessions: StateFlow<List<RaceSession>> = repository.observeSessions()
        .catch { e ->
            Log.e(TAG, "Falha ao ler sessões", e)
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onAppForeground() = recorder.onUiVisible()
    fun onAppBackground() = recorder.onUiHidden()
    fun onLocationPermissionGranted() = recorder.onLocationPermissionGranted()
    fun toggleRace() = recorder.toggleRace()
    fun newLap() = recorder.newLap()
    fun resetSession() = recorder.resetSession()
    fun calibrateInclination() = recorder.calibrateInclination()
    fun saveAndExport() = recorder.saveAndExport()
    fun consumeMessage(id: Long) = events.consume(id)

    /** Pacote .ecooctano.zip de uma corrida (ou de todas, com null) para outro aparelho. */
    fun sharePackage(sessionId: Long?) = launchSafely("Falha ao gerar pacote") {
        val file = repository.exportPackage(sessionId?.let { listOf(it) })
        if (file == null) events.post("Nenhuma corrida para compartilhar.") else events.requestShare(listOf(file))
    }

    /** CSV de 5 Hz de uma corrida (planilha). */
    fun shareCsv(sessionId: Long) = launchSafely("Falha ao exportar CSV") {
        val files = repository.exportSession(sessionId)
            .filter { it.name.endsWith(".csv") && !it.name.endsWith("_imu.csv") }
        if (files.isEmpty()) events.post("Corrida não encontrada.") else events.requestShare(files)
    }

    fun deleteSession(sessionId: Long) = launchSafely("Falha ao excluir corrida") {
        repository.deleteSession(sessionId)
        events.post("Corrida excluída.")
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
