package com.ecooctano.telemetry.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ecooctano.telemetry.EcoOctanoApp
import com.ecooctano.telemetry.MainActivity
import com.ecooctano.telemetry.R
import com.ecooctano.telemetry.ui.components.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (tipo "location") que mantém GPS, sensores e a gravação a 5 Hz
 * ativos com a tela apagada. Ele não grava nada: só mantém o processo em primeiro plano,
 * segura um wake lock parcial (CPU acordada) e mostra a notificação com o status.
 * Quem grava é o [RaceRecorder].
 */
class TelemetryRecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "race_recording"
        private const val NOTIFICATION_ID = 42
        /** Limite de segurança do wake lock (renovado a cada início de serviço). */
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Iniciando gravação…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startForeground falhou", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        startNotificationUpdates()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startNotificationUpdates() {
        if (notificationJob?.isActive == true) return
        val recorder = (application as EcoOctanoApp).recorder
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationJob = scope.launch {
            while (isActive) {
                val st = recorder.live.value
                val text = when (st.status) {
                    RaceStatus.RUNNING ->
                        "Gravando · ${Fmt.elapsed(st.elapsedMs)} · ${Fmt.num(st.speedKmh, 1)} km/h · ${Fmt.distance(st.distanceMeters)}"
                    RaceStatus.PAUSED -> "Pausado · ${Fmt.elapsed(st.elapsedMs)} · ${st.pointCount} pontos"
                    RaceStatus.IDLE -> "Sessão encerrada"
                }
                try {
                    manager?.notify(NOTIFICATION_ID, buildNotification(text))
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao atualizar notificação", e)
                }
                delay(1_000L)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("Eco Octano Telemetry")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Gravação da corrida", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Mostra que a telemetria está sendo gravada"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EcoOctano:recording").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Wake lock indisponível", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao liberar wake lock", e)
        }
        wakeLock = null
    }
}
