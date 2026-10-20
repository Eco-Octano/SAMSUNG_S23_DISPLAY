package com.ecooctano.telemetry.ui.map

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Escala de cores por velocidade (interpolação de matiz HSV):
 *  - 0..15 km/h   : Azul (240°)   -> Ciano (180°)
 *  - 15..30 km/h  : Verde (120°)  -> Amarelo (60°)
 *  - > 30 km/h    : Laranja (30°) -> Vermelho (0°) (saturando em 45 km/h)
 *
 * O matiz é quantizado em passos de 5° ("buckets") para que segmentos consecutivos com a
 * mesma cor sejam agrupados numa única Polyline — mantém o mapa leve em corridas longas.
 */
object SpeedColors {
    private const val HUE_STEP = 5f
    private const val TOP_SPEED_KMH = 45f

    fun hueFor(speedKmh: Float): Float {
        if (!speedKmh.isFinite() || speedKmh <= 0f) return 240f
        return when {
            speedKmh < 15f -> lerp(240f, 180f, speedKmh / 15f)
            speedKmh < 30f -> lerp(120f, 60f, (speedKmh - 15f) / 15f)
            else -> lerp(30f, 0f, ((speedKmh - 30f) / (TOP_SPEED_KMH - 30f)).coerceIn(0f, 1f))
        }
    }

    fun bucketFor(speedKmh: Float): Int = (hueFor(speedKmh) / HUE_STEP).roundToInt()

    fun colorForBucket(bucket: Int): Int =
        android.graphics.Color.HSVToColor(floatArrayOf((bucket * HUE_STEP).coerceIn(0f, 359f), 1f, 1f))

    fun argbFor(speedKmh: Float): Int = colorForBucket(bucketFor(speedKmh))

    fun composeColor(speedKmh: Float?): Color =
        if (speedKmh == null || !speedKmh.isFinite()) Color.White else Color(argbFor(speedKmh))

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
