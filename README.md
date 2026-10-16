# PC Remote Android

Aplicativo Android nativo em React Native + TypeScript para controlar o PC na LAN. Este repositório é independente do `pc-remote-desktop`. Versão 0.2.1, protocolo 1. Não usa WebView, Expo Go, nuvem, login, root, ADB ou acessibilidade para operar.

## Baixar e instalar — versão 0.2.1

| Aplicativo | Download |
|---|---|
| Android ARM64 — celular compatível | [Baixar APK](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.1/pc-remote-android-0.2.1-arm64-evaluation.apk) |
| Windows x64 — instalador | [Baixar instalador .exe](https://github.com/danielharrisonlf/pc-remote-desktop/releases/download/v0.2.2/pc-remote-desktop-0.2.2-x64-setup.exe) |
| Android x86_64 — emulador | [Baixar APK para emulador](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.1/pc-remote-android-0.2.1-x86_64-evaluation.apk) |
| Android Kotlin — alternativa universal, branch native/kotlin | [Baixar APK Kotlin](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.2.4-kotlin/pc-remote-android-kotlin-0.2.4-evaluation.apk) |

Os downloads estão nas Releases privadas: entre no GitHub com uma conta que tenha acesso aos dois repositórios. As páginas de [Release Android](https://github.com/danielharrisonlf/pc-remote-android/releases/tag/v0.2.1) e [Release Windows](https://github.com/danielharrisonlf/pc-remote-desktop/releases/tag/v0.2.2) também incluem os hashes SHA-256.

Instale o `.exe` no Windows e abra o PC Remote pela bandeja. No celular, abra o APK ARM64 pelo gerenciador de arquivos e autorize a instalação dessa fonte quando solicitado. Conecte ambos à mesma rede local, inicie o pareamento no PC, escaneie o QR ou informe os dados exibidos e confirme o pedido no Windows. Os aplicativos instalados não precisam de Metro, Expo Go ou ferramentas de desenvolvimento.

Esta é uma versão de avaliação: APK assinado com chave debug e instalador sem Authenticode. A validação em celular físico e players reais permanece pendente; consulte [testes e limitações](docs/TESTING.md).

## Escolha da arquitetura

React Native **0.87.1**, React **19.2.3**, Community CLI **20.2.0**, New Architecture e Hermes, verificados no registro npm em 14/10/2026. O Expo estável **57.0.28** acompanha RN 0.86, enquanto SDK 58 ainda está no canal `next`. Expo development build com módulo local seria viável; escolhemos Community CLI para manter a versão estável atual do RN sem misturar versões incompatíveis. Kotlin fornece o transporte com identidade TLS fixada, armazenamento Keystore e integrações que precisam acessar flags e ciclo de vida nativos. Interface e regras de interação continuam em React Native/TypeScript estrito.

Fontes: [RN versões](https://reactnative.dev/versions), [criação sem framework](https://reactnative.dev/docs/getting-started-without-a-framework), [matriz Expo](https://docs.expo.dev/versions/latest/). Dependências exatas estão no `package-lock.json`; detalhes nativos em [ANDROID.md](docs/ANDROID.md).

## Pré-requisitos e desenvolvimento

Node LTS >=22.11, JDK 17, Android Studio/SDK conforme `android/build.gradle`, Android 7/API24+; Android 10+ recomendado para restrições modernas do clipboard. Configure `ANDROID_HOME` para o SDK e instale platform-tools, plataforma, Build Tools e NDK das versões registradas no Gradle. Use aparelho ou emulador para desenvolvimento. ADB é ferramenta de desenvolvimento, não requisito do usuário final.

```powershell
git clone https://github.com/danielharrisonlf/pc-remote-android.git
cd pc-remote-android
npm ci
npm run typecheck
npm test -- --runInBand
npm start
# Em outro terminal, aparelho/emulador pronto:
npm run android
```

## APK com JavaScript embutido

```powershell
cd android
.\gradlew.bat assembleRelease
```

Saída em `android/app/build/outputs/apk/release/`. Essa variante inclui o bundle JS e não usa Metro. A configuração de assinatura e as variáveis exatas estão em [ANDROID.md](docs/ANDROID.md). Uma build de avaliação com chave debug deve ser explicitamente identificada; para distribuição use seu próprio keystore fora do repositório. Gere uma chave com `keytool`, forneça caminho/alias/senhas por variáveis de ambiente injetadas pelo seu ambiente de build e guarde backup seguro. Não publique chaves ou senhas. Sem a mesma chave não será possível atualizar uma instalação existente assinada.

Instale o APK no celular pelo gerenciador de arquivos, autorizando a instalação dessa fonte quando solicitado. O aplicativo Windows deve estar em execução na mesma LAN. Não há dependência de ferramentas de desenvolvimento depois de instalado.

## Uso

Abra pareamento no PC, use descoberta ou endereço manual, escaneie o QR ou informe o código e **fingerprint exibidos no PC**, e aprove no Windows. Nome do PC e conexão aparecem no controle. Volume/mudo refletem o Windows; sessão de mídia pode ser selecionada. Faixa anterior/próxima são diferentes dos botões de posição **−10s/+10s**, disponíveis somente quando o player expõe essa capacidade.

**Enviar para o PC** envia o texto copiado no Android; **Copiar do PC** solicita o clipboard atual do Windows e oferece copiar o conteúdo recebido. Também é possível usar **Compartilhar → PC Remote** em outro aplicativo. Conteúdo compartilhado com o PC desconectado permanece somente na tela, em RAM, para envio explícito; não é reenviado ao reconectar.

A sincronização começa desligada. Ative separadamente celular→PC e PC→celular em configurações e autorize as direções no desktop. Pausar interrompe ambas. Aplicação automática no Android é outra opção, desligada por padrão e restrita a quando o app tem foco. Preferências persistem; texto recebido não forma histórico persistente.

## Limites reais do Android

Android 10+ não permite ler clipboard de outros apps em segundo plano. Abra o PC Remote ou use **Compartilhar**. Um serviço em primeiro plano não elimina essa restrição. O MVP não mantém serviço permanente; a conexão pode cair ao suspender, e retoma com novo estado ao abrir. Eventos recebidos enquanto o processo ainda está ativo podem apresentar notificação genérica, se autorizada; não há entrega garantida durante suspensão. Conteúdo antigo não é transferido ao retomar, habilitar sync ou parear.

Texto até **64 KiB UTF-8**, preservando acentos, emojis e quebras; NUL é rejeitado. Texto marcado `EXTRA_IS_SENSITIVE` é bloqueado; a plataforma não marca todas as senhas. Não existe sincronização de imagens/arquivos, abertura automática de links, colagem em aplicativos ou execução remota de comandos.

Veja [protocolo](docs/PROTOCOL.md), [arquitetura](docs/ARCHITECTURE.md), [compatibilidade de mídia](docs/MEDIA-COMPATIBILITY.md) e [testes/pendências](docs/TESTING.md).

## Touchpad e teclado remoto

1. No Windows, abra **Preferências → Mouse e teclado** e habilite **Permitir mouse e teclado pelo celular**. A opção começa desligada.
2. No Android, abra **Touchpad e teclado** e toque em **Ativar controle** nesta conexão.
3. Arraste no touchpad para mover o cursor; toque para clicar. Botões separados oferecem clique direito, clique duplo e rolagem. Digite no campo de texto e envie explicitamente, ou use as teclas comuns.
4. O alvo é o aplicativo em foco no Windows. Selecione a janela desejada antes de enviar texto. O controle não exibe a tela do PC.
5. Para interromper, desative no celular, na Visão geral do Windows ou no menu da bandeja. Bloquear a permissão no PC também interrompe o controle.

A ativação é descartada ao desconectar, suspender ou colocar o Android em segundo plano. É necessário ativar novamente; movimentos, cliques e textos pendentes não são repetidos. Texto digitado fica somente em RAM e não entra em logs/histórico. O limite por envio é **1.024 bytes UTF-8**, sem caracteres de controle; use os botões Enter e Tab para essas teclas. Aplicativos elevados, tela de bloqueio/UAC e algumas interfaces podem recusar entrada. Teclas físicas pressionadas no PC podem interferir. Veja [INPUT.md](docs/INPUT.md) para detalhes e validações.

## Integração contínua

O modelo reproduzível está em [docs/ci/github-actions.yml](docs/ci/github-actions.yml). Ele executa testes e gera artefatos de avaliação. A autenticação GitHub disponível nesta entrega não possui o escopo `workflow`, portanto o modelo não está ativo. Para ativá-lo, com uma autenticação autorizada para workflows, mova o arquivo para `.github/workflows/build.yml` e publique esse commit. Os builds e testes locais estão registrados em [docs/TESTING.md](docs/TESTING.md).

A revisão de 15/10/2026 está documentada na [auditoria de segurança](docs/SECURITY-AUDIT.md) e no [relatório de bugs e regressões](docs/BUG-AUDIT-2026-10-15.md), com evidências e limites da validação.
