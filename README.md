# Scanner Campo

App Android próprio para o scanner **GeoSun GS-102G**, feito a partir da leitura do protocolo do FireEye 3.2.0. Não usa nem altera o app
da GeoSun (e o FireEye e o Scanner Campo não devem ficar conectados ao scanner ao mesmo tempo).

Kotlin, sem bibliotecas externas além do Android SDK. Pacote `br.scanner.campo`.

## O que faz
Conecta no Wi-Fi do scanner (rede `AGS…`, aberta; `192.168.2.11:8080`), mostra em qualquer aba a **faixa do SLAM** (normal / atenção /
perdido, com apito e vibração), o estado do scanner, bateria, memória, GNSS, RTK (cliente **NTRIP** próprio) e precisão; inicia/pausa/continua/para o
levantamento (nome com data e hora; partes `_p2`, `_p3`…), pausa sozinho quando o SLAM se perde, mostra o resumo no fim e grava ponto de controle. Cada levantamento grava um CSV com todos os quadros e o `diagnostico_scanner.txt` em
`Android/data/br.scanner.campo/files/logs_slam/`. Detalhes de uso: `LEIAME.txt`.

## Código (`app/src/main/java/br/scanner/campo/`)
- `Protocolo.kt` — quadros `HOLD` + comando de 4 bytes + tamanho + dados (envio e leitura da nuvem/estado/GNSS/IMU…).
- `ConectorScanner.kt` — achar e conectar a rede Wi-Fi do scanner e abrir o socket.
- `MonitorSlam.kt` — alarme do SLAM (posição tremendo, ambiente pobre contra o normal do levantamento, velocidade/salto, tempo
  sem quadro) e ponto de retomada; limites calibrados com 2 testes reais, ainda **provisórios**.
- `NuvemAcumulada.kt`, `Visualizador3D.kt`, `Nuvem3DActivity.kt` — prévia da nuvem (voxel de 10 cm, gravada em
  `logs_slam/nuvem_*.bin`) e visualizador 3D em OpenGL ES 2.0 (girar, mover, zoom); `Telhado.kt` tira o teto da visão (camada de cima com vão embaixo).
- `PontoGnss.kt`, `Geodesia.kt`, `AbaGnss.kt` — cadastro de pontos só com o RTK do scanner (braço da âncora ou bastão
  rosqueado; duas leituras com o scanner girado 180° cancelam o afastamento da antena), UTM GRS80/SIRGAS 2000 e CSV
  `logs_slam/pontos_rtk_<obra>.csv` no formato dos coletores.
- `AuxiliarGnss.kt` + `assets/scanner_campo_gnss.py`, `Adb.kt` — GNSS sem levantamento: script instalado pelo adb (Wi-Fi) em
  `/tmp` do scanner, que configura o receptor (ttyS1, 460800) e serve o NMEA na porta 8081; solta a serial quando o `pos` roda.
  `Sonda.kt`, `InvestigacaoGnss.kt` — portas e investigação só de leitura do scanner.
- `RumoCelular.kt`, `Excentricidade.kt` — rumo pelo celular preso no scanner (vetor de rotação do Android, norte da quadrícula),
  calibração do afastamento da antena por uma leitura dupla, ponto com 1 leitura e conferência pela trajetória do GNSS.
- `ImuScanner.kt` — IMU do scanner pelo auxiliar (ttyS0, quadros 55 AA 24 de 40 bytes): bolha eletrônica, temperatura e
  giro que filtra o rumo do celular (`RumoFundido`).
- `Semaforo.kt` — resumo verde/amarelo/vermelho do fim do levantamento (perdido, tremor, ambiente pobre, fechamento, GNSS × SLAM).
- `Ntrip.kt`, `Gga.kt` — cliente NTRIP (mountpoints, correções RTCM) e leitura de GGA/GST.
- `MainActivity.kt`, `Estilo.kt`, `VistaTopo.kt` — interface, tema e vista de cima do caminho. `Diagnostico.kt`, `Equipamento.kt` — registros.
- Testes em `app/src/test` (protocolo, GGA, alarme do SLAM, NTRIP contra caster simulado).

## Compilar
`compilar.bat` (duplo clique) usa o JDK 17, o SDK do Android e o Gradle 8.11.1 de `C:\SCANNER\_ferramentas\android` (instalação mínima, sem o
Android Studio; em outro lugar, defina `ANDROID_TOOLS`), cria o `local.properties`, roda os testes e gera `ScannerCampo-<versão>.apk` na pasta.

## Fora do repositório
APKs, `local.properties`, saídas do Gradle, CSVs e diagnósticos de levantamento (dados de campo).
