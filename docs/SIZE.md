# Tamanho real do APK — Kotlin 0.3.0

Medido nos arquivos publicados em 16/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.3.0 release, R8 e recursos reduzidos | 671817 | 0.672 | 0.641 |
| Kotlin 0.3.0 sizeBaseline, sem R8 | 2503247 | 2.503 | 2.387 |
| Kotlin 0.2.4 release, antes do redesign | 599989 | 0.600 | 0.572 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

A interface nova acrescentou 71828 bytes ao release, entre ícones vetoriais e telas. O APK 0.3.0 é **96.7% menor** que o antigo React Native; sem R8, 87.6% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4, verificada com apksigner; por isso a 0.3.0 instala por cima da versão anterior.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
a3c33cdf6fc57425131718b58d228027f95b1b774113d054d14fba71ba7838fd  pc-remote-android-kotlin-0.3.0-evaluation.apk
27e1211773f8d88a254cd6a525f8f958ea30f039982d674904f17a732696130a  pc-remote-android-kotlin-0.3.0-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
