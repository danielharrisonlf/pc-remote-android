# Versões da build Kotlin — 24/10/2026

| Componente | Versão usada |
|---|---|
| Android / desktop | 0.6.9-kotlin (versionCode 609) / 0.6.7 |
| Protocolo | 1 + extensões opcionais input.v1, files.v1, files.v2, search.v1, info.v1, power.v1, clipboard.v2, wol.v1, numpad.v1, presentation.v1 e deck.v1 |
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
| 0.6.8-kotlin | 0.6.6 | Protocolo 1; distingue erros de código, preserva o prazo do pedido aceito e recupera descoberta temporariamente indisponível |
| 0.6.7-kotlin | 0.6.5 | Mesmo protocolo e extensões da 0.6.6; corrige seleção do IP ao trocar de PC, descoberta de vários PCs e estado de pareamento |
| 0.6.6-kotlin | 0.6.5 | Protocolo 1 + todas as extensões; nova identidade visual clean moderna (Precision Dual Prism em porcelana clara), ícone adaptativo e drawables atualizados |
| 0.6.2–0.6.5-kotlin | 0.6.3–0.6.4 | Protocolo 1 + input.v1, files.v1, files.v2, search.v1, info.v1, power.v1, clipboard.v2, wol.v1, numpad.v1, presentation.v1 e deck.v1: tudo da 0.6.1, mais Wake-on-LAN (descoberta de MAC e envio de magic packet UDP), aba dedicada Controles (Deck de 12 atalhos, teclado numérico 123 e apresentador de slides com temporizador e botões de volume físico), Touchpad restaurado em tela cheia e pipeline de mouse com cadência de envio de 16 ms; a 0.6.5 adapta a janela ao RTT |
| 0.6.1-kotlin | 0.6.1 | Protocolo 1 + input.v1, files.v1, files.v2, search.v1, info.v1, power.v1 e clipboard.v2: tudo da 0.6.0, mais bloquear, suspender, desligar e reiniciar o PC (agora ou agendado) e imagens na área de transferência nos dois sentidos |
| 0.6.1-kotlin | 0.6.0 | O PC ignora power.v1 e clipboard.v2: sem energia do PC e clipboard só com texto; vários PCs e controles de mídia fora do app funcionam |
| 0.6.0-kotlin | 0.6.1 | Formato 0.6.0, sem campos novos |
| 0.6.0-kotlin | 0.6.0 | Protocolo 1 + input.v1, files.v1, files.v2, search.v1 e info.v1: arquivos de qualquer tipo até 1 GB nos dois sentidos, touchpad e clipboard prontos após o pareamento, pesquisa no YouTube e nome do Windows |
| 0.6.0-kotlin | 0.4.0 e 0.5.0 | O PC ignora files.v2, search.v1 e info.v1: PNG/JPG/PDF até 50 MB só do celular para o PC, sem pesquisa; touchpad pede **Ativar touchpad** e o PC ainda pede as chaves de mouse/teclado, arquivos e direções do clipboard |
| 0.4.0 e 0.5.0-kotlin | 0.6.0 | Formato anterior; arquivos e mouse/teclado já liberados no PC; o clipboard sincroniza nas direções ligadas no celular |
| 0.4.0 e 0.5.0-kotlin | 0.4.0 | Protocolo 1; input.v1 após consentimento nos dois aplicativos; files.v1 com **Receber imagens e PDFs do celular** ligado no PC |
| 0.4.0 e 0.5.0-kotlin | 0.2.x e 0.3.0 | input.v1, volume (também pelas teclas físicas), mídia e clipboard; Arquivos pede para atualizar o PC e nenhum pedido de arquivo é enviado |
| 0.2.0–0.3.0-kotlin | 0.4.0 | Contrato anterior preservado; o PC não anuncia `file.transfer` nem envia o estado `files` |
| 0.2.x–0.5.0-kotlin | 0.1.x | Volume, mídia e clipboard; sem mouse/teclado |
| Qualquer implementação | Sem protocolo 1 | Incompatível; rejeitado |

A 0.6.0 acrescenta só extensões negociadas (files.v2, search.v1, info.v1); permissões novas: RECORD_AUDIO (pedida no primeiro uso do microfone), FOREGROUND_SERVICE e FOREGROUND_SERVICE_DATA_SYNC; saiu VIBRATE. A 0.5.0 não muda o protocolo: a atualização dentro do app conversa só com o GitHub. Permissões novas: REQUEST_INSTALL_PACKAGES e UPDATE_PACKAGES_WITHOUT_USER_ACTION. A matriz descreve contrato; capacidades do dispositivo/player definem os controles reais. Não equivale a certificação de todos os players/hardwares.
