package com.ecooctano.telemetry.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecooctano.telemetry.R
import com.ecooctano.telemetry.ui.AppMode
import com.ecooctano.telemetry.ui.theme.CockpitColors

/** Primeira tela: escolha do modo. A escolha fica salva e pode ser trocada depois pelos menus. */
@Composable
fun ModeSelectionScreen(onSelect: (AppMode) -> Unit) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        val sideBySide = maxWidth >= 640.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 900.dp)) {
            Image(painterResource(R.drawable.eco_octano_logo), contentDescription = "Eco Octano", modifier = Modifier.size(84.dp))
            Spacer(Modifier.height(10.dp))
            Text("Como este aparelho será usado?", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Você pode trocar a qualquer momento pelo menu.", color = CockpitColors.Label, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            val display: @Composable (Modifier) -> Unit = { m ->
                ModeCard(
                    icon = Icons.Filled.Speed,
                    title = "DISPLAY",
                    subtitle = "Painel do carro",
                    lines = listOf(
                        "Grava a corrida (GPS + sensores)",
                        "Mapa, velocidade, tempo e distância",
                        "Tela sempre ligada, proteção contra burn-in",
                    ),
                    accent = CockpitColors.Green,
                    onClick = { onSelect(AppMode.DISPLAY) },
                    modifier = m,
                )
            }
            val analysis: @Composable (Modifier) -> Unit = { m ->
                ModeCard(
                    icon = Icons.Filled.Insights,
                    title = "ANÁLISE",
                    subtitle = "Dados das corridas",
                    lines = listOf(
                        "Replay no mapa e gráficos sincronizados",
                        "Importar e exportar pacotes de corridas",
                        "Funciona em celular e tablet",
                    ),
                    accent = CockpitColors.Brand,
                    onClick = { onSelect(AppMode.ANALYSIS) },
                    modifier = m,
                )
            }
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    display(Modifier.weight(1f))
                    analysis(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    display(Modifier.fillMaxWidth())
                    analysis(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    lines: List<String>,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CockpitColors.Card),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(subtitle, color = CockpitColors.Label, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            lines.forEach { Text("• $it", color = CockpitColors.Value, fontSize = 14.sp) }
        }
    }
}
