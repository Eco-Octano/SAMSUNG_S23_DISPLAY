package com.ecooctano.telemetry.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ecooctano.telemetry.ui.theme.CockpitColors

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private fun hasFineLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Libera [content] somente com localização PRECISA concedida.
 * Revalida ao voltar das Configurações do sistema (ON_RESUME).
 */
@Composable
fun LocationPermissionGate(
    onGranted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(hasFineLocation(context)) }
    var denials by rememberSaveable { mutableIntStateOf(0) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || hasFineLocation(context)
        if (!granted) denials++
    }

    LaunchedEffect(Unit) {
        if (!granted && !requestedOnce) {
            requestedOnce = true
            launcher.launch(LOCATION_PERMISSIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasFineLocation(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(granted) {
        if (granted) onGranted()
    }

    if (granted) {
        content()
    } else {
        PermissionRequiredScreen(
            showSettingsHint = denials >= 2,
            onRetry = { launcher.launch(LOCATION_PERMISSIONS) },
            onOpenSettings = { openAppSettings(context) },
        )
    }
}

@Composable
private fun PermissionRequiredScreen(
    showSettingsHint: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = CockpitColors.Amber, modifier = Modifier.size(56.dp))
        Text(
            "Localização precisa necessária",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            "O painel usa o GPS a 5 Hz para velocidade, voltas e traçado no mapa.\n" +
                "Conceda a permissão de localização PRECISA para continuar." +
                if (showSettingsHint) "\nSe o diálogo não aparecer mais, ative em Configurações > Permissões > Localização." else "",
            color = CockpitColors.Label,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onRetry,
                modifier = Modifier.height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CockpitColors.Green, contentColor = Color.Black),
            ) { Text("TENTAR NOVAMENTE", fontWeight = FontWeight.Black, fontSize = 16.sp) }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.height(60.dp),
            ) { Text("ABRIR CONFIGURAÇÕES", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Nada a fazer: o botão TENTAR NOVAMENTE continua disponível.
        }
    }
}
