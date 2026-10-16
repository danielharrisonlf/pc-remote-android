# Tamanho real do APK — Kotlin 0.4.0

Medido nos arquivos publicados em 16/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.4.0 release, R8 e recursos reduzidos | 691725 | 0.692 | 0.660 |
| Kotlin 0.4.0 sizeBaseline, sem R8 | 2534707 | 2.535 | 2.417 |
| Kotlin 0.3.0 release, antes de files.v1 | 671817 | 0.672 | 0.641 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

O envio de imagens e PDFs, o touchpad persistente e as teclas de volume acrescentaram 19908 bytes ao release. O APK 0.4.0 é **96.6% menor** que o antigo React Native; sem R8, 87.4% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais; o envio de arquivos usa o OkHttp que já estava no app. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4 e da 0.3.0, verificada com apksigner; por isso a 0.4.0 instala por cima da versão anterior.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
918b7958c5990f4ca48abedf962720bf6ac5572e4e8188ed6c676b6b08ba342b  pc-remote-android-kotlin-0.4.0-evaluation.apk
3729f3cc9e653e926b5bb77de6a50c020e4160dbe5d1fbaea80d5a4181a21b40  pc-remote-android-kotlin-0.4.0-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
