package com.ecooctano.telemetry.ui.analysis

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.R
import com.ecooctano.telemetry.data.model.LapData
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.ui.components.AppEventsEffect
import com.ecooctano.telemetry.ui.components.DeleteSessionDialog
import com.ecooctano.telemetry.ui.components.Fmt
import com.ecooctano.telemetry.ui.components.SessionListItem
import com.ecooctano.telemetry.ui.components.SpeedLegend
import com.ecooctano.telemetry.ui.map.OsmTelemetryMap
import com.ecooctano.telemetry.ui.theme.CockpitColors
import org.osmdroid.util.GeoPoint
import java.util.Locale

/** Largura a partir da qual lista e detalhe ficam lado a lado (Tab S9 em paisagem). */
private val TWO_PANE_MIN_WIDTH = 900.dp
/** Largura do detalhe a partir da qual ele usa duas colunas (mapa | gráficos). */
private val WIDE_DETAIL_MIN_WIDTH = 820.dp

@Composable
fun AnalysisRoot(viewModel: AnalysisViewModel, onSwitchToDisplay: () -> Unit) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    AppEventsEffect(message, viewModel.shareRequests, snackbarHostState, viewModel::consumeMessage)

    var pendingDelete by remember { mutableStateOf<RaceSession?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris)
    }
    val onImport = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "application/octet-stream")) }

    Column(
        Modifier
            .fillMaxSize()
            .background(CockpitColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (recording.isSessionActive) {
            RecordingBanner(
                text = (if (recording.isRecording) "Gravando corrida" else "Corrida pausada") +
                    " · ${Fmt.elapsed(recording.elapsedMs)} · ${Fmt.distance(recording.distanceMeters)}",
                onClick = onSwitchToDisplay,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
            val hasSelection = state.session != null || state.isLoading
            val listPane: @Composable (Modifier) -> Unit = { m ->
                SessionsPane(
                    sessions = sessions,
                    selectedId = state.session?.id,
                    importing = importing,
                    onImport = onImport,
                    onShareAll = { viewModel.sharePackage(null) },
                    onSwitchToDisplay = onSwitchToDisplay,
                    onOpen = viewModel::openSession,
                    onSharePackage = { viewModel.sharePackage(it) },
                    onShareCsv = { viewModel.shareCsv(it) },
                    onDelete = { pendingDelete = it },
                    modifier = m,
                )
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    listPane(Modifier.width(340.dp).fillMaxHeight())
                    VerticalDivider(color = CockpitColors.CardBorder)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        DetailOrPlaceholder(state, viewModel, showBack = false)
                    }
                }
            } else {
                BackHandler(enabled = hasSelection) { viewModel.closeSession() }
                if (hasSelection) {
                    DetailOrPlaceholder(state, viewModel, showBack = true)
                } else {
                    listPane(Modifier.fillMaxSize())
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
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

/** Aviso fixo no topo enquanto há corrida em gravação neste aparelho. */
@Composable
private fun RecordingBanner(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF3A0D0D))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(CockpitColors.Red, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("VOLTAR AO DISPLAY", color = CockpitColors.Amber, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

// =================================================================== Lista

@Composable
private fun SessionsPane(
    sessions: List<RaceSession>,
    selectedId: Long?,
    importing: Boolean,
    onImport: () -> Unit,
    onShareAll: () -> Unit,
    onSwitchToDisplay: () -> Unit,
    onOpen: (Long) -> Unit,
    onSharePackage: (Long) -> Unit,
    onShareCsv: (Long) -> Unit,
    onDelete: (RaceSession) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.eco_octano_logo), contentDescription = "Eco Octano", modifier = Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("ECO OCTANO", color = CockpitColors.Brand, fontSize = 16.sp, fontWeight = FontWeight.Black)
                Text("Análise de telemetria", color = CockpitColors.Label, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onSwitchToDisplay) {
                Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("DISPLAY")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onImport,
                enabled = !importing,
                colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Brand, contentColor = Color.Black),
                modifier = Modifier.weight(1f),
            ) {
                if (importing) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (importing) "IMPORTANDO" else "IMPORTAR", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onShareAll, enabled = sessions.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("EXPORTAR TUDO", maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma corrida ainda.\n\nNo display (S23): menu ⋮ → Corridas salvas → compartilhar.\n" +
                        "Envie o .ecooctano.zip para cá (Quick Share, Drive, cabo) e toque em IMPORTAR.",
                    color = CockpitColors.Label,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionListItem(
                        session = session,
                        selected = session.id == selectedId,
                        onSharePackage = { onSharePackage(session.id) },
                        onShareCsv = { onShareCsv(session.id) },
                        onDelete = { onDelete(session) },
                        onOpen = { onOpen(session.id) },
                    )
                }
            }
        }
    }
}

// =================================================================== Detalhe

@Composable
private fun DetailOrPlaceholder(state: AnalysisState, viewModel: AnalysisViewModel, showBack: Boolean) {
    when {
        state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CockpitColors.Brand)
        }
        state.session != null -> SessionDetail(state, viewModel, showBack)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Selecione uma corrida na lista", color = CockpitColors.Label, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SessionDetail(state: AnalysisState, viewModel: AnalysisViewModel, showBack: Boolean) {
    val session = state.session ?: return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_DETAIL_MIN_WIDTH
        val header: @Composable () -> Unit = { DetailHeader(session, state, showBack, viewModel::closeSession) }
        val map: @Composable (Modifier) -> Unit = { m -> ReplayMap(state, m) }
        val controls: @Composable () -> Unit = { PlaybackControls(state, viewModel) }
        val values: @Composable () -> Unit = { CurrentValues(state) }
        val charts: @Composable () -> Unit = { ChartsSection(state, viewModel::seekTo) }
        val laps: @Composable () -> Unit = { LapTable(state.laps, state.currentPoint?.lapNumber) }

        if (wide) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    header()
                    map(Modifier.fillMaxWidth().weight(1f))
                    controls()
                    values()
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SummaryStats(session, state)
                    charts()
                    laps()
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                header()
                SummaryStats(session, state)
                map(Modifier.fillMaxWidth().height(300.dp))
                controls()
                values()
                charts()
                laps()
            }
        }
    }
}

@Composable
private fun DetailHeader(session: RaceSession, state: AnalysisState, showBack: Boolean, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "#${session.id}  ${session.name}",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${Fmt.dateTime(session.startedAtEpochMs)} · ${state.points.size} pontos (5 Hz)" +
                    if (session.imuFileName != null) " · IMU 100 Hz disponível" else "",
                color = CockpitColors.Label,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryStats(session: RaceSession, state: AnalysisState) {
    val gpsDelta = remember(state.series) { meanAbsDifference(state.series.speedFused, state.series.speedGpsRaw) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("DURAÇÃO", Fmt.elapsed(session.durationMs))
        StatTile("DISTÂNCIA", Fmt.distance(session.distanceMeters))
        StatTile("VEL. MÉDIA", "${Fmt.num(session.avgSpeedKmh, 1)} km/h")
        StatTile("VEL. MÁXIMA", "${Fmt.num(session.maxSpeedKmh, 1)} km/h")
        StatTile("VOLTAS", session.lapCount.toString())
        StatTile("MELHOR VOLTA", Fmt.raceTime(session.bestLapMs))
        StatTile("KALMAN × GPS", "${Fmt.num(gpsDelta, 2)} km/h")
    }
}

/** Diferença média absoluta entre a velocidade fundida e a do GPS bruto (validação do filtro). */
private fun meanAbsDifference(a: FloatArray, b: FloatArray): Float? {
    var sum = 0.0
    var n = 0
    for (i in 0 until minOf(a.size, b.size)) {
        val x = a[i]
        val y = b[i]
        if (x.isFinite() && y.isFinite()) {
            sum += kotlin.math.abs(x - y)
            n++
        }
    }
    return if (n > 0) (sum / n).toFloat() else null
}

@Composable
private fun StatTile(label: String, value: String) {
    Column(
        Modifier
            .widthIn(min = 120.dp)
            .background(CockpitColors.Card, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = CockpitColors.Label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(
            value,
            color = if (value.startsWith(Fmt.NA)) CockpitColors.Label else CockpitColors.Value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun ReplayMap(state: AnalysisState, modifier: Modifier) {
    val marker = state.markerPoint
    val lat = marker?.latitude
    val lon = marker?.longitude
    val markerPosition = if (lat != null && lon != null) GeoPoint(lat, lon) else null
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        OsmTelemetryMap(
            track = state.track,
            carPosition = markerPosition,
            followCar = false,
            modifier = Modifier.fillMaxSize(),
            fitToTrackKey = state.session?.id,
        )
        SpeedLegend(Modifier.align(Alignment.BottomStart).padding(8.dp))
        if (state.track.size == 0) {
            Text(
                "Corrida sem pontos de GPS",
                color = CockpitColors.Amber,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun PlaybackControls(state: AnalysisState, viewModel: AnalysisViewModel) {
    val start = state.startMs
    val end = state.endMs
    val hasRange = end > start
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = state.positionMs.coerceIn(start, end).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong()) },
            valueRange = start.toFloat()..(if (hasRange) end.toFloat() else start.toFloat() + 1f),
            enabled = hasRange,
            colors = SliderDefaults.colors(
                thumbColor = CockpitColors.Brand,
                activeTrackColor = CockpitColors.Brand,
                inactiveTrackColor = Color(0xFF333333),
            ),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = viewModel::togglePlayback, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pausar" else "Reproduzir",
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            AnalysisViewModel.PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = state.playbackSpeed == speed,
                    onClick = { viewModel.setPlaybackSpeed(speed) },
                    label = { Text(String.format(Locale.US, "%.0fx", speed), fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CockpitColors.Amber,
                        selectedLabelColor = Color.Black,
                    ),
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${Fmt.raceTime(state.positionMs - start)} / ${Fmt.elapsed(end - start)}",
                color = CockpitColors.Value,
                fontSize = 13.sp,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentValues(state: AnalysisState) {
    val p = state.currentPoint
    val lap = state.currentLap
    val lapElapsed = if (p != null && lap != null) p.elapsedMs - lap.startOffsetMs else null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ValueChip("Velocidade", "${Fmt.num(p?.speedKmh, 1)} km/h")
        ValueChip("GPS bruto", "${Fmt.num(p?.gpsSpeedRawKmh, 1)} km/h")
        ValueChip("Volta ${p?.lapNumber ?: "-"}", Fmt.raceTime(lapElapsed))
        ValueChip("Força G", "${Fmt.num(p?.gForce, 2)} G")
        ValueChip("X/Y/Z", "${Fmt.signed(p?.accelX, 2)} ${Fmt.signed(p?.accelY, 2)} ${Fmt.signed(p?.accelZ, 2)}")
        ValueChip("Pitch / Roll", "${Fmt.signed(p?.pitchDeg, 1)}° ${Fmt.signed(p?.rollDeg, 1)}°")
        ValueChip("Alt. baro", "${Fmt.signed(p?.baroAltitudeM, 1)} m")
        ValueChip("Pressão", "${Fmt.num(p?.pressureHpa, 1)} hPa")
        ValueChip("Temp.", "${Fmt.num(p?.temperatureC, 1)} °C")
        ValueChip("GPS ±", "${Fmt.num(p?.gpsAccuracyM, 1)} m")
    }
}

@Composable
private fun ValueChip(label: String, value: String) {
    Column(
        Modifier
            .background(CockpitColors.Card, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = CockpitColors.Label, fontSize = 10.sp)
        Text(
            value,
            color = CockpitColors.Value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

@Composable
private fun ChartsSection(state: AnalysisState, onSeek: (Long) -> Unit) {
    val s = state.series
    val charts = remember(s) {
        listOf(
            ChartSpec("Velocidade", "km/h", 1, listOf(
                ChartSeries("Kalman", s.speedFused, ChartColors.Series1),
                ChartSeries("GPS", s.speedGpsRaw, ChartColors.Series2),
            )),
            ChartSpec("Força G", "G", 2, listOf(ChartSeries("G", s.gForce, ChartColors.Series1))),
            ChartSpec("Aceleração linear", "m/s²", 2, listOf(
                ChartSeries("X", s.accelX, ChartColors.Series1),
                ChartSeries("Y", s.accelY, ChartColors.Series2),
                ChartSeries("Z", s.accelZ, ChartColors.Series3),
            )),
            ChartSpec("Altitude barométrica", "m", 1, listOf(ChartSeries("Alt", s.baroAltitude, ChartColors.Series1))),
            ChartSpec("Inclinação", "°", 1, listOf(
                ChartSeries("Pitch", s.pitch, ChartColors.Series1),
                ChartSeries("Roll", s.roll, ChartColors.Series2),
            )),
            ChartSpec("Temperatura", "°C", 1, listOf(ChartSeries("Temp", s.temperature, ChartColors.Series1))),
            ChartSpec("Precisão do GPS", "m", 1, listOf(ChartSeries("±", s.gpsAccuracy, ChartColors.Series1))),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        charts.forEach { c ->
            TelemetryChart(
                title = c.title,
                unit = c.unit,
                decimals = c.decimals,
                timesMs = s.timesMs,
                series = c.series,
                startMs = state.startMs,
                endMs = state.endMs,
                cursorMs = state.positionMs,
                cursorIndex = state.currentIndex,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private class ChartSpec(val title: String, val unit: String, val decimals: Int, val series: List<ChartSeries>)

@Composable
private fun LapTable(laps: List<LapData>, currentLap: Int?) {
    if (laps.isEmpty()) return
    val best = laps.minOfOrNull { it.durationMs }
    Column(
        Modifier
            .fillMaxWidth()
            .background(CockpitColors.Card, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text("Voltas", color = ChartColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        LapRow("#", "Duração", "Média", "Máx", header = true)
        HorizontalDivider(color = CockpitColors.CardBorder)
        laps.forEach { lap ->
            val tag = buildString {
                if (lap.durationMs == best && laps.size > 1) append(" · melhor")
                if (lap.lapNumber == currentLap) append(" · agora")
            }
            LapRow(
                "${lap.lapNumber}$tag",
                Fmt.raceTime(lap.durationMs),
                "${Fmt.num(lap.avgSpeedKmh, 1)} km/h",
                "${Fmt.num(lap.maxSpeedKmh, 1)} km/h",
                highlight = lap.lapNumber == currentLap,
            )
        }
    }
}

@Composable
private fun LapRow(a: String, b: String, c: String, d: String, header: Boolean = false, highlight: Boolean = false) {
    val color = if (header) CockpitColors.Label else CockpitColors.Value
    val weight = if (header || highlight) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlight) Color(0xFF10293A) else Color.Transparent)
            .padding(vertical = 4.dp),
    ) {
        listOf(a to 1.3f, b to 1f, c to 1f, d to 1f).forEach { (text, w) ->
            Text(
                text,
                color = color,
                fontSize = 13.sp,
                fontWeight = weight,
                modifier = Modifier.weight(w),
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}
