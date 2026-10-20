package com.ecooctano.telemetry.ui.display

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Proteções contra marcação de tela (burn-in / retenção de imagem) do OLED do S23.
 *
 * O que causa a marcação é um mesmo pixel aceso, sempre igual, por muito tempo:
 * bordas de cartões, rótulos fixos, ícones, a barra lateral. As medidas aqui:
 *  1. Deslocamento de pixels: todo o conteúdo anda alguns dp em um ciclo lento, então
 *     nenhuma borda fica parada sobre os mesmos pixels.
 *  2. Controles somem sozinhos durante a corrida e voltam com um toque.
 *  3. Escurecimento automático quando o painel fica parado sem corrida.
 * O layout do display também evita elementos estáticos claros (sem bordas, rótulos
 * escuros, fundo preto real).
 */
object BurnInProtection {
    /** Intervalo entre deslocamentos. */
    const val SHIFT_INTERVAL_MS = 30_000L
    /** Amplitude máxima do deslocamento (dp). */
    const val SHIFT_MAX_DP = 4f
    /** Controles visíveis após um toque, durante a corrida. */
    const val CONTROLS_TIMEOUT_MS = 6_000L
    /** Sem corrida e sem toque por esse tempo, a tela escurece. */
    const val IDLE_DIM_TIMEOUT_MS = 90_000L

    /** Órbita em 9 posições (dp), percorrida em ~4,5 min. */
    val ORBIT: List<Pair<Float, Float>> = listOf(
        0f to 0f, 1f to -1f, 1f to 1f, -1f to 1f, -1f to -1f,
        0.5f to 0f, 0f to 0.5f, -0.5f to 0f, 0f to -0.5f,
    )
}

/** Deslocamento atual (px) do ciclo de proteção. */
@Composable
fun rememberPixelShift(): IntOffset {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BurnInProtection.SHIFT_INTERVAL_MS)
            step = (step + 1) % BurnInProtection.ORBIT.size
        }
    }
    val maxPx = with(LocalDensity.current) { BurnInProtection.SHIFT_MAX_DP.dp.toPx() }
    val (fx, fy) = BurnInProtection.ORBIT[step]
    return IntOffset((fx * maxPx).roundToInt(), (fy * maxPx).roundToInt())
}

/** Instante (elapsedRealtime) do último toque na tela + relógio que avança a cada segundo. */
class InteractionClock {
    var lastTouchMs by mutableLongStateOf(SystemClock.elapsedRealtime())
    var nowMs by mutableLongStateOf(SystemClock.elapsedRealtime())

    fun touched() {
        lastTouchMs = SystemClock.elapsedRealtime()
        nowMs = lastTouchMs
    }

    val idleMs: Long get() = (nowMs - lastTouchMs).coerceAtLeast(0L)
}

@Composable
fun rememberInteractionClock(): InteractionClock {
    val clock = remember { InteractionClock() }
    LaunchedEffect(clock) {
        while (true) {
            delay(500L)
            clock.nowMs = SystemClock.elapsedRealtime()
        }
    }
    return clock
}

/** Observa toques sem consumi-los (os botões e o mapa continuam funcionando). */
fun Modifier.observeTouches(clock: InteractionClock): Modifier =
    pointerInput(clock) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                clock.touched()
            }
        }
    }
