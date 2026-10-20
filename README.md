# Eco Octano Telemetry

Painel de telemetria (cockpit) e data logger do veículo de eficiência energética da equipe **Eco Octano**, otimizado para o **Samsung Galaxy S23** em paisagem.

## Como compilar

1. Abra a pasta no **Android Studio** (Koala 2024.1.1 ou mais recente). Ele cria o `local.properties` com o caminho do SDK.
2. Instale o SDK Platform **API 34** se o Studio pedir.
3. Rode a configuração `app` no S23 (com depuração USB ligada) ou use `./gradlew assembleDebug`.

Stack: AGP 8.5.2 · Gradle 8.9 · Kotlin 2.0.20 · KSP 2.0.20-1.0.25 · Compose BOM 2024.09.03 (Material 3) · Room 2.6.1 · OSMDroid 6.1.18 · Play Services Location 21.3.0 · minSdk 29 / targetSdk 34.

## Estrutura

```
app/src/main/java/com/ecooctano/telemetry/
├── EcoOctanoApp.kt              # Configuração do OSMDroid (User-Agent, cache de tiles)
├── MainActivity.kt              # Single-Activity, KEEP_SCREEN_ON, imersivo, ciclo de vida
├── data/
│   ├── model/TelemetryModels.kt # RaceSession, LapData, TelemetryPoint (Room) + TrackPoint
│   ├── db/AppDatabase.kt        # Room DB + DAO
│   ├── export/TelemetryExporter.kt  # CSV + JSON → Documents/EcoOctano_Telemetry
│   └── TelemetryRepository.kt
├── sensors/
│   ├── SensorTelemetryManager.kt  # Acelerômetro linear, giroscópio, vetor de rotação, barômetro, temperatura
│   └── LocationManager.kt         # FusedLocationProviderClient, alta precisão, 200 ms
└── ui/
    ├── TelemetryViewModel.kt      # Estado do modo Ao Vivo e do modo Retrospectiva
    ├── map/                       # OSMDroid no Compose + traçado colorido por velocidade
    ├── screens/                   # LiveDashboard, Replay, PermissionGate, Root
    ├── components/                # Cards, formatação ("N/A")
    └── theme/                     # Tema escuro de alto contraste
```

## Uso na pista

| Ação | Como |
|---|---|
| Iniciar / pausar / retomar | Botão verde/âmbar **INICIAR** |
| Fechar volta | **NOVA VOLTA** (toques com menos de 2 s de intervalo são ignorados) |
| Zerar a sessão | **RESETAR** e depois confirmar no diálogo |
| Salvar | **SALVAR**: grava no Room e exporta `.csv` + `.json`; o snackbar oferece **COMPARTILHAR** |
| Zerar a inclinação | Pressione e segure o card **INCLINAÇÃO** com o carro parado no plano |
| Seguir o carro no mapa | Botão de mira no canto do mapa (desligue para arrastar o mapa) |

Os arquivos ficam em `Documents/EcoOctano_Telemetry` (via MediaStore, sem permissão de armazenamento). Se o MediaStore falhar, o app grava em `Android/data/com.ecooctano.telemetry/files/Documents/EcoOctano_Telemetry`.

### Colunas do CSV
`timestamp_epoch_ms, timestamp_iso, elapsed_ms, lap, latitude, longitude, gps_altitude_m, gps_accuracy_m, speed_kmh, accel_x_ms2, accel_y_ms2, accel_z_ms2, g_force, pressure_hpa, baro_altitude_m, pitch_deg, roll_deg, temperature_c`

Uma célula vazia significa sensor indisponível ou GPS sem fix naquele instante.

## Notas de engenharia

- **Inclinação:** pitch e roll vêm do vetor "para cima" (obtido do `ROTATION_VECTOR`) comparado com um referencial do veículo capturado na calibração. Isso funciona com o celular em pé no painel ou deitado, sem o gimbal-lock do `getOrientation()`. Sem `ROTATION_VECTOR`, o app usa um filtro complementar com giroscópio e acelerômetro.
- **Temperatura:** o S23 não tem `TYPE_AMBIENT_TEMPERATURE`, então o app usa a temperatura da bateria (`ACTION_BATTERY_CHANGED`). O valor mede a temperatura interna do aparelho, não a do ar.
- **GPS a 5 Hz:** o app pede 200 ms ao Fused Provider, mas a taxa real depende do chipset e do sinal. A gravação amostra sempre a 5 Hz. Um fix com mais de 2,5 s é tratado como "sinal perdido" e grava lat/lon/velocidade vazios.
- **Mapa:** os segmentos com a mesma cor (matiz quantizado em 5°) são agrupados numa única `Polyline`, o que mantém o mapa fluido em corridas longas. As tiles visualizadas ficam em cache, o que ajuda em pistas com sinal ruim.
- **Segundo plano:** a corrida continua gravando se o app sair da tela, mas o Android pode reduzir a taxa do GPS. Para garantir a gravação com a tela apagada, o próximo passo seria um Foreground Service de localização.
