# Tamanho real do APK — Kotlin 0.2.4

Medido nos arquivos gerados em 15/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin release, R8 e recursos reduzidos | 599989 | 0.600 | 0.572 |
| Kotlin sizeBaseline, sem R8 | 2409747 | 2.410 | 2.298 |
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
73d07e817f3acc937978856a40d5fb50927356d748ef0b4285bd6b86c4b20703  pc-remote-android-kotlin-0.2.4-evaluation.apk
79f53fd4ce7da6b4211938df526907ec6559b2beaadbf0177bc140e61bdf16c7  pc-remote-android-kotlin-0.2.4-unminified-evaluation.apk
2aea00a472c0abdec79daad5b812f32cce11f9f8966ccd573b1e481a3377f96a  pc-remote-android-0.2.0-arm64-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
