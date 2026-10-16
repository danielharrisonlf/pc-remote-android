# PC Remote Android

Versão **0.4.0-kotlin**. Kotlin e Views do Android, sem React Native, Hermes, JavaScript, Compose ou WebView. Conecta ao desktop Rust/Tauri pela LAN, com protocolo 1 e as extensões `input.v1` e `files.v1`.

## Baixar e instalar

| Aplicativo | Download |
|---|---|
| Android — APK universal, Android 7/API24+ | [Baixar APK](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.4.0-kotlin/pc-remote-android-kotlin-0.4.0-evaluation.apk) |
| Windows x64 — instalador | [Baixar instalador .exe](https://github.com/danielharrisonlf/pc-remote-desktop/releases/download/v0.4.0/pc-remote-desktop-0.4.0-x64-setup.exe) |

O APK é público. O instalador Windows fica num repositório privado: entre no GitHub com uma conta autorizada. Veja a [release Android](https://github.com/danielharrisonlf/pc-remote-android/releases/tag/v0.4.0-kotlin) e a [release Windows](https://github.com/danielharrisonlf/pc-remote-desktop/releases/tag/v0.4.0). APK assinado com chave debug de avaliação; instalador Windows sem Authenticode. Validação em celular físico permanece pendente.

Instale o `.exe` no PC e abra o APK pelo gerenciador de arquivos do celular, autorizando a fonte quando solicitado. O app aparece como **PC Remote**, pacote `com.pcremote.nativeandroid`. Somente um controlador pode ficar conectado por PC. O uso não requer root, ADB, acessibilidade ou teclado especial.

## Tamanho

**APK 0.4.0: 691725 bytes (0.692 MB), cerca de 96.6% menor que os 20.20 MB do antigo app React Native.** Sem R8: 2.535 MB.

Veja [SIZE.md](docs/SIZE.md) para bytes exatos, hashes e comparação das builds com e sem R8. O tamanho do download difere do espaço instalado e do consumo de RAM. O scanner QR opcional pode baixar módulos externos do Google Play Services, que não fazem parte do APK.

## Desenvolvimento e build

JDK 17 e Android SDK: `platform-tools`, `platforms;android-37.0`, `build-tools;37.0.0`. Não requer Node/npm ou NDK. Versões em [VERSIONS.md](docs/VERSIONS.md).

```powershell
git clone https://github.com/danielharrisonlf/pc-remote-android.git
cd pc-remote-android\android
.\gradlew.bat testDebugUnitTest assembleRelease lintRelease
# Comparação sem R8:
.\gradlew.bat assembleSizeBaseline
```

No Linux/macOS, use `bash gradlew`. Também pode abrir `android` no Android Studio. APK final: `android/app/build/outputs/apk/release/app-release.apk`. Sem as quatro variáveis de assinatura, release usa a chave debug gerada pelo SDK. Para distribuição forneça seu keystore fora do Git. Instruções em [ANDROID.md](docs/ANDROID.md).

## Interface

Quatro abas: **Touchpad**, **Mídia**, **Transferir** e **Conexão**. Com um PC pareado o app abre no Touchpad; sem PC, na Conexão. A barra superior mostra o PC e o estado da conexão; a latência fica em Conexão. O tema segue o sistema ou fica claro ou escuro, pelo botão de sol/lua ou em Conexão → Preferências.

## Atualizações

O app consulta as releases deste repositório no GitHub ao abrir e a cada 6 horas, ou em Conexão → **Verificar agora**. Só contam tags `vX.Y.Z-kotlin`. Com versão nova, aparece um aviso no topo e um ponto na aba Conexão. As notas abrem numa folha e **Baixar APK** abre o download no navegador. **Agora não** esconde o aviso daquela versão.

## Pareamento e uso

No PC, abra **Parear celular**. No Android, digite o código de 8 dígitos e escolha o PC encontrado na rede, ou leia o QR. Aprove o pedido no Windows. Com o QR, o celular já conhece a identidade SHA-256 do PC. Com o código, ele fixa o certificado da primeira conexão e mostra a faixa de cores da identidade, que deve ser igual à exibida no PC. WSS usa o certificado fixado, validação normal do hostname e credenciais individuais cifradas pelo Android Keystore.

Volume e mudo refletem o Windows; o slider limita a frequência e confirma o valor final. Nas abas Touchpad e Mídia, com o PC conectado, os botões de volume do celular mudam o volume do PC de 5 em 5% (no Touchpad aparece "Volume do PC: NN%"); nas outras abas, ou desconectado, controlam o celular. Selecione a sessão de mídia desejada. Reproduzir/pausar, faixa anterior/próxima e posição **−10s/+10s** têm capacidades distintas; ações indisponíveis ficam desabilitadas, com explicação. Veja [compatibilidade de mídia](docs/MEDIA-COMPATIBILITY.md); não se promete controle de todo player.

**Enviar o que copiei** lê a cópia atual do Android. **Enviar texto** envia o campo, inclusive texto de **Compartilhar → PC Remote**. **Buscar do PC** solicita a cópia do Windows; **Copiar no celular** aplica o texto recebido. Esses envios manuais funcionam com a sincronização desligada.

Sync começa desligada. Cada direção exige consentimento no celular e no desktop; desligar **Sincronizar em tempo real** pausa as duas. Aplicação automática no Android é outra opção, inicialmente desligada. Texto fica em RAM, sem histórico persistente ou logs de conteúdo. Limite **64 KiB UTF-8**, preservando Unicode e quebras; NUL/Unicode inválido são recusados. Conteúdo sinalizado sensível é bloqueado; nem todas as senhas podem ser detectadas.

Em **Transferir → Arquivos**, **Escolher imagem ou PDF** abre o seletor do Android; também dá para usar **Compartilhar → PC Remote** na galeria ou no leitor de PDF. Aceita PNG, JPG e PDF de até 50 MB, vários de uma vez (fila de até 20, um por vez), com progresso e **Cancelar**. O PC salva em `Downloads\PC Remote`, sem sobrescrever. No Windows, ligue **Receber imagens e PDFs do celular** em Transferência; PCs sem essa versão mostram um aviso para atualizar. Arquivos compartilhados sem conexão esperam na fila e saem quando o PC conecta.

Android 10+ restringe leitura do clipboard de outros apps em segundo plano. Abra o aplicativo ou use **Compartilhar**. Sem serviço permanente ou garantia de conexão durante suspensão. Eventos que chegam antes da suspensão podem apresentar aviso genérico, quando autorizado. Texto antigo não é reenviado ao parear, reconectar, retomar ou habilitar sync. Atualizar clipboard não abre links, executa comandos ou cola em apps.

## Touchpad e teclado

No Windows, habilite **Permitir mouse e teclado pelo celular**. No Android, toque em **Ativar touchpad**. Um dedo move o cursor, com aceleração, e um toque clica. Dois dedos rolam, e um toque com dois dedos abre o menu de contexto. A faixa à direita rola como uma barra de rolagem. Há teclas rápidas (Esc, Tab, Enter, espaço, apagar, setas, Del, Home, End e duplo clique) e botões de clique. O botão de teclado abre **Digitar no PC**: o texto só vai com **Enviar** ou **Enviar + Enter**, até **1.024 bytes UTF-8** por comando. O alvo é a janela em foco no Windows; não há transmissão da tela. O touchpad continua ligado ao trocar de aba. Sair do app ou perder o foco desarma o PC na hora, e ele volta sozinho quando o app retorna, na mesma conexão. Só **Desligar**, a queda da conexão ou o bloqueio no PC exigem ativação nova. Aplicativos elevados/telas protegidas podem bloquear SendInput. Veja [INPUT.md](docs/INPUT.md).

## Documentação e testes

[Arquitetura](docs/ARCHITECTURE.md), [protocolo](docs/PROTOCOL.md), [testes e pendências](docs/TESTING.md). Contrato e exemplos são idênticos aos do desktop. O app 0.4.0 funciona com desktop 0.2.x, 0.3.0 e 0.4.0; o envio de imagens e PDFs exige o desktop 0.4.0; desktop 0.1.x conserva volume, mídia e clipboard, sem entrada remota.

O [template CI](docs/ci/github-actions.yml) testa Kotlin, executa lint e gera APK sem Node. A credencial GitHub disponível não tem escopo `workflow`; para ativá-lo, publique em `.github/workflows/build.yml` com autenticação apropriada.

A versão 0.2.1 corrige a recópia de texto recebido, estabelece baseline no ganho de foco, evita escritas idênticas e detalha erros de clipboard. Há uma [fixture opcional de validação](verification/clipboard-probe/README.md), separada do APK distribuído.

A versão 0.2.3 inclui a correção de fechamento do leitor QR da 0.2.2 e revisa pareamento, falhas de gravação protegida, troca de rede e informações da sessão de mídia. Atualize instalando o APK sobre a versão Kotlin anterior; o pareamento salvo é preservado quando a assinatura corresponde. O leitor requer Google Play Services e pode precisar baixar seu módulo na primeira utilização; o pareamento por código funciona sem esse módulo.

A versão 0.2.4 confirma o volume alterado por teclado ou acessibilidade, desativa entrada ao sair da tela de controle e descarta previews de clipboard quando a conexão termina.

A versão 0.3.0 traz a interface nova, com abas, temas, touchpad com gestos e aviso de atualização.

A versão 0.4.0 envia imagens e PDFs ao PC, mantém o touchpad ligado ao trocar de aba e usa os botões de volume do celular no volume do PC nas abas Touchpad e Mídia.

A revisão de 15/10/2026 está documentada na [auditoria de segurança](docs/SECURITY-AUDIT.md) e no [relatório de bugs e regressões](docs/BUG-AUDIT-2026-10-15.md), com evidências e limites da validação.
