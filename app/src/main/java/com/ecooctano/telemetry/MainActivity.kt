package com.ecooctano.telemetry

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ecooctano.telemetry.ui.analysis.AnalysisRoot
import com.ecooctano.telemetry.ui.analysis.AnalysisViewModel
import com.ecooctano.telemetry.ui.display.DisplayRoot
import com.ecooctano.telemetry.ui.display.DisplayViewModel
import com.ecooctano.telemetry.ui.screens.LocationPermissionGate
import com.ecooctano.telemetry.ui.theme.EcoOctanoTheme

/**
 * Activity única das duas versões. O flavor de build define qual UI sobe:
 *  - display  → cockpit do carro (tela sempre ligada, imersivo, GPS e sensores);
 *  - analysis → análise de dados (importa pacotes, replay, gráficos).
 */
class MainActivity : ComponentActivity() {

    private val isAnalysis = BuildConfig.APP_MODE == "analysis"

    // Delegados preguiçosos: só o ViewModel da versão em uso é criado.
    private val displayViewModel: DisplayViewModel by viewModels()
    private val analysisViewModel: AnalysisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isAnalysis) {
            setContent {
                EcoOctanoTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AnalysisRoot(analysisViewModel)
                    }
                }
            }
            if (savedInstanceState == null) handleIncomingFiles(intent)
            return
        }

        // Display: tela do S23 nunca apaga durante a corrida.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        setContent {
            EcoOctanoTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LocationPermissionGate(onGranted = displayViewModel::onLocationPermissionGranted) {
                        DisplayRoot(displayViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAnalysis) handleIncomingFiles(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!isAnalysis) displayViewModel.onAppForeground()
    }

    override fun onStop() {
        if (!isAnalysis) displayViewModel.onAppBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isAnalysis) enterImmersiveMode()
    }

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

    /** Cockpit em tela cheia; barras do sistema aparecem só com gesto de deslizar. */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
