package com.ecooctano.telemetry.ui.display

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.recording.LiveSessionState
import com.ecooctano.telemetry.recording.RaceStatus
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.ui.components.AppEventsEffect
import com.ecooctano.telemetry.ui.components.DeleteSessionDialog
import com.ecooctano.telemetry.ui.components.FitText
import com.ecooctano.telemetry.ui.components.Fmt
import com.ecooctano.telemetry.ui.components.PackageHint
import com.ecooctano.telemetry.ui.components.SessionListItem
import com.ecooctano.telemetry.ui.map.OsmTelemetryMap
import com.ecooctano.telemetry.ui.theme.CockpitColors
import org.osmdroid.util.GeoPoint
import java.util.Locale

/** Rótulos do display: escuros de propósito (são estáticos e causariam burn-in). */
private val DimLabel = Color(0xFF5E5E5E)

@Composable
fun DisplayRoot(viewModel: DisplayViewModel, onSwitchToAnalysis: () -> Unit) {
    var showSessions by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    AppEventsEffect(message, viewModel.shareRequests, snackbarHostState, viewModel::consumeMessage)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (showSessions) {
            DisplaySessionsScreen(viewModel, onBack = { showSessions = false })
        } else {
            DisplayCockpit(viewModel, onOpenSessions = { showSessions = true }, onSwitchToAnalysis = onSwitchToAnalysis)
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
    }
}

// =================================================================== Cockpit

/**
 * Display do piloto: só mapa, velocidade, tempo decorrido e distância.
 * Os controles aparecem com um toque e somem sozinhos durante a corrida.
 */
@Composable
fun DisplayCockpit(viewModel: DisplayViewModel, onOpenSessions: () -> Unit, onSwitchToAnalysis: () -> Unit) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val gps by viewModel.gpsReading.collectAsStateWithLifecycle()
    val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }
    var followCar by rememberSaveable { mutableStateOf(true) }
    val clock = rememberInteractionClock()
    val shift = rememberPixelShift()

    val running = live.status == RaceStatus.RUNNING
    val controlsVisible = !running || clock.idleMs < BurnInProtection.CONTROLS_TIMEOUT_MS || showResetDialog
    val dimmed = live.status == RaceStatus.IDLE && clock.idleMs > BurnInProtection.IDLE_DIM_TIMEOUT_MS

    val lat = live.latitude
    val lon = live.longitude
    val carPosition = if (lat != null && lon != null) GeoPoint(lat, lon) else null

    Box(Modifier.fillMaxSize().observeTouches(clock)) {
        // Margem de 4 dp absorve o deslocamento de pixels sem cortar nada.
        Row(
            Modifier
                .fillMaxSize()
                .padding(6.dp)
                .offset { shift },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(0.95f).fillMaxHeight()) {
                DisplayMetric("VELOCIDADE", "km/h", Modifier.weight(1.3f)) {
                    FitText(Fmt.num(live.speedKmh, 1), "88.8", Modifier.fillMaxWidth().weight(1f))
                }
                DisplayMetric("TEMPO", null, Modifier.weight(1f)) {
                    val elapsed = Fmt.elapsed(live.elapsedMs)
                    FitText(
                        elapsed,
                        if (elapsed.length > 5) "8:88:88" else "88:88",
                        Modifier.fillMaxWidth().weight(1f),
                        color = if (live.status == RaceStatus.PAUSED) CockpitColors.Amber else CockpitColors.Value,
                    )
                }
                val (distValue, distUnit) = distanceParts(live.distanceMeters)
                DisplayMetric("DISTÂNCIA", distUnit, Modifier.weight(0.75f)) {
                    FitText(distValue, "88.88", Modifier.fillMaxWidth().weight(1f))
                }
            }
            Box(
                Modifier
                    .weight(1.05f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                OsmTelemetryMap(
                    track = track,
                    carPosition = carPosition,
                    followCar = followCar,
                    modifier = Modifier.fillMaxSize(),
                )
                // Só aparece quando há problema (evita mais um elemento fixo na tela).
                GpsProblemBadge(gpsStatus, gps, live.gpsFresh, Modifier.align(Alignment.TopStart).padding(8.dp))
                if (controlsVisible) {
                    IconButton(
                        onClick = { followCar = !followCar },
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(52.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color(0xCC000000),
                            contentColor = if (followCar) CockpitColors.Green else CockpitColors.Label,
                        ),
                    ) {
                        Icon(
                            if (followCar) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                            contentDescription = "Seguir carro",
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                if (running) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .size(10.dp)
                            .background(CockpitColors.Red, CircleShape),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlBar(
                live = live,
                onStartPause = viewModel::toggleRace,
                onLap = viewModel::newLap,
                onReset = { showResetDialog = true },
                onSave = viewModel::saveAndExport,
                onOpenSessions = onOpenSessions,
                onCalibrate = viewModel::calibrateInclination,
                onSwitchToAnalysis = onSwitchToAnalysis,
            )
        }

        if (dimmed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Toque para acordar",
                    color = DimLabel,
                    fontSize = 14.sp,
                    modifier = Modifier.offset { shift },
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Resetar sessão?") },
            text = { Text("Todos os pontos NÃO salvos desta corrida serão descartados.") },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        viewModel.resetSession()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Red),
                ) { Text("RESETAR", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("CANCELAR") } },
        )
    }
}

private fun distanceParts(meters: Double): Pair<String, String> = when {
    !meters.isFinite() -> Fmt.NA to "m"
    meters < 1000.0 -> String.format(Locale.US, "%.0f", meters) to "m"
    else -> String.format(Locale.US, "%.2f", meters / 1000.0) to "km"
}

/** Bloco sem borda nem fundo: rótulo escuro + valor grande que ocupa o espaço. */
@Composable
private fun DisplayMetric(
    label: String,
    unit: String?,
    modifier: Modifier = Modifier,
    value: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = DimLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            if (unit != null) {
                Spacer(Modifier.width(6.dp))
                Text(unit, color = DimLabel, fontSize = 12.sp)
            }
        }
        value()
    }
}

@Composable
private fun ControlBar(
    live: LiveSessionState,
    onStartPause: () -> Unit,
    onLap: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onOpenSessions: () -> Unit,
    onCalibrate: () -> Unit,
    onSwitchToAnalysis: () -> Unit,
) {
    val (startLabel, startIcon, startColor) = when (live.status) {
        RaceStatus.IDLE -> Triple("INICIAR", Icons.Filled.PlayArrow, CockpitColors.Green)
        RaceStatus.RUNNING -> Triple("PAUSAR", Icons.Filled.Pause, CockpitColors.Amber)
        RaceStatus.PAUSED -> Triple("RETOMAR", Icons.Filled.PlayArrow, CockpitColors.Green)
    }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xE6000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigButton(startLabel, startIcon, startColor, !live.isSaving, onStartPause, Modifier.weight(1.3f))
        BigButton(
            "VOLTA ${live.lapNumber}",
            Icons.Filled.Flag,
            CockpitColors.Blue,
            live.status == RaceStatus.RUNNING && !live.isSaving,
            onLap,
            Modifier.weight(1f),
        )
        BigButton(
            "RESETAR",
            Icons.Filled.RestartAlt,
            CockpitColors.Red,
            !live.isSaving && (live.status != RaceStatus.IDLE || live.pointCount > 0),
            onReset,
            Modifier.weight(1f),
        )
        BigButton(
            if (live.isSaving) "SALVANDO" else "SALVAR",
            Icons.Filled.Save,
            Color(0xFFB388FF),
            !live.isSaving && live.pointCount > 0,
            onSave,
            Modifier.weight(1f),
            loading = live.isSaving,
        )
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(60.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = CockpitColors.Value),
            ) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções", modifier = Modifier.size(30.dp)) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Corridas salvas / compartilhar") },
                    enabled = live.status != RaceStatus.RUNNING,
                    onClick = {
                        menuOpen = false
                        onOpenSessions()
                    },
                )
                DropdownMenuItem(
                    // A gravação continua ao trocar de modo.
                    text = { Text("Modo Análise (replay e gráficos)") },
                    onClick = {
                        menuOpen = false
                        onSwitchToAnalysis()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Zerar inclinação (carro no plano)") },
                    onClick = {
                        menuOpen = false
                        onCalibrate()
                    },
                )
            }
        }
    }
}

@Composable
private fun BigButton(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.Black,
            disabledContainerColor = Color(0xFF242424),
            disabledContentColor = Color(0xFF5E5E5E),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 3.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun GpsProblemBadge(status: GpsStatus, gps: GpsReading?, fresh: Boolean, modifier: Modifier = Modifier) {
    val text = when {
        status == GpsStatus.NO_PERMISSION -> "GPS sem permissão"
        status == GpsStatus.UNAVAILABLE -> "GPS indisponível"
        gps == null -> "Buscando GPS…"
        !fresh || status == GpsStatus.SIGNAL_LOST -> "Sinal de GPS perdido"
        (gps.accuracyM ?: 0f) > 15f -> "GPS impreciso ±${Fmt.num(gps.accuracyM, 0)} m"
        else -> return
    }
    Row(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(CockpitColors.Amber, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

// =================================================================== Corridas salvas

@Composable
fun DisplaySessionsScreen(viewModel: DisplayViewModel, onBack: () -> Unit) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RaceSession?>(null) }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(CockpitColors.Background).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
            Text(
                "CORRIDAS SALVAS",
                color = CockpitColors.Brand,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { viewModel.sharePackage(null) }, enabled = sessions.isNotEmpty()) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("COMPARTILHAR TODAS")
            }
        }
        PackageHint(Modifier.padding(horizontal = 12.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Nenhuma corrida salva ainda.", color = CockpitColors.Label, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionListItem(
                        session = session,
                        selected = false,
                        onSharePackage = { viewModel.sharePackage(session.id) },
                        onShareCsv = { viewModel.shareCsv(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        DeleteSessionDialog(
            session = session,
            onConfirm = {
                pendingDelete = null
                viewModel.deleteSession(session.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
