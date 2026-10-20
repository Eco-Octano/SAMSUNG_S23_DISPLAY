package com.ecooctano.telemetry.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.ecooctano.telemetry.ui.components.AxisLine
import com.ecooctano.telemetry.ui.components.CockpitCard
import com.ecooctano.telemetry.ui.components.Fmt
import com.ecooctano.telemetry.ui.components.LabeledValue
import com.ecooctano.telemetry.ui.components.MonoValue
import com.ecooctano.telemetry.ui.components.SpeedLegend
import com.ecooctano.telemetry.ui.map.OsmTelemetryMap
import com.ecooctano.telemetry.ui.map.SpeedColors
import com.ecooctano.telemetry.ui.theme.CockpitColors
import org.osmdroid.util.GeoPoint

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
    val freshGps: GpsReading? = if (live.gpsFresh) currentGps else null
    val carPosition = currentGps?.let { GeoPoint(it.latitude, it.longitude) }

    Row(
        modifier.fillMaxSize().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---------------- Painel esquerdo: telemetria e controle de voltas
        Column(
            Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpeedPanel(live = live, speedKmh = freshGps?.speedKmh)
            ChronoPanel(live)
            SensorGrid(sensors, onZeroInclination = viewModel::calibrateInclination)
            ControlButtons(
                live = live,
                onStartPause = viewModel::toggleRace,
                onLap = viewModel::newLap,
                onReset = { showResetDialog = true },
                onSave = viewModel::saveAndExport,
            )
        }

        // ---------------- Painel direito: mapa estilo Strava
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
            text = { Text("Todas as voltas e pontos NÃO salvos desta corrida serão descartados.") },
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

@Composable
private fun SpeedPanel(live: LiveSessionState, speedKmh: Float?) {
    CockpitCard(title = "VELOCIDADE", modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1.4f), verticalAlignment = Alignment.Bottom) {
                MonoValue(
                    text = Fmt.num(speedKmh, 1),
                    fontSize = 72.sp,
                    color = SpeedColors.composeColor(speedKmh),
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "km/h",
                    color = CockpitColors.Label,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabeledValue("MÉDIA", "${Fmt.num(live.avgSpeedKmh, 1)} km/h", valueSize = 16.sp)
                LabeledValue("MÁXIMA", "${Fmt.num(live.maxSpeedKmh, 1)} km/h", valueSize = 16.sp)
                LabeledValue("DISTÂNCIA", Fmt.distance(live.distanceMeters), valueSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ChronoPanel(live: LiveSessionState) {
    CockpitCard(title = "CRONÔMETRO  •  ${live.pointCount} pts", modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledValue("TOTAL", Fmt.raceTime(live.elapsedMs), Modifier.weight(1f), valueSize = 17.sp)
            LabeledValue(
                "VOLTA ${live.lapNumber}",
                Fmt.raceTime(live.lapElapsedMs),
                Modifier.weight(1f),
                valueSize = 17.sp,
                valueColor = CockpitColors.Amber,
            )
            LabeledValue(
                "MELHOR",
                Fmt.raceTime(live.bestLapMs),
                Modifier.weight(1f),
                valueSize = 17.sp,
                valueColor = CockpitColors.Green,
            )
            LabeledValue("ÚLTIMA", Fmt.raceTime(live.lastLapMs), Modifier.weight(1f), valueSize = 17.sp)
        }
    }
}

@Composable
fun SensorGrid(sensors: SensorReadings, onZeroInclination: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        GForceCard(
            gForce = sensors.gForce,
            x = sensors.accelX,
            y = sensors.accelY,
            z = sensors.accelZ,
            modifier = Modifier.weight(1f).height(96.dp),
        )
        CockpitCard("BARÔMETRO", Modifier.weight(1f).height(96.dp)) {
            MonoValue(Fmt.num(sensors.pressureHpa, 1), fontSize = 18.sp)
            Text("hPa", color = CockpitColors.Label, fontSize = 10.sp)
            AxisLine("Alt", "${Fmt.signed(sensors.baroAltitudeM, 1)} m")
        }
        val inclinationModifier = Modifier
            .weight(1f)
            .height(96.dp)
            .let { base ->
                if (onZeroInclination == null) {
                    base
                } else {
                    base.pointerInput(onZeroInclination) {
                        detectTapGestures(onLongPress = { onZeroInclination() })
                    }
                }
            }
        CockpitCard("INCLINAÇÃO", inclinationModifier) {
            AxisLine("Pitch", "${Fmt.signed(sensors.pitchDeg, 1)}°")
            AxisLine("Roll ", "${Fmt.signed(sensors.rollDeg, 1)}°")
            if (onZeroInclination != null) {
                Text("segure p/ zerar", color = CockpitColors.Label, fontSize = 9.sp)
            }
        }
        TemperatureCard(
            temperatureC = sensors.temperatureC,
            source = sensors.temperatureSource,
            modifier = Modifier.weight(1f).height(96.dp),
        )
    }
}

@Composable
fun GForceCard(gForce: Float?, x: Float?, y: Float?, z: Float?, modifier: Modifier = Modifier) {
    CockpitCard("FORÇA G", modifier) {
        MonoValue("${Fmt.num(gForce, 2)} G", fontSize = 18.sp, color = CockpitColors.Amber)
        AxisLine("X", Fmt.signed(x, 2))
        AxisLine("Y", Fmt.signed(y, 2))
        AxisLine("Z", Fmt.signed(z, 2))
    }
}

@Composable
fun TemperatureCard(
    temperatureC: Float?,
    source: TemperatureSource,
    modifier: Modifier = Modifier,
) {
    CockpitCard("TEMPERATURA", modifier) {
        MonoValue("${Fmt.num(temperatureC, 1)} °C", fontSize = 18.sp)
        val label = when (source) {
            TemperatureSource.AMBIENT_SENSOR -> "sensor ambiente"
            TemperatureSource.BATTERY -> "bateria (interna)"
            TemperatureSource.UNAVAILABLE -> Fmt.NA
        }
        Text(label, color = CockpitColors.Label, fontSize = 10.sp)
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
            modifier = Modifier.weight(1f),
        )
        BigButton(
            label = "NOVA VOLTA",
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
        modifier = modifier.height(68.dp),
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
