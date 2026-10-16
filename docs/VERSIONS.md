# Versões da build Kotlin — 16/10/2026

| Componente | Versão usada |
|---|---|
| Android / desktop | 0.3.0-kotlin / 0.3.0 |
| Protocolo | 1 + extensão opcional input.v1 |
| Android Gradle Plugin | 9.2.1 |
| Kotlin integrado ao AGP | 2.2.10, confirmado por buildEnvironment |
| Gradle wrapper | 9.4.1, URL versionada |
| JDK | Temurin 17.0.20.1 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 |
| SDK platform / Build Tools | android-37.0 / 37.0.0 |
| OkHttp / Google Play Services Code Scanner | 4.12.0 / 16.1.0 |
| JUnit / JSON de testes | 4.13.2 / 20250517 |
| Comparação histórica de tamanho | React Native 0.87.1, app 0.2.0 ARM64, removido |

Versões diretas são exatas no Gradle. O projeto não utiliza package-lock, Hermes, Expo, React, Compose ou NDK. Kotlin integrado ao AGP não recebe plugin separado. Target 36 conserva INTERNET/NSD; elevar para 37 requer permissão contextual de rede local conforme a [documentação Android](https://developer.android.com/privacy-and-security/local-network-permission).

## Compatibilidade entre versões

| Android | Desktop | Compatibilidade |
|---|---|---|
| 0.3.0-kotlin | 0.3.0 | Protocolo 1; input.v1 após consentimento nos dois aplicativos; o PC mostra a mesma faixa de cores do pareamento |
| 0.3.0-kotlin | 0.2.x | Mesmo contrato; o PC mostra a identidade só em hexadecimal |
| 0.2.0–0.2.4-kotlin | 0.2.x e 0.3.0 | Compatibilidade anterior preservada; input.v1 após consentimento |
| 0.2.x e 0.3.0 | 0.1.x | Volume, mídia e clipboard; sem mouse/teclado |
| Qualquer implementação | Sem protocolo 1 | Incompatível; rejeitado |

A matriz descreve contrato; capacidades do dispositivo/player definem os controles reais. Não equivale a certificação de todos os players/hardwares.
