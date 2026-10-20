package com.ecooctano.telemetry.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.ui.ReplayState
import com.ecooctano.telemetry.ui.TelemetryViewModel
import com.ecooctano.telemetry.ui.components.CockpitCard
import com.ecooctano.telemetry.ui.components.Fmt
import com.ecooctano.telemetry.ui.components.LabeledValue
import com.ecooctano.telemetry.ui.components.SensorStrip
import com.ecooctano.telemetry.ui.components.SensorStripValues
import com.ecooctano.telemetry.ui.components.MonoValue
import com.ecooctano.telemetry.ui.components.SpeedLegend
import com.ecooctano.telemetry.ui.map.OsmTelemetryMap
import com.ecooctano.telemetry.ui.map.SpeedColors
import com.ecooctano.telemetry.ui.theme.CockpitColors
import org.osmdroid.util.GeoPoint
import java.util.Locale

@Composable
fun ReplayScreen(viewModel: TelemetryViewModel, modifier: Modifier = Modifier) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val replay by viewModel.replay.collectAsStateWithLifecycle()

    BackHandler(enabled = replay.session != null || replay.isLoading) { viewModel.closeReplay() }

    when {
        replay.isLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = CockpitColors.Green)
        }
        replay.session != null -> ReplayContent(replay, viewModel, modifier)
        else -> SessionList(
            sessions = sessions,
            onOpen = viewModel::openSession,
            onShare = viewModel::shareSession,
            onDelete = viewModel::deleteSession,
            modifier = modifier,
        )
    }
}

// ============================================================== Lista de corridas

@Composable
private fun SessionList(
    sessions: List<RaceSession>,
    onOpen: (Long) -> Unit,
    onShare: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<RaceSession?>(null) }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("RETROSPECTIVA", color = CockpitColors.Green, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text("Selecione uma corrida salva para reproduzir", color = CockpitColors.Label, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma corrida salva ainda.\nUse SALVAR no modo Ao Vivo.",
                    color = CockpitColors.Label,
                    fontSize = 16.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onOpen = { onOpen(session.id) },
                        onShare = { onShare(session.id) },
                        onDelete = { pendingDelete = session },
                    )
                }
            }
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Excluir corrida?") },
            text = { Text("\"${session.name}\" será removida do banco de dados. Arquivos já exportados em Documents não são apagados.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete = null
                        onDelete(session.id)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Red),
                ) { Text("EXCLUIR", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("CANCELAR") } },
        )
    }
}

@Composable
private fun SessionRow(
    session: RaceSession,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CockpitColors.Card),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${session.id}  ${session.name}",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(Fmt.dateTime(session.startedAtEpochMs), color = CockpitColors.Label, fontSize = 12.sp)
                Text(
                    "${Fmt.raceTime(session.durationMs)}  •  ${Fmt.distance(session.distanceMeters)}  •  " +
                        "${session.lapCount} volta(s)  •  máx ${Fmt.num(session.maxSpeedKmh, 1)} km/h  •  " +
                        "melhor ${Fmt.raceTime(session.bestLapMs)}",
                    color = CockpitColors.Value,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onShare, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Compartilhar", tint = CockpitColors.Blue)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = CockpitColors.Red)
            }
            Button(
                onClick = onOpen,
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Green, contentColor = Color.Black),
            ) { Text("ABRIR", fontWeight = FontWeight.Black) }
        }
    }
}

// ============================================================== Reprodução

@Composable
private fun ReplayContent(replay: ReplayState, viewModel: TelemetryViewModel, modifier: Modifier = Modifier) {
    val session = replay.session ?: return
    val point = replay.currentPoint
    val marker = replay.markerPoint
    val markerLat = marker?.latitude
    val markerLon = marker?.longitude
    val markerPosition = if (markerLat != null && markerLon != null) GeoPoint(markerLat, markerLon) else null
    val lap = replay.currentLap
    val lapElapsed = if (point != null && lap != null) point.elapsedMs - lap.startOffsetMs else null

    Row(
        modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            Modifier.weight(1.15f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Cabeçalho
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = viewModel::closeReplay, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "#${session.id}  ${session.name}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${Fmt.dateTime(session.startedAtEpochMs)}  •  ${replay.points.size} pts",
                        color = CockpitColors.Label,
                        fontSize = 11.sp,
                    )
                }
            }

            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Velocidade + tempos
                CockpitCard("NESTE INSTANTE", Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1.2f), verticalAlignment = Alignment.Bottom) {
                            MonoValue(
                                Fmt.num(point?.speedKmh, 1),
                                fontSize = 56.sp,
                                color = SpeedColors.composeColor(point?.speedKmh),
                                fontWeight = FontWeight.Black,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("km/h", color = CockpitColors.Label, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            LabeledValue("TEMPO", Fmt.raceTime(point?.elapsedMs), valueSize = 16.sp)
                            LabeledValue(
                                "VOLTA ${point?.lapNumber ?: "-"}",
                                Fmt.raceTime(lapElapsed),
                                valueSize = 16.sp,
                                valueColor = CockpitColors.Amber,
                            )
                            LabeledValue(
                                "MELHOR VOLTA",
                                Fmt.raceTime(session.bestLapMs),
                                valueSize = 16.sp,
                                valueColor = CockpitColors.Green,
                            )
                        }
                    }
                }

                // Sensores registrados naquele timestamp
                SensorStrip(
                    values = SensorStripValues(
                        gForce = point?.gForce,
                        accelX = point?.accelX,
                        accelY = point?.accelY,
                        accelZ = point?.accelZ,
                        pressureHpa = point?.pressureHpa,
                        baroAltitudeM = point?.baroAltitudeM,
                        pitchDeg = point?.pitchDeg,
                        rollDeg = point?.rollDeg,
                        temperatureC = point?.temperatureC,
                        temperatureLabel = "registrada",
                    ),
                    modifier = Modifier.fillMaxWidth().height(86.dp),
                )
            }

            PlaybackControls(replay, viewModel)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black),
        ) {
            OsmTelemetryMap(
                track = replay.track,
                carPosition = markerPosition,
                followCar = false,
                modifier = Modifier.fillMaxSize(),
                fitToTrackKey = session.id,
            )
            SpeedLegend(Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (replay.track.size == 0) {
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
}

@Composable
private fun PlaybackControls(replay: ReplayState, viewModel: TelemetryViewModel) {
    val start = replay.startMs
    val end = replay.endMs
    val hasRange = end > start
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = replay.positionMs.coerceIn(start, end).toFloat(),
            onValueChange = { viewModel.seekTo(it.toLong()) },
            valueRange = start.toFloat()..(if (hasRange) end.toFloat() else start.toFloat() + 1f),
            enabled = hasRange,
            colors = SliderDefaults.colors(
                thumbColor = CockpitColors.Green,
                activeTrackColor = CockpitColors.Green,
                inactiveTrackColor = Color(0xFF333333),
            ),
            modifier = Modifier.fillMaxWidth().height(36.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = viewModel::togglePlayback,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = if (replay.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (replay.isPlaying) "Pausar" else "Reproduzir",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            TelemetryViewModel.PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = replay.playbackSpeed == speed,
                    onClick = { viewModel.setPlaybackSpeed(speed) },
                    label = { Text(String.format(Locale.US, "%.0fx", speed), fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CockpitColors.Amber,
                        selectedLabelColor = Color.Black,
                    ),
                    modifier = Modifier.padding(end = 6.dp).height(40.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            MonoValue(
                "${Fmt.raceTime(replay.positionMs - start)} / ${Fmt.raceTime(end - start)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

