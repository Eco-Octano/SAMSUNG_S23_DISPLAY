package com.ecooctano.telemetry.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecooctano.telemetry.ui.components.Fmt
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cores das séries: os 3 primeiros tons categóricos do modo escuro, validados para
 * daltonismo em qualquer par sobre a superfície dos cards (#161616).
 * Texto nunca usa a cor da série (só as amostras de cor carregam a identidade).
 */
object ChartColors {
    val Series1 = Color(0xFF3987E5) // azul
    val Series2 = Color(0xFFD95926) // laranja
    val Series3 = Color(0xFF199E70) // verde-água
    val Surface = Color(0xFF161616)
    val Grid = Color(0xFF2C2C2A)
    val Axis = Color(0xFF383835)
    val Muted = Color(0xFF898781)
    val Ink = Color(0xFFF0EFEC)
    val Secondary = Color(0xFFC3C2B7)
}

@Immutable
class ChartSeries(val label: String, val values: FloatArray, val color: Color)

/**
 * Gráfico de linha de uma grandeza no tempo, com um único eixo Y.
 * - Desenha a envoltória mín/máx por coluna de pixel: picos curtos (ex.: força G) não somem.
 * - Cursor vertical na posição do replay; toque ou arraste horizontal navega ([onSeek]).
 */
@Composable
fun TelemetryChart(
    title: String,
    unit: String,
    decimals: Int,
    timesMs: LongArray,
    series: List<ChartSeries>,
    startMs: Long,
    endMs: Long,
    cursorMs: Long,
    cursorIndex: Int,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 140.dp,
) {
    val textMeasurer = rememberTextMeasurer()
    val seek by rememberUpdatedState(onSeek)
    val span = (endMs - startMs).coerceAtLeast(1L)

    // Faixa do eixo Y (calculada uma vez por conjunto de séries).
    val yRange = remember(series) {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (s in series) for (v in s.values) {
            if (v.isFinite()) {
                lo = min(lo, v)
                hi = max(hi, v)
            }
        }
        if (!lo.isFinite()) {
            0f to 1f
        } else {
            val pad = max((hi - lo) * 0.08f, if (hi == lo) max(abs(hi) * 0.1f, 1f) else 0f)
            (lo - pad) to (hi + pad)
        }
    }
    val hasData = remember(series) { series.any { s -> s.values.any { it.isFinite() } } }

    Column(
        modifier
            .background(ChartColors.Surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Cabeçalho: título + leitura no cursor (texto em tinta neutra; cor só na amostra).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = ChartColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text(unit, color = ChartColors.Muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            series.forEach { s ->
                val v = s.values.getOrNull(cursorIndex)
                if (series.size > 1) {
                    Box(Modifier.size(width = 12.dp, height = 3.dp).background(s.color, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(4.dp))
                    Text("${s.label} ", color = ChartColors.Muted, fontSize = 12.sp)
                }
                Text(
                    Fmt.num(v, decimals),
                    color = ChartColors.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Spacer(Modifier.width(10.dp))
            }
        }
        Spacer(Modifier.height(6.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(plotHeight)
                .pointerInput(startMs, endMs) {
                    detectTapGestures { pos -> seek(xToMs(pos.x, size.width.toFloat(), startMs, span)) }
                }
                .pointerInput(startMs, endMs) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        seek(xToMs(change.position.x, size.width.toFloat(), startMs, span))
                    }
                },
        ) {
            if (!hasData) {
                Text("Sem dados deste sensor", color = ChartColors.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                // Camada 1: grade + séries (recalculada só quando muda o tamanho ou os dados).
                val (yLo, yHi) = yRange
                val dataLayer = remember(series, timesMs, startMs, endMs, yRange) {
                    Modifier.drawWithCache {
                        val w = size.width
                        val h = size.height
                        val paths = series.map { s -> s.color to buildEnvelopePath(timesMs, s.values, startMs, span, yLo, yHi, w, h) }
                        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val gridStroke = 1.dp.toPx()
                        onDrawBehind {
                            for (i in 0..3) {
                                val y = h * i / 3f
                                drawLine(ChartColors.Grid, Offset(0f, y), Offset(w, y), gridStroke)
                            }
                            for ((color, path) in paths) drawPath(path, color, style = stroke)
                        }
                    }
                }
                Box(Modifier.fillMaxSize().then(dataLayer))

                // Camada 2: rótulos do eixo e cursor (barata, redesenha a cada quadro do replay).
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val labelStyle = TextStyle(color = ChartColors.Muted, fontSize = 10.sp)
                    for (i in 0..3) {
                        val value = yHi - (yHi - yLo) * i / 3f
                        val layout = textMeasurer.measure(String.format(Locale.US, "%.${decimals}f", value), labelStyle)
                        val y = (h * i / 3f).coerceIn(0f, h - layout.size.height)
                        drawText(layout, topLeft = Offset(2f, if (i == 0) y else y - layout.size.height))
                    }
                    val x = ((cursorMs - startMs).toFloat() / span) * w
                    drawLine(ChartColors.Secondary, Offset(x, 0f), Offset(x, h), 1.dp.toPx())
                    for (s in series) {
                        val v = s.values.getOrNull(cursorIndex) ?: continue
                        if (!v.isFinite()) continue
                        val y = h - (v - yLo) / (yHi - yLo) * h
                        drawCircle(ChartColors.Surface, radius = 6.dp.toPx(), center = Offset(x, y))
                        drawCircle(s.color, radius = 4.dp.toPx(), center = Offset(x, y))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Fmt.elapsed(0L), color = ChartColors.Muted, fontSize = 10.sp)
            Text(Fmt.elapsed(span), color = ChartColors.Muted, fontSize = 10.sp)
        }
    }
}

private fun xToMs(x: Float, width: Float, startMs: Long, span: Long): Long {
    if (width <= 0f) return startMs
    return startMs + ((x / width).coerceIn(0f, 1f) * span).toLong()
}

/**
 * Caminho com no máximo ~2 vértices por coluna de pixel (mín e máx de cada coluna).
 * NaN interrompe a linha (sem dado = sem traço).
 */
private fun buildEnvelopePath(
    times: LongArray,
    values: FloatArray,
    startMs: Long,
    span: Long,
    yLo: Float,
    yHi: Float,
    w: Float,
    h: Float,
): Path {
    val path = Path()
    val n = min(times.size, values.size)
    if (n == 0 || w <= 0f || yHi <= yLo) return path
    val cols = max(1, w.toInt())
    val colMin = FloatArray(cols) { Float.NaN }
    val colMax = FloatArray(cols) { Float.NaN }
    for (i in 0 until n) {
        val v = values[i]
        if (!v.isFinite()) continue
        val c = (((times[i] - startMs).toFloat() / span) * (cols - 1)).toInt().coerceIn(0, cols - 1)
        if (colMin[c].isNaN() || v < colMin[c]) colMin[c] = v
        if (colMax[c].isNaN() || v > colMax[c]) colMax[c] = v
    }
    fun y(v: Float) = h - (v - yLo) / (yHi - yLo) * h
    var penDown = false
    var emptyRun = 0
    for (c in 0 until cols) {
        if (colMin[c].isNaN()) {
            // Buracos de até 3 colunas são ligados; maiores interrompem a linha.
            emptyRun++
            if (emptyRun > 3) penDown = false
            continue
        }
        emptyRun = 0
        val x = c.toFloat() * w / cols
        if (!penDown) {
            path.moveTo(x, y(colMin[c]))
            penDown = true
        } else {
            path.lineTo(x, y(colMin[c]))
        }
        if (colMax[c] != colMin[c]) path.lineTo(x, y(colMax[c]))
    }
    return path
}
