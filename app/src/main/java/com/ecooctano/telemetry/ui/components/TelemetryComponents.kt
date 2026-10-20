package com.ecooctano.telemetry.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
