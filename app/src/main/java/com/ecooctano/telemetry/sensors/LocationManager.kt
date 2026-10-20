package com.ecooctano.telemetry.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GpsStatus { NO_PERMISSION, SEARCHING, ACTIVE, SIGNAL_LOST, UNAVAILABLE }

data class GpsReading(
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    /** Velocidade exibível (km/h), com zona morta de parado. */
    val speedKmh: Float,
    /** Velocidade bruta do GNSS em m/s (sem zona morta), null se o fix não trouxer. */
    val rawSpeedMs: Float?,
    val speedAccuracyMs: Float?,
    val bearingDeg: Float?,
    /** Horário UTC do fix (Location.time). */
    val fixTimeMs: Long,
    /** SystemClock.elapsedRealtime() no recebimento — usado para detectar dados velhos. */
    val receivedAtRealtimeMs: Long,
    /** Location.elapsedRealtimeNanos: mesma base de tempo dos SensorEvent. */
    val elapsedRealtimeNanos: Long,
)

/**
 * Wrapper do FusedLocationProviderClient em alta precisão a 5 Hz (200 ms).
 * Nunca lança exceção para fora: falhas viram [GpsStatus].
 */
class LocationManager(context: Context) {

    companion object {
        const val UPDATE_INTERVAL_MS = 200L
        private const val TAG = "EcoOctanoLocation"
        /** Abaixo disso consideramos parado (ruído do GNSS). */
        private const val STATIONARY_THRESHOLD_KMH = 0.5f
    }

    private val appContext = context.applicationContext

    private val client: FusedLocationProviderClient? = try {
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (code == ConnectionResult.SUCCESS) LocationServices.getFusedLocationProviderClient(appContext) else null
    } catch (t: Throwable) {
        Log.e(TAG, "Play Services indisponível", t)
        null
    }

    private val request: LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()

    private val _location = MutableStateFlow<GpsReading?>(null)
    val location: StateFlow<GpsReading?> = _location.asStateFlow()

    private val _status = MutableStateFlow(if (client == null) GpsStatus.UNAVAILABLE else GpsStatus.SEARCHING)
    val status: StateFlow<GpsStatus> = _status.asStateFlow()

    /** Chamado na Main thread a cada fix válido (ex.: filtro de Kalman). */
    @Volatile
    var fixListener: ((GpsReading) -> Unit)? = null

    private var updatesActive = false
    private var previousLocation: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            try {
                onNewLocation(loc)
            } catch (t: Throwable) {
                Log.w(TAG, "Fix descartado", t)
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable && _status.value == GpsStatus.ACTIVE) {
                _status.value = GpsStatus.SIGNAL_LOST
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // verificado em hasLocationPermission()
    fun start() {
        if (updatesActive) return
        if (!hasLocationPermission()) {
            _status.value = GpsStatus.NO_PERMISSION
            return
        }
        val c = client
        if (c == null) {
            _status.value = GpsStatus.UNAVAILABLE
            return
        }
        try {
            c.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { e ->
                    Log.e(TAG, "requestLocationUpdates falhou", e)
                    updatesActive = false
                    _status.value = GpsStatus.UNAVAILABLE
                }
            updatesActive = true
            if (_status.value != GpsStatus.ACTIVE) _status.value = GpsStatus.SEARCHING
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão revogada", e)
            _status.value = GpsStatus.NO_PERMISSION
        } catch (t: Throwable) {
            Log.e(TAG, "Erro ao iniciar GPS", t)
            _status.value = GpsStatus.UNAVAILABLE
        }
    }

    fun stop() {
        if (!updatesActive) return
        try {
            client?.removeLocationUpdates(callback)
        } catch (t: Throwable) {
            Log.w(TAG, "Erro ao parar GPS", t)
        }
        updatesActive = false
        previousLocation = null
    }

    private fun onNewLocation(loc: Location) {
        val lat = loc.latitude
        val lon = loc.longitude
        if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) return

        val prev = previousLocation
        var speedMs: Float = when {
            loc.hasSpeed() && loc.speed.isFinite() -> loc.speed
            prev != null -> {
                val dt = (loc.time - prev.time) / 1000f
                if (dt > 0f) prev.distanceTo(loc) / dt else 0f
            }
            else -> 0f
        }
        if (!speedMs.isFinite() || speedMs < 0f) speedMs = 0f
        var speedKmh = speedMs * 3.6f
        if (speedKmh < STATIONARY_THRESHOLD_KMH) speedKmh = 0f

        previousLocation = Location(loc)
        val reading = GpsReading(
            latitude = lat,
            longitude = lon,
            altitudeM = if (loc.hasAltitude()) loc.altitude.takeIf { it.isFinite() } else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy.takeIf { it.isFinite() } else null,
            speedKmh = speedKmh,
            rawSpeedMs = if (loc.hasSpeed() && loc.speed.isFinite()) loc.speed else null,
            speedAccuracyMs = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.takeIf { it.isFinite() } else null,
            bearingDeg = if (loc.hasBearing()) loc.bearing.takeIf { it.isFinite() } else null,
            fixTimeMs = loc.time,
            receivedAtRealtimeMs = SystemClock.elapsedRealtime(),
            elapsedRealtimeNanos = loc.elapsedRealtimeNanos.takeIf { it > 0L } ?: SystemClock.elapsedRealtimeNanos(),
        )
        _location.value = reading
        _status.value = GpsStatus.ACTIVE
        try {
            fixListener?.invoke(reading)
        } catch (t: Throwable) {
            Log.w(TAG, "fixListener falhou", t)
        }
    }
}
