package com.ecooctano.telemetry.ui.map

import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ecooctano.telemetry.data.model.TrackPoint
import com.ecooctano.telemetry.data.model.TrackSnapshot
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

private const val FOLLOW_ZOOM = 18.0
private const val INITIAL_ZOOM = 5.0
private const val TILE_SCALE_RATIO = 0.65f
private val INITIAL_CENTER = GeoPoint(-15.7801, -47.9292) // Brasil

/**
 * Mapa OSMDroid embutido no Compose com traçado colorido por velocidade.
 *
 * @param track traçado (somente acréscimos; nova lista = reinício)
 * @param carPosition posição do pino do carro (null = oculto)
 * @param followCar centraliza a câmera no carro a cada atualização
 * @param fitToTrackKey quando não-nulo, enquadra o traçado inteiro uma vez por chave (replay)
 */
@Composable
fun OsmTelemetryMap(
    track: TrackSnapshot,
    carPosition: GeoPoint?,
    followCar: Boolean,
    modifier: Modifier = Modifier,
    fitToTrackKey: Any? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { createTelemetryMapView(context) }
    val renderer = remember(mapView) { SpeedTrackRenderer(mapView) }

    DisposableEffect(lifecycleOwner, mapView) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // dispara ON_RESUME imediatamente se já estiver resumido
        onDispose {
            lifecycle.removeObserver(observer)
            renderer.clear()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            renderer.syncTrack(track)
            renderer.updateCar(carPosition)
            renderer.setFollow(followCar, carPosition)
            if (fitToTrackKey != null) renderer.fitToTrackOnce(fitToTrackKey, track)
            view.invalidate()
        },
    )
}

private fun createTelemetryMapView(context: Context): MapView =
    MapView(context).apply {
        // Tiles do OSM têm 256 px. Escalar pela densidade total do S23 (~2,6x) deixa o mapa
        // borrado; ~1,7x mantém nitidez com rótulos ainda legíveis no painel.
        setTilesScaledToDpi(false)
        setTilesScaleFactor(maxOf(1f, context.resources.displayMetrics.density * TILE_SCALE_RATIO))
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        setMinZoomLevel(3.0)
        setMaxZoomLevel(19.0) // zoom máximo real dos tiles MAPNIK
        controller.setZoom(INITIAL_ZOOM)
        controller.setCenter(INITIAL_CENTER)
        // Tiles invertidos = mapa escuro coerente com o cockpit.
        overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        overlayManager.tilesOverlay.setLoadingBackgroundColor(android.graphics.Color.BLACK)
        overlayManager.tilesOverlay.setLoadingLineColor(android.graphics.Color.DKGRAY)
    }

/**
 * Desenha o traçado de forma incremental: só os pontos novos viram segmentos.
 * Segmentos consecutivos do mesmo "bucket" de cor são agrupados numa única Polyline.
 * Todas as chamadas acontecem na Main thread (AndroidView.update).
 */
internal class SpeedTrackRenderer(private val mapView: MapView) {

    private val density = mapView.resources.displayMetrics.density
    private val segments = ArrayList<Polyline>()
    private var activeLine: Polyline? = null
    private var activeBucket = Int.MIN_VALUE
    private var lastGeoPoint: GeoPoint? = null
    private var source: List<TrackPoint>? = null
    private var drawnCount = 0

    private var zoomedForFollow = false
    private var lastCenter: GeoPoint? = null
    private var fittedKey: Any? = null

    private val carMarker = Marker(mapView).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = createCarIcon(density)
        setOnMarkerClickListener { _, _ -> true } // sem balão de informação
    }
    private var markerAdded = false

    fun syncTrack(snapshot: TrackSnapshot) {
        val points = snapshot.points
        if (points !== source || snapshot.size < drawnCount) {
            clear()
            source = points
        }
        val end = minOf(snapshot.size, points.size)
        if (end <= drawnCount) return
        for (i in drawnCount until end) appendPoint(points[i])
        drawnCount = end
    }

    private fun appendPoint(p: TrackPoint) {
        if (!p.latitude.isFinite() || !p.longitude.isFinite()) return
        val gp = GeoPoint(p.latitude, p.longitude)
        val prev = lastGeoPoint
        lastGeoPoint = gp
        if (prev == null) return

        val bucket = SpeedColors.bucketFor(p.speedKmh)
        val current = activeLine
        if (current != null && bucket == activeBucket) {
            current.addPoint(gp)
            return
        }
        val line = Polyline(mapView).apply {
            outlinePaint.color = SpeedColors.colorForBucket(bucket)
            outlinePaint.strokeWidth = 6f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            setPoints(arrayListOf(prev, gp))
            setOnClickListener { _, _, _ -> true } // sem balão de informação
        }
        // Segmentos ficam nas primeiras posições; o pino do carro permanece por cima.
        mapView.overlays.add(segments.size, line)
        segments.add(line)
        activeLine = line
        activeBucket = bucket
    }

    fun updateCar(position: GeoPoint?) {
        if (position == null) {
            if (markerAdded) {
                mapView.overlays.remove(carMarker)
                markerAdded = false
            }
            return
        }
        carMarker.position = position
        if (!markerAdded) {
            mapView.overlays.add(carMarker)
            markerAdded = true
        }
    }

    fun setFollow(follow: Boolean, position: GeoPoint?) {
        if (!follow) {
            lastCenter = null // ao religar, recentraliza imediatamente
            return
        }
        if (position == null) return
        if (!zoomedForFollow) {
            mapView.controller.setZoom(FOLLOW_ZOOM)
            zoomedForFollow = true
        }
        val last = lastCenter
        if (last != null && last.latitude == position.latitude && last.longitude == position.longitude) return
        lastCenter = position
        mapView.controller.setCenter(position)
    }

    fun fitToTrackOnce(key: Any, snapshot: TrackSnapshot) {
        if (fittedKey == key) return
        val pts = snapshot.points
        val n = minOf(snapshot.size, pts.size)
        if (n == 0) return
        fittedKey = key

        var north = -90.0
        var south = 90.0
        var east = -180.0
        var west = 180.0
        for (i in 0 until n) {
            val p = pts[i]
            if (p.latitude > north) north = p.latitude
            if (p.latitude < south) south = p.latitude
            if (p.longitude > east) east = p.longitude
            if (p.longitude < west) west = p.longitude
        }

        val fit = {
            try {
                if (north - south < 1e-4 && east - west < 1e-4) {
                    mapView.controller.setZoom(FOLLOW_ZOOM)
                    mapView.controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
                } else {
                    mapView.zoomToBoundingBox(BoundingBox(north, east, south, west).increaseByScale(1.25f), false)
                }
            } catch (e: Exception) {
                mapView.controller.setCenter(GeoPoint((north + south) / 2, (east + west) / 2))
            }
        }
        if (mapView.width > 0 && mapView.height > 0) {
            fit()
        } else {
            mapView.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
        }
    }

    fun clear() {
        if (segments.isNotEmpty()) mapView.overlays.removeAll(segments.toSet())
        segments.clear()
        activeLine = null
        activeBucket = Int.MIN_VALUE
        lastGeoPoint = null
        source = null
        drawnCount = 0
    }

    private fun createCarIcon(density: Float): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            setStroke((4 * density).toInt(), android.graphics.Color.parseColor("#FF00E676"))
            val size = (22 * density).toInt()
            setSize(size, size)
        }
}
