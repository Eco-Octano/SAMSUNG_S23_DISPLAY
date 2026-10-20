package com.ecooctano.telemetry.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ecooctano.telemetry.data.model.RaceSession
import com.ecooctano.telemetry.ui.UiMessage
import com.ecooctano.telemetry.ui.theme.CockpitColors
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Liga o canal de eventos do app à UI: mostra mensagens no snackbar (com ação
 * COMPARTILHAR quando há arquivos) e abre o seletor de compartilhamento do Android.
 */
@Composable
fun AppEventsEffect(
    message: UiMessage?,
    shareRequests: SharedFlow<List<File>>,
    snackbarHostState: SnackbarHostState,
    onConsume: (Long) -> Unit,
) {
    val context = LocalContext.current
    val consume by rememberUpdatedState(onConsume)
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
        consume(m.id)
    }
    LaunchedEffect(shareRequests) {
        shareRequests.collect { files -> shareFiles(context, files) }
    }
}

/** Compartilha arquivos via FileProvider (Quick Share, WhatsApp, Drive, e-mail...). */
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

        val allZip = files.all { it.name.endsWith(".zip") }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }.apply {
            type = if (allZip) "application/zip" else "*/*"
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

/**
 * Linha de uma corrida salva, usada nas duas versões.
 * [onShareCsv] é opcional; [onOpen] mostra o botão ABRIR quando informado.
 */
@Composable
fun SessionListItem(
    session: RaceSession,
    selected: Boolean,
    onSharePackage: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onShareCsv: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF10293A) else CockpitColors.Card,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
        onClick = { onOpen?.invoke() },
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "#${session.id}  ${session.name}",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(Fmt.dateTime(session.startedAtEpochMs), color = CockpitColors.Label, fontSize = 12.sp)
                Text(
                    "${Fmt.elapsed(session.durationMs)} · ${Fmt.distance(session.distanceMeters)} · " +
                        "máx ${Fmt.num(session.maxSpeedKmh, 1)} km/h" +
                        if (session.imuFileName != null) " · IMU" else "",
                    color = CockpitColors.Value,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSharePackage, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Compartilhar pacote", tint = CockpitColors.Brand)
            }
            if (onShareCsv != null) {
                IconButton(onClick = onShareCsv, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.TableChart, contentDescription = "Compartilhar CSV", tint = CockpitColors.Label)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = CockpitColors.Red)
            }
        }
    }
}

/** Confirmação de exclusão de corrida. */
@Composable
fun DeleteSessionDialog(session: RaceSession, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Excluir corrida?") },
        text = { Text("\"${session.name}\" será removida deste aparelho. Arquivos já exportados não são apagados.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Red),
            ) { Text("EXCLUIR", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCELAR") } },
    )
}

/** Explica, uma vez, o que é o pacote .ecooctano.zip. */
@Composable
fun PackageHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "O botão de compartilhar gera um pacote .ecooctano.zip com todos os dados (5 Hz + IMU 100 Hz).",
            color = CockpitColors.Label,
            fontSize = 12.sp,
        )
        Text(
            "Envie por Quick Share, Drive ou cabo e use IMPORTAR no app Eco Octano Análise.",
            color = CockpitColors.Label,
            fontSize = 12.sp,
        )
    }
}
