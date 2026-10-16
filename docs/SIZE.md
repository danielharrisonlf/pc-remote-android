# Tamanho real do APK — Kotlin 0.5.0

Medido nos arquivos gerados para publicação em 16/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.5.0 release, R8 e recursos reduzidos | 707333 | 0.707 | 0.675 |
| Kotlin 0.5.0 sizeBaseline, sem R8 | 2556523 | 2.557 | 2.438 |
| Kotlin 0.4.0 release, antes da atualização dentro do app | 691725 | 0.692 | 0.660 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

A atualização dentro do app (download, verificação, PackageInstaller e aviso) acrescentou 15608 bytes ao release. O APK 0.5.0 é **96.5% menor** que o antigo React Native; sem R8, 87.3% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais; o envio de arquivos e a atualização usam o OkHttp que já estava no app. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4 à 0.4.0, verificada com apksigner (certificado SHA-256 `70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`); por isso a 0.5.0 instala por cima da versão anterior, e a atualização dentro do app depende de manter essa mesma chave.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
4018c93119d22290617848931aebf11d09d19c780c7cf4b5897d110e135f7638  pc-remote-android-kotlin-0.5.0-evaluation.apk
99b9dd0b886b0995de5b706aa0dd375c2dc9462f79dc8252354b743080363d3c  pc-remote-android-kotlin-0.5.0-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
