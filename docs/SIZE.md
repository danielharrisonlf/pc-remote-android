# Tamanho real do APK — Kotlin 0.6.1

Medido nos arquivos gerados para publicação em 16/10/2026. MB = 1.000.000 bytes; MiB = 1.048.576 bytes.

| Build | Bytes | MB | MiB |
|---|---:|---:|---:|
| Kotlin 0.6.1 release, R8 e recursos reduzidos | 759953 | 0.760 | 0.725 |
| Kotlin 0.6.1 sizeBaseline, sem R8 | 2634807 | 2.635 | 2.513 |
| Kotlin 0.6.0 release, antes desta versão | 725441 | 0.725 | 0.692 |
| React Native 0.2.0 ARM64, app antigo removido | 20195433 | 20.195 | 19.260 |

Vários PCs, controles de mídia fora do app (serviço, sessão de mídia, widget e bloco das Configurações rápidas), energia do PC e imagens na área de transferência acrescentaram 34512 bytes ao release, sem dependência nova: o provider das imagens usa o `FileProvider` do androidx.core, que já vinha com o scanner QR. O APK 0.6.1 é **96.2% menor** que o antigo React Native; sem R8, 87.0% menor.

A comparação inclui decisões de build: o React Native usava release ARM64 com a configuração original; o Kotlin usa release universal com R8 e redução de recursos. sizeBaseline conserva o código Kotlin e a assinatura, mas desliga as duas otimizações. Não se atribui toda a redução apenas à linguagem Kotlin.

Views e serviços do sistema evitam empacotar runtime React Native/Hermes ou Compose. A inspeção ZIP confirmou ausência de bibliotecas .so e bundle JS no APK. OkHttp e o scanner QR são dependências reais; arquivos nos dois sentidos e a atualização usam o OkHttp que já estava no app, e a voz usa o reconhecedor do sistema. Módulos externos do Google Play Services podem ser necessários para o QR e não entram neste tamanho.

São tamanhos do download, não espaço instalado, dados/cache ou RAM. As duas variantes estão assinadas com a chave debug de avaliação, a mesma da 0.2.4 à 0.6.0, verificada com apksigner (certificado SHA-256 `70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`); por isso a 0.6.1 instala por cima da versão anterior e chega pela atualização dentro do app, que depende de manter essa mesma chave.

## Reprodução

Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease
Get-Item app/build/outputs/apk/release/app-release.apk, app/build/outputs/apk/sizeBaseline/app-sizeBaseline.apk | Select-Object Name,Length
```

A assinatura, o ambiente e a versão das ferramentas podem alterar alguns bytes; os arquivos entregues são identificados por hash.

## SHA-256

```text
121cc07a2519f777ec02cea88f8bd87f83be0faa19ae2322671c38fb32c9dae1  pc-remote-android-kotlin-0.6.1-evaluation.apk
6a083f26b417764e8525c18b904efcca66526c9ee02f0ee46535605ad728bb7d  pc-remote-android-kotlin-0.6.1-unminified-evaluation.apk
```

Veja [TESTING.md](TESTING.md) para cobertura e pendências; compilação e tamanho não certificam todos os aparelhos ou players.
