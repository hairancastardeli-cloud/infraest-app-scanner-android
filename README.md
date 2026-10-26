# Scanner Campo

App Android próprio para o scanner **GeoSun GS-102G**, feito a partir da leitura do protocolo do FireEye 3.2.0. Não usa nem altera o app
da GeoSun (e o FireEye e o Scanner Campo não devem ficar conectados ao scanner ao mesmo tempo).

Kotlin, sem bibliotecas externas além do Android SDK. Pacote `br.scanner.campo`.

## O que faz
Conecta no Wi-Fi do scanner (rede `AGS…`, aberta; `192.168.2.11:8080`), mostra em qualquer aba a **faixa do SLAM** (normal / atenção /
perdido, com apito e vibração), o estado do scanner, bateria, memória, GNSS, RTK (cliente **NTRIP** próprio) e precisão; inicia/para o
levantamento e grava ponto de controle. Cada levantamento grava um CSV com todos os quadros e o `diagnostico_scanner.txt` em
`Android/data/br.scanner.campo/files/logs_slam/`. Detalhes de uso: `LEIAME.txt`.

## Código (`app/src/main/java/br/scanner/campo/`)
- `Protocolo.kt` — quadros `HOLD` + comando de 4 bytes + tamanho + dados (envio e leitura da nuvem/estado/GNSS/IMU…).
- `ConectorScanner.kt` — achar e conectar a rede Wi-Fi do scanner e abrir o socket.
- `MonitorSlam.kt` — alarme do SLAM (velocidade, % de pontos, tempo sem quadro); limites ainda **provisórios**.
- `Ntrip.kt`, `Gga.kt` — cliente NTRIP (mountpoints, correções RTCM) e leitura de GGA/GST.
- `MainActivity.kt`, `Estilo.kt`, `VistaTopo.kt` — interface, tema e vista de cima do caminho. `Diagnostico.kt`, `Equipamento.kt` — registros.
- Testes em `app/src/test` (protocolo, GGA, alarme do SLAM, NTRIP contra caster simulado).

## Compilar
`compilar.bat` (duplo clique) usa o JDK 17, o SDK do Android e o Gradle 8.11.1 de `C:\SCANNER\_ferramentas\android` (instalação mínima, sem o
Android Studio; em outro lugar, defina `ANDROID_TOOLS`), cria o `local.properties`, roda os testes e gera `ScannerCampo-<versão>.apk` na pasta.

## Fora do repositório
APKs, `local.properties`, saídas do Gradle, CSVs e diagnósticos de levantamento (dados de campo).
