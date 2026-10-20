# Eco Octano Telemetry

Painel de telemetria (cockpit) e data logger do veículo de eficiência energética da equipe **Eco Octano**, otimizado para o **Samsung Galaxy S23** em paisagem.

## Um app, dois modos

Na primeira abertura, o app pergunta como o aparelho será usado. A escolha fica salva e pode ser trocada a qualquer momento:

| Modo | Uso típico | O que faz | Como trocar |
|---|---|---|---|
| **Display** | Galaxy S23 no carro | Mapa, velocidade, tempo decorrido e distância. Grava a corrida (5 Hz + IMU 100 Hz). Paisagem, tela sempre ligada | Toque na tela → **⋮ → Modo Análise** |
| **Análise** | Galaxy Tab S9, celular ou o próprio S23 | Corridas salvas, replay no mapa, gráficos sincronizados, voltas, importar/exportar | Botão **DISPLAY** no topo da lista |

- **Qualquer aparelho pode gravar e ver os dados.** Basta escolher o modo.
- **Gravação contínua:** se a corrida estiver gravando e você trocar para Análise, a gravação continua em segundo plano. Uma faixa vermelha no topo mostra o tempo e leva de volta ao Display.
- **Corridas na hora:** as corridas salvas aparecem na Análise imediatamente, no mesmo aparelho.
- **Importação direta:** abrir ou compartilhar um `.ecooctano.zip` para o app leva direto ao modo Análise e importa o pacote.

## Como compilar

1. Abra a pasta no **Android Studio**.
2. Conecte o aparelho e clique em ▶. Há uma única variante (`debug`). Pela linha de comando, use `./gradlew assembleDebug`, e o APK sai em `app/build/outputs/apk/debug/`.
3. Instale o mesmo APK em todos os aparelhos (S23, Tab S9 etc.).

Stack: AGP 8.5.2 · Gradle 8.9 · Kotlin 2.0.20 · KSP 2.0.20-1.0.25 · Compose BOM 2024.09.03 (Material 3) · Room 2.6.1 · OSMDroid 6.1.18 · Play Services Location 21.3.0 · minSdk 29 / targetSdk 34.

## Levar corridas para outro aparelho

1. **No S23 (display):** toque na tela para mostrar os controles, depois **⋮ → Corridas salvas**. Use o botão de compartilhar de uma corrida, ou **COMPARTILHAR TODAS**. O app gera um pacote **`.ecooctano.zip`** com tudo: resumo, voltas, pontos de 5 Hz e o arquivo IMU de 100 Hz.
2. **Envie o pacote** pelo meio que preferir: Quick Share (mais rápido entre aparelhos Samsung), Google Drive, WhatsApp, e-mail ou cabo USB. Depois de cada corrida salva, o pacote também fica em `Documents/EcoOctano_Telemetry`.
3. **No Tab S9 (modo Análise):** toque em **IMPORTAR** e escolha o arquivo (Quick Share salva em *Downloads*). Também é possível tocar no arquivo e escolher "Abrir com Eco Octano Análise".
4. Corridas repetidas (mesmo início e duração) são ignoradas. Importar o mesmo pacote duas vezes não duplica nada.

O pacote é um `.zip` comum: no computador, abra e use `session.json` ou `imu.csv` direto em Python, MATLAB ou Excel.

## Proteção contra marcação de tela (modo Display)

A tela OLED do S23 pode reter imagem quando os mesmos pixels ficam acesos por muito tempo. O display faz o seguinte:
- **Deslocamento de pixels:** a cada 30 s, todo o conteúdo anda até 4 dp num ciclo de 9 posições.
- **Controles que somem:** durante a corrida, os botões desaparecem 6 s após o último toque. Um toque os traz de volta.
- **Escurecimento automático:** sem corrida e sem toque por 90 s, a tela escurece. Um toque a acorda.
- **Sem elementos fixos claros:** fundo preto real, sem bordas de cartões, rótulos em cinza escuro e sem barra lateral. O aviso de GPS só aparece quando há problema.

Também ajuda reduzir o brilho quando não estiver sob sol forte e não deixar o app aberto parado fora da corrida.

## Estrutura

```
app/src/main/java/com/ecooctano/telemetry/
├── EcoOctanoApp.kt              # Configuração do OSMDroid (User-Agent, cache de tiles)
├── MainActivity.kt              # Single-Activity; aplica o modo (orientação, tela ligada, sensores)
├── data/
│   ├── model/TelemetryModels.kt # RaceSession, LapData, TelemetryPoint (Room) + TrackPoint
│   ├── db/AppDatabase.kt        # Room DB + DAO
│   ├── export/TelemetryExporter.kt  # CSV + JSON + pacote .ecooctano.zip → Documents/EcoOctano_Telemetry
│   ├── transfer/SessionPackageReader.kt  # Importa pacotes .ecooctano.zip / .json
│   └── TelemetryRepository.kt
├── recording/
│   ├── RaceRecorder.kt            # Dono da corrida (nível do app): gravação 5 Hz, voltas, salvar
│   ├── TelemetryRecordingService.kt  # Serviço em primeiro plano: grava com a tela apagada
│   └── ImuRawLogger.kt            # CSV bruto ~100 Hz (acelerômetro, giroscópio, barômetro)
├── sensors/
│   ├── SensorTelemetryManager.kt  # Acelerômetro linear, giroscópio, vetor de rotação, barômetro, temperatura
│   ├── LocationManager.kt         # FusedLocationProviderClient, alta precisão, 200 ms
│   └── KalmanTrackFilter.kt       # Fusão GPS + acelerômetro com estimativa de viés
└── ui/
    ├── AppMode.kt                 # Modo escolhido (Display / Análise), salvo nas preferências
    ├── screens/ModeSelectionScreen.kt  # Tela de escolha do modo
    ├── display/                   # Modo DISPLAY: cockpit, proteção contra burn-in, corridas salvas
    ├── analysis/                  # Modo ANÁLISE: lista responsiva, replay, gráficos, importação
    ├── map/                       # OSMDroid no Compose + traçado colorido por velocidade
    ├── screens/PermissionGate.kt  # Permissão de localização (só display)
    ├── components/                # Formatação, compartilhamento, itens de lista
    ├── UiEvents.kt                # Snackbar e compartilhamento comuns às duas versões
    └── theme/                     # Tema escuro de alto contraste
```

## Uso na pista

| Ação | Como |
|---|---|
| Mostrar os controles | Toque em qualquer lugar da tela (durante a corrida eles somem após 6 s) |
| Iniciar / pausar / retomar | Botão verde/âmbar **INICIAR** |
| Marcar volta | **VOLTA**: só registra o número da volta nos dados; o piloto vê apenas o tempo decorrido. Toques com menos de 2 s de intervalo são ignorados |
| Zerar a sessão | **RESETAR** e depois confirmar no diálogo |
| Salvar | **SALVAR**: grava no Room e exporta `.csv` + `.json`; o snackbar oferece **COMPARTILHAR** |
| Zerar a inclinação | **⋮ → Zerar inclinação**, com o carro parado no plano |
| Seguir o carro no mapa | Botão de mira no canto do mapa (desligue para arrastar o mapa) |

Os arquivos ficam em `Documents/EcoOctano_Telemetry` (via MediaStore, sem permissão de armazenamento). Se o MediaStore falhar, o app grava em `Android/data/com.ecooctano.telemetry/files/Documents/EcoOctano_Telemetry`.

### Arquivos gerados por corrida

| Arquivo | Taxa | Conteúdo |
|---|---|---|
| `EcoOctano_<data>_S<id>.csv` | 5 Hz | Posição e velocidade **fundidas** (Kalman) + GPS **bruto** + sensores filtrados |
| `EcoOctano_<data>_S<id>_imu.csv` | ~100 Hz | Leituras **brutas**: `ACC` (com gravidade), `LIN` (sem gravidade), `GYR` (rad/s), `BAR` (hPa) |
| `EcoOctano_<data>_S<id>.json` | 5 Hz | Mesmo conteúdo do CSV + voltas e resumo da sessão |

Colunas do CSV de 5 Hz:
`timestamp_epoch_ms, timestamp_iso, elapsed_ms, lap, latitude, longitude, gps_altitude_m, gps_accuracy_m, speed_kmh, accel_x_ms2, accel_y_ms2, accel_z_ms2, g_force, pressure_hpa, baro_altitude_m, pitch_deg, roll_deg, temperature_c, gps_latitude_raw, gps_longitude_raw, gps_speed_raw_kmh`

Colunas do CSV IMU: `race_elapsed_ms, sensor_time_ms, sensor, x, y, z`. O `race_elapsed_ms` usa a mesma base do `elapsed_ms` do CSV de 5 Hz, então os dois arquivos podem ser cruzados.

Uma célula vazia significa sensor indisponível ou GPS sem fix naquele instante.

## Notas de engenharia

- **Inclinação:** pitch e roll vêm do vetor "para cima" (obtido do `ROTATION_VECTOR`) comparado com um referencial do veículo capturado na calibração. Isso funciona com o celular em pé no painel ou deitado, sem o gimbal-lock do `getOrientation()`. Sem `ROTATION_VECTOR`, o app usa um filtro complementar com giroscópio e acelerômetro.
- **Temperatura:** o S23 não tem `TYPE_AMBIENT_TEMPERATURE`, então o app usa a temperatura da bateria (`ACTION_BATTERY_CHANGED`). O valor mede a temperatura interna do aparelho, não a do ar.
- **GPS a 5 Hz:** o app pede 200 ms ao Fused Provider, mas a taxa real depende do chipset e do sinal. A gravação amostra sempre a 5 Hz. Um fix com mais de 2,5 s é tratado como "sinal perdido" e grava lat/lon/velocidade vazios.
- **Mapa:** os segmentos com a mesma cor (matiz quantizado em 5°) são agrupados numa única `Polyline`, o que mantém o mapa fluido em corridas longas. As tiles visualizadas ficam em cache, o que ajuda em pistas com sinal ruim.
- **Filtro de Kalman (GPS + acelerômetro):** estado `[posição, velocidade, viés]` por eixo (leste/norte). A predição roda a 100 Hz com a aceleração linear rotacionada para o mundo, e a correção usa cada fix de GPS (posição + velocidade Doppler). Estimar o viés é o que torna o filtro robusto a erro de orientação. Em simulação (GPS ±4 m a 1 Hz, viés de até 0,4 m/s²), o erro RMS de velocidade caiu de 1,44 para 0,78 km/h e o de posição de 6,8 para 1,5 m. **Isso ainda precisa ser validado na pista**, comparando `speed_kmh` com `gps_speed_raw_kmh`.
- **Tela apagada:** ao INICIAR, um serviço em primeiro plano (notificação "Eco Octano Telemetry") mantém GPS, sensores e gravação ativos com a tela apagada ou com outro app aberto. Ele é encerrado ao SALVAR ou RESETAR.
- **Bateria no S23:** em *Configurações → Aplicativos → Eco Octano Telemetry → Bateria*, escolha **Sem restrições**. Sem isso, a economia de energia da Samsung pode encerrar o app durante corridas longas.
