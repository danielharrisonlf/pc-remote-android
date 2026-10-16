# PC Remote Android — Kotlin nativo

Versão **0.2.4-kotlin**, branch **native/kotlin**. A branch `main` conserva React Native. Esta alternativa usa Kotlin e Views do Android, sem React Native, Hermes, JavaScript, Compose ou WebView. Conecta ao mesmo desktop Rust/Tauri pela LAN, com protocolo 1 e extensão `input.v1`.

## Baixar e instalar

| Aplicativo | Download |
|---|---|
| Android Kotlin — APK universal, Android 7/API24+ | [Baixar APK Kotlin](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.4-kotlin/pc-remote-android-kotlin-0.2.4-evaluation.apk) |
| Windows x64 — instalador | [Baixar instalador .exe](https://github.com/danielharrisonlf/pc-remote-desktop/releases/download/v0.2.2/pc-remote-desktop-0.2.2-x64-setup.exe) |
| Android React Native — alternativa ARM64 | [Baixar APK React Native](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.1/pc-remote-android-0.2.1-arm64-evaluation.apk) |
| Android React Native — alternativa x86_64 para emulador | [Baixar APK React Native x86_64](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.1/pc-remote-android-0.2.1-x86_64-evaluation.apk) |

Downloads em repositórios privados: entre no GitHub com uma conta autorizada. Veja a [release Kotlin](https://github.com/danielharrisonlf/pc-remote-android/releases/tag/v0.2.4-kotlin) e a [release Windows](https://github.com/danielharrisonlf/pc-remote-desktop/releases/tag/v0.2.2). APK assinado com chave debug de avaliação; instalador Windows sem Authenticode. Validação em celular físico permanece pendente.

Instale o `.exe` no PC e abra o APK pelo gerenciador de arquivos do celular, autorizando a fonte quando solicitado. A versão Kotlin aparece como **PC Remote Kotlin**, pacote `com.pcremote.nativeandroid`, e pode coexistir com React Native. Cada app precisa de pareamento próprio; somente um controlador pode ficar conectado por PC. O uso não requer Metro, Expo Go, root, ADB, acessibilidade ou teclado especial.

## Tamanho

**APK final: 599989 bytes (0.600 MB), aproximadamente 97.0% menor que os 20.20 MB do React Native entregue.** Sem R8: 2.410 MB.

Veja [SIZE.md](docs/SIZE.md) para bytes exatos, hashes e comparação das builds Kotlin com e sem R8 e do APK React Native entregue. O tamanho do download difere do espaço instalado e do consumo de RAM. O scanner QR opcional pode baixar módulos externos do Google Play Services, que não fazem parte do APK.

## Desenvolvimento e build

JDK 17 e Android SDK: `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`. Não requer Node/npm ou NDK. Versões em [VERSIONS.md](docs/VERSIONS.md).

```powershell
git clone --branch native/kotlin https://github.com/danielharrisonlf/pc-remote-android.git
cd pc-remote-android\android
.\gradlew.bat testDebugUnitTest assembleRelease lintRelease
# Comparação sem R8:
.\gradlew.bat assembleSizeBaseline
```

No Linux/macOS, use `bash gradlew`. Também pode abrir `android` no Android Studio. APK final: `android/app/build/outputs/apk/release/app-release.apk`. Sem as quatro variáveis de assinatura, release usa a chave debug gerada pelo SDK. Para distribuição forneça seu keystore fora do Git. Instruções em [ANDROID.md](docs/ANDROID.md).

## Pareamento e uso

No PC, abra **Parear celular**. No Android, digite o código de 8 dígitos e escolha o PC encontrado na rede, ou leia o QR. Aprove o pedido no Windows. Com o QR, o celular já conhece a identidade SHA-256 do PC. Com o código, ele fixa o certificado da primeira conexão e mostra a faixa de cores da identidade, que deve ser igual à exibida no PC. WSS usa o certificado fixado, validação normal do hostname e credenciais individuais cifradas pelo Android Keystore.

Volume e mudo refletem o Windows; o slider limita a frequência e confirma o valor final. Selecione a sessão de mídia desejada. Reproduzir/pausar, faixa anterior/próxima e posição **−10s/+10s** têm capacidades distintas; ações indisponíveis ficam desabilitadas, com explicação. Veja [compatibilidade de mídia](docs/MEDIA-COMPATIBILITY.md); não se promete controle de todo player.

**Enviar texto copiado** lê a cópia atual do Android. **Enviar ao PC** envia o campo de texto, inclusive texto de **Compartilhar → PC Remote Kotlin**. **Ler do PC** solicita a cópia Windows; **Copiar no celular** aplica o texto recebido. Esses envios manuais funcionam com sincronização desligada.

Sync começa desligada. Cada direção exige consentimento no celular e no desktop; pausa rápida interrompe ambas. Aplicação automática no Android é outra opção, inicialmente desligada. Texto fica em RAM, sem histórico persistente ou logs de conteúdo. Limite **64 KiB UTF-8**, preservando Unicode e quebras; NUL/Unicode inválido são recusados. Conteúdo sinalizado sensível é bloqueado; nem todas as senhas podem ser detectadas.

Android 10+ restringe leitura do clipboard de outros apps em segundo plano. Abra o aplicativo ou use **Compartilhar**. Sem serviço permanente ou garantia de conexão durante suspensão. Eventos que chegam antes da suspensão podem apresentar aviso genérico, quando autorizado. Texto antigo não é reenviado ao parear, reconectar, retomar ou habilitar sync. Atualizar clipboard não abre links, executa comandos ou cola em apps.

## Touchpad e teclado

No Windows, habilite **Permitir mouse e teclado pelo celular**. No Android, toque em **Ativar entrada**. Arraste/toque no touchpad para mover/clicar; use os botões de clique direito, duplo, rolagem e teclas. Texto exige envio explícito, até **1.024 bytes UTF-8** por comando. O alvo é a janela em foco no Windows; não há transmissão da tela. Sair do app, desconectar ou suspender desativa entrada e exige ativação nova. Aplicativos elevados/telas protegidas podem bloquear SendInput. Veja [INPUT.md](docs/INPUT.md).

## Documentação e testes

[Arquitetura](docs/ARCHITECTURE.md), [protocolo](docs/PROTOCOL.md), [testes e pendências](docs/TESTING.md). Contrato e exemplos são idênticos aos do desktop. Kotlin 0.2.4 funciona com desktop 0.2.x; desktop 0.1.x conserva volume, mídia e clipboard, sem entrada remota.

O [template CI](docs/ci/github-actions.yml) testa Kotlin, executa lint e gera APK sem Node. A credencial GitHub disponível não tem escopo `workflow`; para ativá-lo, publique em `.github/workflows/build.yml` com autenticação apropriada.

A versão 0.2.1 corrige a recópia de texto recebido, estabelece baseline no ganho de foco, evita escritas idênticas e detalha erros de clipboard. Há uma [fixture opcional de validação](verification/clipboard-probe/README.md), separada do APK distribuído.

A versão 0.2.3 inclui a correção de fechamento do leitor QR da 0.2.2 e revisa pareamento, falhas de gravação protegida, troca de rede e informações da sessão de mídia. Atualize instalando o APK sobre a versão Kotlin anterior; o pareamento salvo é preservado quando a assinatura corresponde. O leitor requer Google Play Services e pode precisar baixar seu módulo na primeira utilização; o pareamento por código funciona sem esse módulo.

A versão 0.2.4 confirma o volume alterado por teclado ou acessibilidade, desativa entrada ao sair da tela de controle e descarta previews de clipboard quando a conexão termina.

A revisão de 15/10/2026 está documentada na [auditoria de segurança](docs/SECURITY-AUDIT.md) e no [relatório de bugs e regressões](docs/BUG-AUDIT-2026-10-15.md), com evidências e limites da validação.
