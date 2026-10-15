# Tamanho real do APK — Kotlin 0.2.3

Medido nos arquivos gerados em 15/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin release, R8 e recursos reduzidos | 597197 | 0.597 | 0.570 |
| Kotlin sizeBaseline, sem R8 | 2406519 | 2.407 | 2.295 |
| React Native main 0.2.0 ARM64 entregue | 20195433 | 20.195 | 19.260 |

Kotlin otimizado: **97.0% menor** que o React Native entregue. Sem R8: **88.1% menor**.

A comparação inclui decisões de build: React Native entregue usa release ARM64 com sua configuração original; Kotlin usa release universal com R8/redução de recursos. sizeBaseline conserva o código Kotlin e assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK Kotlin. OkHttp e scanner QR são dependências reais, sem implementações simuladas. Módulos externos do Google Play Services podem ser necessários para QR e não entram neste tamanho.

São tamanhos do download APK, não espaço instalado, dados/cache ou RAM. Instalação paralela usa com.pcremote.nativeandroid. Ambas as variantes Kotlin estão assinadas com a chave debug de avaliação, verificada com apksigner.

## Reprodução

Na branch native/kotlin, pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, ambiente e versão das ferramentas podem alterar alguns bytes; este relatório identifica os arquivos entregues por hash.

## SHA-256

```text
4f14cff589b3f0c1cf1897f2436351bd579cd5195700c409d70abcdb80c7b389  pc-remote-android-kotlin-0.2.3-evaluation.apk
b8dbb6f285263b8cf0aa4564c5ae48328dd8f6d3b4513681b4172be640a9bd0f  pc-remote-android-kotlin-0.2.3-unminified-evaluation.apk
2aea00a472c0abdec79daad5b812f32cce11f9f8966ccd573b1e481a3377f96a  pc-remote-android-0.2.0-arm64-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
