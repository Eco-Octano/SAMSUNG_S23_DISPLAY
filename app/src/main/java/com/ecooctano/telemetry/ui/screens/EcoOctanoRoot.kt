package com.ecooctano.telemetry.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ecooctano.telemetry.ui.TelemetryViewModel
import com.ecooctano.telemetry.ui.theme.CockpitColors
import java.io.File

/** Estrutura Single-Activity: trilho de navegação (Ao Vivo / Retrospectiva) + conteúdo. */
@Composable
fun EcoOctanoRoot(viewModel: TelemetryViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        val canShare = m.shareFiles.isNotEmpty()
        val result = snackbarHostState.showSnackbar(
            message = m.text,
            actionLabel = if (canShare) "COMPARTILHAR" else null,
            withDismissAction = true,
            duration = if (canShare) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && canShare) shareFiles(context, m.shareFiles)
        viewModel.consumeMessage(m.id)
    }

    LaunchedEffect(viewModel) {
        viewModel.shareRequests.collect { files -> shareFiles(context, files) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(CockpitColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight(),
                containerColor = CockpitColors.Surface,
                windowInsets = WindowInsets(0, 0, 0, 0),
                header = {
                    Text(
                        "ECO\nOCTANO",
                        color = CockpitColors.Green,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        lineHeight = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                },
            ) {
                val itemColors = NavigationRailItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    selectedTextColor = CockpitColors.Green,
                    indicatorColor = CockpitColors.Green,
                    unselectedIconColor = CockpitColors.Label,
                    unselectedTextColor = CockpitColors.Label,
                )
                NavigationRailItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                    label = { Text("AO VIVO", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = itemColors,
                )
                NavigationRailItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("REPLAY", fontSize = 10.sp, fontWeight = FontWeight.Bold) },
                    colors = itemColors,
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (tab) {
                    0 -> LiveDashboardScreen(viewModel)
                    else -> ReplayScreen(viewModel)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
    }
}

/** Compartilha os arquivos exportados via FileProvider (WhatsApp, Drive, e-mail...). */
fun shareFiles(context: Context, files: List<File>) {
    try {
        val authority = "${context.packageName}.fileprovider"
        val uris = ArrayList<Uri>()
        for (file in files) {
            if (!file.exists()) continue
            try {
                uris.add(FileProvider.getUriForFile(context, authority, file))
            } catch (e: IllegalArgumentException) {
                Log.w("ShareFiles", "Arquivo fora do FileProvider: ${file.absolutePath}", e)
            }
        }
        if (uris.isEmpty()) return

        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "Telemetria Eco Octano")
            val clip = ClipData.newRawUri(null, uris.first())
            for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Compartilhar telemetria").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        Log.e("ShareFiles", "Falha ao compartilhar", e)
    }
}
