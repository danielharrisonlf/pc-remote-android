# Tamanho real do APK — Kotlin 0.6.8

APK 0.6.8 medido em 24/10/2026; comparação com as builds anteriores de 17–24/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.6.8 release, pareamento e descoberta | 767209 | 0.767 | 0.732 |
| Kotlin 0.6.7 release, correção de conexão | 762593 | 0.763 | 0.727 |
| Kotlin 0.6.6 release, identidade visual | 759385 | 0.759 | 0.724 |
| Kotlin 0.6.5 release, R8 e recursos reduzidos | 726185 | 0.726 | 0.693 |
| Kotlin 0.6.4 release, R8 e recursos reduzidos | 724989 | 0.725 | 0.691 |
| Kotlin 0.6.3 release, R8 e recursos reduzidos | 723457 | 0.723 | 0.690 |
| Kotlin 0.6.3 sizeBaseline, sem R8 | 2604523 | 2.605 | 2.484 |
| Kotlin 0.6.2 release, antes desta versão | 771929 | 0.772 | 0.736 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

Nova identidade visual vetorial adaptativa (claro, escuro e monocromático), ícone branco de notificação e reorganização de Deck, teclado numérico (123) e apresentador de slides na nova aba dedicada **Controles** (`ControlsScreen`), restaurando o layout integral do Touchpad e reduzindo o APK otimizado pelo R8 para 723457 bytes. O APK 0.6.3 é **96.4% menor** que o antigo React Native; sem R8, 87.1% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais; arquivos nos dois sentidos e a atualização usam o OkHttp que já estava no app, e a voz usa o reconhecedor do sistema. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4 à 0.6.2, verificada com apksigner (certificado SHA-256 `70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`); por isso a 0.6.3 instala por cima da versão anterior e chega pela atualização dentro do app, que depende de manter essa mesma chave.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
e67424b6778ad22932c019a646169ba0c8b64d32b4462041770ae6a2c5e2fd8d  pc-remote-android-kotlin-0.6.8-evaluation.apk
d9296110b67b550235ca9e006acdef41fb297a9b1bcef6c79e9029260a6895a4  pc-remote-android-kotlin-0.6.7-evaluation.apk
55480613150f71cdb90a82fba7acef278a8ab0f40492dfedd09432e0993d9197  pc-remote-android-kotlin-0.6.5-evaluation.apk
baabf15d25d84cd821bcf4ecccdb7ac99011e598f22674dfc2e945e2c0360126  pc-remote-android-kotlin-0.6.4-evaluation.apk
673890551e02d89f5b8448ab0cf25b0500a9416782d4c102299b6c0347fea3f9  pc-remote-android-kotlin-0.6.3-evaluation.apk
ed832b24508ce93dad93d5ab6bdc8adb9bb895c2959dcccc0ea66a7dc4b98be6  pc-remote-android-kotlin-0.6.3-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
