package com.ecooctano.telemetry.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecooctano.telemetry.ui.map.SpeedColors
import com.ecooctano.telemetry.ui.theme.CockpitColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatação centralizada. Qualquer valor nulo/não finito vira "N/A". */
object Fmt {
    const val NA = "N/A"
    private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

    fun raceTime(ms: Long?): String {
        if (ms == null || ms < 0) return "--:--.---"
        val totalSec = ms / 1000
        return String.format(Locale.US, "%02d:%02d.%03d", totalSec / 60, totalSec % 60, ms % 1000)
    }

    /** Tempo decorrido para o piloto: MM:SS (ou H:MM:SS acima de 1 hora). */
    fun elapsed(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0L)) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }

    fun num(value: Float?, decimals: Int): String =
        if (value == null || !value.isFinite()) NA else String.format(Locale.US, "%.${decimals}f", value)

    fun signed(value: Float?, decimals: Int): String =
        if (value == null || !value.isFinite()) NA else String.format(Locale.US, "%+.${decimals}f", value)

    fun distance(meters: Double?): String = when {
        meters == null || !meters.isFinite() -> NA
        meters < 1000.0 -> String.format(Locale.US, "%.0f m", meters)
        else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
    }

    fun dateTime(epochMs: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", ptBr).format(Date(epochMs))
}

@Composable
fun CockpitCard(
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = CockpitColors.Label,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CockpitColors.Card),
        border = BorderStroke(1.dp, CockpitColors.CardBorder),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = title,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            content()
        }
    }
}

@Composable
fun MonoValue(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    color: Color = CockpitColors.Value,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    Text(
        text = text,
        modifier = modifier,
        color = if (text == Fmt.NA) CockpitColors.Label else color,
        fontSize = fontSize,
        fontFamily = FontFamily.Monospace,
        fontWeight = fontWeight,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 18.sp,
    valueColor: Color = CockpitColors.Value,
) {
    Column(modifier) {
        Text(label, color = CockpitColors.Label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        MonoValue(value, fontSize = valueSize, color = valueColor)
    }
}

/** Linha "X  +0.12" compacta usada nos cards de sensores. */
@Composable
fun AxisLine(axis: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(axis, color = CockpitColors.Label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        MonoValue(value, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Legenda de cores do traçado (mesma escala do mapa). */
@Composable
fun SpeedLegend(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(SpeedColors.composeColor(7f), "0–15")
        LegendItem(SpeedColors.composeColor(22f), "15–30")
        LegendItem(SpeedColors.composeColor(38f), ">30 km/h")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, color = CockpitColors.Value, fontSize = 11.sp)
    }
}

/**
 * Texto que ocupa todo o espaço disponível (largura e altura), dimensionado por um
 * [template] de tamanho fixo — assim a fonte não "pula" quando o valor muda de 9.9 para 10.0.
 * Usa algarismos tabulares (mesma largura) sem o espaçamento exagerado da fonte monoespaçada.
 */
@Composable
fun FitText(
    text: String,
    template: String,
    modifier: Modifier = Modifier,
    color: Color = CockpitColors.Value,
    fontWeight: FontWeight = FontWeight.Black,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val chars = template.length.coerceAtLeast(1)
        val byWidth = maxWidth.value / (chars * 0.6f)
        val byHeight = if (maxHeight.value.isFinite()) maxHeight.value * 0.82f else byWidth
        val sizeDp = minOf(byWidth, byHeight).coerceIn(10f, 220f)
        val fontSize = with(LocalDensity.current) { sizeDp.dp.toSp() }
        Text(
            text = text,
            color = if (text == Fmt.NA) CockpitColors.Label else color,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** Painel de fundo escuro que ocupa o espaço que receber. */
@Composable
fun CockpitPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(CockpitColors.Card, RoundedCornerShape(10.dp))
            .border(1.dp, CockpitColors.CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        content = content,
    )
}

@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier, color: Color = CockpitColors.Label) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Par rótulo/valor compacto usado nas linhas secundárias dos painéis. */
@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = CockpitColors.Value) {
    Column(modifier) {
        Text(label, color = CockpitColors.Label, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            value,
            color = if (value.startsWith(Fmt.NA)) CockpitColors.Label else valueColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

/** Valores exibidos na faixa de sensores (ao vivo ou no replay). */
data class SensorStripValues(
    val gForce: Float?,
    val accelX: Float?,
    val accelY: Float?,
    val accelZ: Float?,
    val pressureHpa: Float?,
    val baroAltitudeM: Float?,
    val pitchDeg: Float?,
    val rollDeg: Float?,
    val temperatureC: Float?,
    val temperatureLabel: String,
)

/**
 * Faixa com 4 blocos de sensores dimensionada para caber sem cortes em ~100 dp por bloco.
 * [onLongPressInclination] (opcional) permite zerar a inclinação segurando o bloco.
 */
@Composable
fun SensorStrip(
    values: SensorStripValues,
    modifier: Modifier = Modifier,
    onLongPressInclination: (() -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SensorTile(
            title = "FORÇA G",
            value = Fmt.num(values.gForce, 2),
            unit = "G",
            valueColor = CockpitColors.Amber,
            lines = listOf(
                "X${Fmt.signed(values.accelX, 2)} Y${Fmt.signed(values.accelY, 2)}",
                "Z${Fmt.signed(values.accelZ, 2)} m/s²",
            ),
            modifier = Modifier.weight(1.15f).fillMaxHeight(),
        )
        SensorTile(
            title = "PRESSÃO hPa",
            value = Fmt.num(values.pressureHpa, 1),
            unit = null,
            lines = listOf("Alt ${Fmt.signed(values.baroAltitudeM, 1)} m"),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        val inclinationModifier = Modifier
            .weight(0.95f)
            .fillMaxHeight()
            .let { base ->
                if (onLongPressInclination == null) {
                    base
                } else {
                    base.pointerInput(onLongPressInclination) {
                        detectTapGestures(onLongPress = { onLongPressInclination() })
                    }
                }
            }
        SensorTile(
            title = "INCLINAÇÃO",
            value = null,
            unit = null,
            lines = listOf(
                "P ${Fmt.signed(values.pitchDeg, 1)}°",
                "R ${Fmt.signed(values.rollDeg, 1)}°",
            ),
            linesSize = 15.sp,
            modifier = inclinationModifier,
        )
        SensorTile(
            title = "TEMP.",
            value = Fmt.num(values.temperatureC, 1),
            unit = "°C",
            lines = listOf(values.temperatureLabel),
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
        )
    }
}

@Composable
private fun SensorTile(
    title: String,
    value: String?,
    unit: String?,
    lines: List<String>,
    modifier: Modifier = Modifier,
    valueColor: Color = CockpitColors.Value,
    linesSize: TextUnit = 11.sp,
) {
    Column(
        modifier
            .background(CockpitColors.Card, RoundedCornerShape(10.dp))
            .border(1.dp, CockpitColors.CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        PanelLabel(title)
        if (value != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    color = if (value == Fmt.NA) CockpitColors.Label else valueColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    softWrap = false,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                if (unit != null && value != Fmt.NA) {
                    Spacer(Modifier.width(3.dp))
                    Text(unit, color = CockpitColors.Label, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        lines.forEach { line ->
            Text(
                line,
                color = CockpitColors.Value,
                fontSize = linesSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }
}
