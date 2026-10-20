package com.ecooctano.telemetry.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.sensors.GpsReading
import com.ecooctano.telemetry.sensors.GpsStatus
import com.ecooctano.telemetry.sensors.SensorReadings
import com.ecooctano.telemetry.sensors.TemperatureSource
import com.ecooctano.telemetry.ui.LiveSessionState
import com.ecooctano.telemetry.ui.RaceStatus
import com.ecooctano.telemetry.ui.TelemetryViewModel
import com.ecooctano.telemetry.ui.components.CockpitPanel
import com.ecooctano.telemetry.ui.components.FitText
import com.ecooctano.telemetry.ui.components.Fmt
import com.ecooctano.telemetry.ui.components.MiniStat
import com.ecooctano.telemetry.ui.components.PanelLabel
import com.ecooctano.telemetry.ui.components.SensorStrip
import com.ecooctano.telemetry.ui.components.SensorStripValues
import com.ecooctano.telemetry.ui.components.SpeedLegend
import com.ecooctano.telemetry.ui.map.OsmTelemetryMap
import com.ecooctano.telemetry.ui.map.SpeedColors
import com.ecooctano.telemetry.ui.theme.CockpitColors
import org.osmdroid.util.GeoPoint

/**
 * Cockpit ao vivo. O painel esquerdo usa toda a altura sem rolagem:
 * velocidade + tempo decorrido (o que o piloto precisa ler de relance) ocupam o espaço
 * que sobrar; sensores e botões têm altura fixa.
 */
@Composable
fun LiveDashboardScreen(viewModel: TelemetryViewModel, modifier: Modifier = Modifier) {
    val live by viewModel.live.collectAsStateWithLifecycle()
    val sensors by viewModel.sensorReadings.collectAsStateWithLifecycle()
    val gps by viewModel.gpsReading.collectAsStateWithLifecycle()
    val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }
    var followCar by rememberSaveable { mutableStateOf(true) }

    val currentGps = gps
    val speedKmh = if (live.gpsFresh) currentGps?.speedKmh else null
    val carPosition = currentGps?.let { GeoPoint(it.latitude, it.longitude) }

    Row(
        modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------- Painel esquerdo
        Column(
            Modifier.weight(1.1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SpeedPanel(
                    speedKmh = speedKmh,
                    live = live,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                )
                ElapsedPanel(
                    live = live,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            SensorStrip(
                values = sensors.toStripValues(),
                modifier = Modifier.fillMaxWidth().height(86.dp),
                onLongPressInclination = viewModel::calibrateInclination,
            )
            ControlButtons(
                live = live,
                onStartPause = viewModel::toggleRace,
                onLap = viewModel::newLap,
                onReset = { showResetDialog = true },
                onSave = viewModel::saveAndExport,
            )
        }

        // ---------------- Painel direito: mapa
        Box(
            Modifier
                .weight(1f)
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
            GpsBadge(
                status = gpsStatus,
                gps = currentGps,
                fresh = live.gpsFresh,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            )
            IconButton(
                onClick = { followCar = !followCar },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color(0xCC000000),
                    contentColor = if (followCar) CockpitColors.Green else CockpitColors.Label,
                ),
            ) {
                Icon(
                    imageVector = if (followCar) Icons.Filled.MyLocation else Icons.Filled.LocationSearching,
                    contentDescription = "Seguir carro",
                    modifier = Modifier.size(28.dp),
                )
            }
            SpeedLegend(Modifier.align(Alignment.BottomStart).padding(8.dp))
            if (live.isRecording) {
                RecordingBadge(Modifier.align(Alignment.BottomEnd).padding(8.dp))
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
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("CANCELAR") }
            },
        )
    }
}

private fun SensorReadings.toStripValues() = SensorStripValues(
    gForce = gForce,
    accelX = accelX,
    accelY = accelY,
    accelZ = accelZ,
    pressureHpa = pressureHpa,
    baroAltitudeM = baroAltitudeM,
    pitchDeg = pitchDeg,
    rollDeg = rollDeg,
    temperatureC = temperatureC,
    temperatureLabel = when (temperatureSource) {
        TemperatureSource.AMBIENT_SENSOR -> "ambiente"
        TemperatureSource.BATTERY -> "bateria"
        TemperatureSource.UNAVAILABLE -> Fmt.NA
    },
)

@Composable
private fun SpeedPanel(speedKmh: Float?, live: LiveSessionState, modifier: Modifier = Modifier) {
    CockpitPanel(modifier) {
        PanelLabel("VELOCIDADE  km/h")
        // Número em branco: máximo contraste sob sol. A cor da escala aparece na barra abaixo.
        FitText(
            text = Fmt.num(speedKmh, 1),
            template = "88.8",
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(
                    if (speedKmh == null) CockpitColors.CardBorder else SpeedColors.composeColor(speedKmh),
                    RoundedCornerShape(3.dp),
                ),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            MiniStat("MÉDIA", Fmt.num(live.avgSpeedKmh, 1), Modifier.weight(1f))
            MiniStat("MÁXIMA", Fmt.num(live.maxSpeedKmh, 1), Modifier.weight(1f))
        }
    }
}

@Composable
private fun ElapsedPanel(live: LiveSessionState, modifier: Modifier = Modifier) {
    val (statusText, statusColor) = when (live.status) {
        RaceStatus.IDLE -> "PRONTO" to CockpitColors.Label
        RaceStatus.RUNNING -> "EM CORRIDA" to CockpitColors.Green
        RaceStatus.PAUSED -> "PAUSADO" to CockpitColors.Amber
    }
    val elapsed = Fmt.elapsed(live.elapsedMs)
    CockpitPanel(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PanelLabel("TEMPO DECORRIDO", Modifier.weight(1f))
            PanelLabel(statusText, color = statusColor)
        }
        FitText(
            text = elapsed,
            template = if (elapsed.length > 5) "8:88:88" else "88:88",
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = if (live.status == RaceStatus.PAUSED) CockpitColors.Amber else CockpitColors.Value,
        )
        Spacer(Modifier.height(11.dp))
        Row(Modifier.fillMaxWidth()) {
            MiniStat("VOLTA", live.lapNumber.toString(), Modifier.weight(0.7f))
            MiniStat("DISTÂNCIA", Fmt.distance(live.distanceMeters), Modifier.weight(1.3f))
        }
    }
}

@Composable
private fun ControlButtons(
    live: LiveSessionState,
    onStartPause: () -> Unit,
    onLap: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    val (startLabel, startIcon, startColor) = when (live.status) {
        RaceStatus.IDLE -> Triple("INICIAR", Icons.Filled.PlayArrow, CockpitColors.Green)
        RaceStatus.RUNNING -> Triple("PAUSAR", Icons.Filled.Pause, CockpitColors.Amber)
        RaceStatus.PAUSED -> Triple("RETOMAR", Icons.Filled.PlayArrow, CockpitColors.Green)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BigButton(
            label = startLabel,
            icon = startIcon,
            color = startColor,
            enabled = !live.isSaving,
            onClick = onStartPause,
            modifier = Modifier.weight(1.3f),
        )
        BigButton(
            label = "VOLTA",
            icon = Icons.Filled.Flag,
            color = CockpitColors.Blue,
            enabled = live.status == RaceStatus.RUNNING && !live.isSaving,
            onClick = onLap,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            label = "RESETAR",
            icon = Icons.Filled.RestartAlt,
            color = CockpitColors.Red,
            enabled = !live.isSaving && (live.status != RaceStatus.IDLE || live.pointCount > 0),
            onClick = onReset,
            modifier = Modifier.weight(1f),
        )
        BigButton(
            label = if (live.isSaving) "SALVANDO" else "SALVAR",
            icon = Icons.Filled.Save,
            color = Color(0xFFB388FF),
            enabled = !live.isSaving && live.pointCount > 0,
            onClick = onSave,
            modifier = Modifier.weight(1f),
            loading = live.isSaving,
        )
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
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = Color.Black,
            disabledContainerColor = Color(0xFF2A2A2A),
            disabledContentColor = Color(0xFF6E6E6E),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 3.dp)
            } else {
                Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            }
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

@Composable
private fun GpsBadge(status: GpsStatus, gps: GpsReading?, fresh: Boolean, modifier: Modifier = Modifier) {
    val (text, color) = when {
        status == GpsStatus.NO_PERMISSION -> "GPS: sem permissão" to CockpitColors.Red
        status == GpsStatus.UNAVAILABLE -> "GPS: indisponível" to CockpitColors.Red
        gps == null -> "GPS: buscando…" to CockpitColors.Amber
        !fresh || status == GpsStatus.SIGNAL_LOST -> "GPS: sinal perdido" to CockpitColors.Red
        else -> {
            val acc = gps.accuracyM
            val c = when {
                acc == null -> CockpitColors.Amber
                acc <= 5f -> CockpitColors.Green
                acc <= 15f -> CockpitColors.Amber
                else -> CockpitColors.Red
            }
            "GPS ±${Fmt.num(acc, 1)} m" to c
        }
    }
    Row(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RecordingBadge(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(CockpitColors.Red, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("REC 5 Hz", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
