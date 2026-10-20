package com.ecooctano.telemetry

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.ui.AppMode
import com.ecooctano.telemetry.ui.analysis.AnalysisRoot
import com.ecooctano.telemetry.ui.analysis.AnalysisViewModel
import com.ecooctano.telemetry.ui.display.DisplayRoot
import com.ecooctano.telemetry.ui.display.DisplayViewModel
import com.ecooctano.telemetry.ui.screens.LocationPermissionGate
import com.ecooctano.telemetry.ui.screens.ModeSelectionScreen
import com.ecooctano.telemetry.ui.theme.EcoOctanoTheme

/**
 * Activity única com seleção de modo:
 *  - Display  → cockpit do carro (grava; tela sempre ligada; paisagem; imersivo);
 *  - Análise  → corridas, replay, gráficos, importação/exportação (qualquer orientação).
 * A gravação pertence ao app (RaceRecorder), então continua ao trocar de modo.
 */
class MainActivity : ComponentActivity() {

    private val modeStore by lazy { (application as EcoOctanoApp).modeStore }

    // Delegados preguiçosos: cada ViewModel só é criado quando o modo dele é usado.
    private val displayViewModel: DisplayViewModel by viewModels()
    private val analysisViewModel: AnalysisViewModel by viewModels()

    private var started = false
    /** Modo cujos efeitos (janela, sensores) estão aplicados no momento. */
    private var appliedMode: AppMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Um pacote aberto/compartilhado para o app leva direto ao modo Análise.
        val incoming = savedInstanceState == null && hasIncomingFiles(intent)
        if (incoming) modeStore.select(AppMode.ANALYSIS)

        setContent {
            EcoOctanoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val mode by modeStore.mode.collectAsStateWithLifecycle()
                    LaunchedEffect(mode) { applyMode(mode) }
                    when (mode) {
                        null -> ModeSelectionScreen(onSelect = modeStore::select)
                        AppMode.DISPLAY ->
                            LocationPermissionGate(onGranted = displayViewModel::onLocationPermissionGranted) {
                                DisplayRoot(displayViewModel, onSwitchToAnalysis = { modeStore.select(AppMode.ANALYSIS) })
                            }
                        AppMode.ANALYSIS ->
                            AnalysisRoot(analysisViewModel, onSwitchToDisplay = { modeStore.select(AppMode.DISPLAY) })
                    }
                }
            }
        }
        if (incoming) handleIncomingFiles(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (hasIncomingFiles(intent)) {
            modeStore.select(AppMode.ANALYSIS)
            handleIncomingFiles(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (appliedMode == AppMode.DISPLAY) displayViewModel.onAppForeground()
    }

    override fun onStop() {
        started = false
        if (appliedMode == AppMode.DISPLAY) displayViewModel.onAppBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && appliedMode == AppMode.DISPLAY) setSystemBarsHidden(true)
    }

    /** Aplica janela, orientação e sensores do modo escolhido. */
    private fun applyMode(mode: AppMode?) {
        val previous = appliedMode
        appliedMode = mode
        if (mode == AppMode.DISPLAY) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            setSystemBarsHidden(true)
            if (started) displayViewModel.onAppForeground()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            setSystemBarsHidden(false)
            // Sai do Display: desliga GPS/sensores, a menos que haja corrida em andamento.
            if (previous == AppMode.DISPLAY) displayViewModel.onAppBackground()
        }
    }

    /** Display em tela cheia; nos outros modos as barras do sistema ficam visíveis. */
    private fun setSystemBarsHidden(hidden: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            if (hidden) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun hasIncomingFiles(intent: Intent?): Boolean =
        intent?.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)

    /** Pacotes recebidos por "Abrir com" (VIEW) ou "Compartilhar" (SEND / SEND_MULTIPLE). */
    private fun handleIncomingFiles(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) analysisViewModel.importUris(uris)
    }
}
