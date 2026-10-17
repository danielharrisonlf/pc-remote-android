# Tamanho real do APK — Kotlin 0.6.2

Medido nos arquivos gerados para publicação em 17/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.6.2 release, R8 e recursos reduzidos | 771929 | 0.772 | 0.736 |
| Kotlin 0.6.2 sizeBaseline, sem R8 | 2653227 | 2.653 | 2.530 |
| Kotlin 0.6.1 release, antes desta versão | 759953 | 0.760 | 0.725 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

Wake-on-LAN (descoberta de MAC e socket UDP de magic packet), teclado numérico dedicado (NumpadSheet), apresentador de slides (PresentationSheet) com controle por botões de volume físico e timer, Quick Deck com 12 atalhos, pipeline de mouse a 60 FPS com grid de pontos em Bitmap cacheado e purificação de UX acrescentaram apenas 11976 bytes ao release otimizado pelo R8, sem nenhuma dependência externa nova. O APK 0.6.2 continua **96.2% menor** que o antigo React Native; sem R8, 86.9% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais; arquivos nos dois sentidos e a atualização usam o OkHttp que já estava no app, e a voz usa o reconhecedor do sistema. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4 à 0.6.1, verificada com apksigner (certificado SHA-256 `70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`); por isso a 0.6.2 instala por cima da versão anterior e chega pela atualização dentro do app, que depende de manter essa mesma chave.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
7f4a2ff083a6d80b2612109e0d78c472399ef116c9a455e7777182337175605b  pc-remote-android-kotlin-0.6.2-evaluation.apk
77c6d6c4e726cb373ec31f5fcbc52279445af8e1ce75bd3ef187c1e9acf7f6df  pc-remote-android-kotlin-0.6.2-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
