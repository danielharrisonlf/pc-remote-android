# PC Remote Android

Versão **0.6.5-kotlin**. Kotlin e Views do Android, sem React Native, Hermes, JavaScript, Compose ou WebView. Conecta ao desktop Rust/Tauri pela LAN, com protocolo 1 e as extensões `input.v1`, `files.v1`, `files.v2`, `search.v1`, `info.v1`, `power.v1`, `clipboard.v2`, `wol.v1`, `numpad.v1`, `presentation.v1` e `deck.v1`.

## Baixar e instalar

| Aplicativo | Download |
|---|---|
| Android — APK universal, Android 7/API24+ | [Baixar APK](https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.6.5-kotlin/pc-remote-android-kotlin-0.6.5-evaluation.apk) |
| Windows x64 — instalador | [Baixar instalador .exe](https://github.com/danielharrisonlf/pc-remote-desktop/releases/download/v0.6.4/pc-remote-desktop-0.6.4-x64-setup.exe) |

As releases dos dois repositórios são públicas. Veja a [release Android](https://github.com/danielharrisonlf/pc-remote-android/releases/tag/v0.6.5-kotlin) e a [release Windows](https://github.com/danielharrisonlf/pc-remote-desktop/releases/tag/v0.6.4). APK assinado com chave debug de avaliação; instalador Windows sem Authenticode. Validação em celular físico permanece pendente.

Instale o `.exe` no PC e abra o APK pelo gerenciador de arquivos do celular, autorizando a fonte quando solicitado. O app aparece como **PC Remote**, pacote `com.pcremote.nativeandroid`. Somente um controlador pode ficar conectado por PC. O uso não requer root, ADB, acessibilidade ou teclado especial.

## Tamanho

**APK 0.6.5: 726185 bytes (0.726 MB), cerca de 96.4% menor que os 20.20 MB do antigo app React Native.** A medição sem R8 da 0.6.3 foi de 2.605 MB.

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

**0.6.3-kotlin:** nova aba **Controles** dedicada (Deck de ações, teclado numérico `123` e apresentador de slides), restauração do Touchpad em tela cheia e ícone vetorial adaptativo para tema claro, escuro e ícones temáticos, além de um ícone branco próprio para notificações. Pacote, assinatura, pareamentos, permissões e protocolo são preservados. [Notas da versão](docs/releases/0.6.3-kotlin.md).

## Interface

Cinco abas: **Touchpad**, **Controles**, **Mídia**, **Transferir** e **Conexão**. Com um PC pareado o app abre no Touchpad; sem PC, na Conexão. A barra superior mostra o PC e o estado da conexão. **Controles** reúne o Deck de Ações, o Teclado Numérico (`123`) e o Apresentador de Slides. Conexão mostra só o nome do PC, o estado, o Windows dele e as ações (Desconectar/Reconectar, Adicionar PC, Remover este PC), o cartão **Energia do PC** e, com mais de um PC, **Outros PCs**; as preferências são **Sincronização em tempo real** e o tema (sistema, claro ou escuro).

**Vários PCs (0.6.1):** **Adicionar PC** pareia outro PC sem apagar os anteriores, até 8. Os demais ficam em **Outros PCs**, com **Conectar** e a lixeira; com mais de um, tocar no nome do PC na barra superior abre **Trocar de PC**. O celular conecta a um PC por vez.

**Energia do PC (0.6.1, com desktop 0.6.1):** **Bloquear** age na hora; **Suspender** e **Desligar** oferecem Agora, 15 min, 30 min, 1 hora e 2 horas; **Reiniciar** pede confirmação. Um agendamento aparece com a hora e o tempo restante, com **Cancelar**, e também pode ser cancelado no PC. Com **Interromper** ativo no PC os botões ficam desabilitados. Desligar e reiniciar não forçam o fechamento de programas no Windows.

**Mídia fora do app (0.6.1):** com o PC tocando e o app aberto, os controles (anterior, tocar/pausar, próxima) aparecem na notificação, na tela de bloqueio e nos controles de mídia do Android, e continuam com o app em segundo plano. **Fechar** os tira até você abrir o app de novo. O widget **Mídia do PC** (tela inicial) e o bloco **Mídia do PC** (Configurações rápidas) controlam o PC atual e conectam na hora do toque, se preciso.

## Atualizações

Correção **0.6.5-kotlin**: melhora a fluidez do ponteiro em conexões com maior tempo de resposta, preserva o último movimento ao levantar o dedo e mantém cliques na ordem dos movimentos. A fila de movimentos continua limitada e descarta movimento local antigo durante congestionamento. Veja a [validação](docs/TESTING.md) e as [notas da release](docs/releases/0.6.5-kotlin.md).

Correção **0.6.4-kotlin**: elimina o fechamento na abertura introduzido na 0.6.3 e usa o resultado do instalador do Android para reabrir o app após atualizações iniciadas dentro dele, quando autorizado. Se a versão 0.6.3 não conseguir abrir, instale o APK corrigido manualmente sobre ela.

O app consulta as releases deste repositório no GitHub ao abrir e a cada 6 horas, ou em Conexão → **Verificar agora**. Só contam tags `vX.Y.Z-kotlin`. Com versão nova, aparece um aviso no topo e um ponto na aba Conexão; as notas abrem numa folha.

Na 0.6.4, o sistema solicita a reabertura pelo resultado da instalação. Atualizações iniciadas por versões anteriores ainda usam a notificação **PC Remote atualizado para X** para abrir a versão nova.

No Android 10 ou mais novo, a reabertura automática exige a autorização opcional **Exibir sobre outros apps**. Antes da primeira atualização, escolha **Abrir ajustes**, selecione **PC Remote** e permita. Essa autorização é persistente e permite ao app voltar sobre outra tela após atualizar. **Só atualizar** continua sem essa autorização; nesse caso, abra pelo ícone ou pela notificação, se habilitada. O Android encerra o processo durante a substituição do APK.

A partir da 0.5.0 a atualização acontece dentro do app:

- **Baixar atualizações automaticamente** (Conexão → Atualizações, ligado por padrão): em Wi-Fi ou rede sem limite de dados, o app baixa e confere a versão nova enquanto está aberto. O aviso passa a **Atualização pronta — toque para instalar**. Nada é instalado sem o seu toque em **Instalar agora**.
- Com a opção desligada, ou em dados móveis, **Atualizar agora** baixa, confere e instala de uma vez, com progresso.
- Antes de instalar, o app exige que o APK tenha o SHA-256 publicado no `KOTLIN-SHA256SUMS` da mesma release, o mesmo pacote, um versionCode maior e a mesma assinatura do app instalado. O Android confere a assinatura de novo.
- Na primeira vez, o Android pede para autorizar o PC Remote a **instalar apps desconhecidos** (Permitir desta fonte). No Android 13+ o app também pede, uma vez, permissão de notificações.
- No Android 12 ou mais novo, depois dessa autorização, as atualizações instalam sem outra confirmação do sistema (nos testes em Android 12 e 13, já na primeira). O app fecha durante a instalação e a notificação **PC Remote atualizado para X** abre a versão nova. Quando o Android pede confirmação (sempre no Android 7 a 11; no 13, numa segunda atualização em menos de 30 segundos), toque em **Atualizar**.
- A própria 0.5.0 se instala do jeito antigo, uma vez: baixe o APK no navegador e abra-o. Da 0.5.0 em diante a atualização já acontece dentro do app. Pareamentos e preferências são mantidos.

**Ver no GitHub** continua como alternativa, e **Agora não** esconde o aviso daquela versão.

## Pareamento e uso

No PC, abra **Parear celular**. No Android, digite o código de 8 dígitos e escolha o PC encontrado na rede, ou leia o QR. Aprove o pedido no Windows. Com o QR, o celular já conhece a identidade SHA-256 do PC. Com o código, ele fixa o certificado da primeira conexão e mostra a faixa de cores da identidade, que deve ser igual à exibida no PC. WSS usa o certificado fixado, validação normal do hostname e credenciais individuais cifradas pelo Android Keystore.

Volume e mudo refletem o Windows; o slider limita a frequência e confirma o valor final. Nas abas Touchpad e Mídia, com o PC conectado, os botões de volume do celular mudam o volume do PC de 5 em 5% (no Touchpad aparece "Volume do PC: NN%"); nas outras abas, ou desconectado, controlam o celular. Mídia segue o player que o Windows trata como atual; toque em outro player para escolhê-lo. Reproduzir/pausar, faixa anterior/próxima e posição **−10s/+10s** têm capacidades distintas; ações indisponíveis ficam desabilitadas, com explicação. Veja [compatibilidade de mídia](docs/MEDIA-COMPATIBILITY.md); não se promete controle de todo player.

No topo de Mídia, **Pesquisar no YouTube** abre a pesquisa no navegador padrão do PC. O microfone ao lado aceita a pesquisa falada ou comandos como "pausar", "continuar", "próxima música", "voltar 10 segundos" e "aumentar o volume"; a permissão do microfone é pedida só no primeiro toque, cada toque ouve uma frase e a escuta termina sozinha. O reconhecimento é o do próprio Android.

**Enviar o que copiei** lê a cópia atual do Android. **Enviar texto** envia o campo, inclusive texto de **Compartilhar → PC Remote**. **Buscar do PC** copia no celular a cópia atual do Windows. Esses envios manuais funcionam com a sincronização desligada.

**Sincronização em tempo real** já vem ligada: o que você copia no PC vai para a área de transferência do celular (também com o app em segundo plano, enquanto a conexão existir), e o que você copia no celular vai para o PC. Desligada em Conexão → Preferências, nada é sincronizado sozinho. Com o desktop 0.6.1, imagens copiadas (um print, "Copiar imagem" no navegador) também vão e vêm, até 32 MB: a do PC chega pronta para colar, e uma imagem copiada em outro app vai ao PC quando você volta ao PC Remote; **Enviar o que copiei** e **Buscar do PC** também levam imagens. Texto fica em RAM, sem histórico persistente ou logs de conteúdo. Limite **64 KiB UTF-8**, preservando Unicode e quebras; NUL/Unicode inválido são recusados. Conteúdo sinalizado sensível é bloqueado; nem todas as senhas podem ser detectadas.

Em **Transferir → Arquivos**, **Enviar arquivo** abre o seletor do Android; também dá para usar **Compartilhar → PC Remote** em qualquer app. Qualquer tipo de até 1 GB, vários de uma vez (fila de até 20, um por vez), com progresso e **Cancelar**. O PC salva em `Downloads\PC Remote`, sem sobrescrever. Arquivos enviados pelo PC chegam sozinhos em `Download/PC Remote` (no Android 7 a 9, na pasta do app); **Recentes** lista os últimos e abre cada um. Durante uma transferência iniciada com o app aberto, uma notificação mostra o progresso e mantém o envio vivo se você trocar de app. Com PC 0.4 ou 0.5, só PNG, JPG e PDF de até 50 MB, do celular para o PC. Arquivos compartilhados sem conexão esperam na fila e saem quando o PC conecta.

Android 10+ restringe leitura do clipboard de outros apps em segundo plano: o que você copiar em outro app vai para o PC quando você voltar ao PC Remote, uma vez. Sem serviço permanente (só durante transferências de arquivo e enquanto os controles de mídia aparecem fora do app) nem garantia de conexão durante suspensão. Eventos que chegam antes da suspensão podem apresentar aviso genérico, quando autorizado. Texto antigo não é reenviado ao parear, reconectar ou ligar a sincronização. Atualizar clipboard não abre links, executa comandos ou cola em apps.

## Touchpad e teclado

Depois do pareamento o touchpad já está pronto, sem ligar nada no PC. Um dedo move o cursor, com aceleração, e um toque clica. Dois dedos rolam, e um toque com dois dedos abre o menu de contexto. A faixa à direita rola como uma barra de rolagem. Há teclas rápidas (Esc, Tab, Enter, espaço, apagar, setas, Del, Home, End e duplo clique) e botões de clique. O botão de teclado abre **Digitar no PC**: o texto só vai com **Enviar** ou **Enviar + Enter**, até **1.024 bytes UTF-8** por comando. O alvo é a janela em foco no Windows; não há transmissão da tela. O touchpad continua ligado ao trocar de aba. Sair do app ou perder o foco desarma o PC na hora, e ele volta sozinho quando o app retorna, na mesma conexão. **Desligar** vale até **Ativar touchpad**. **Interromper** no PC bloqueia o touchpad até **Liberar** no PC; com PC 0.4 ou 0.5, cada conexão pede **Ativar touchpad** e o PC precisa de **Permitir mouse e teclado pelo celular**. Aplicativos elevados/telas protegidas podem bloquear SendInput. Veja [INPUT.md](docs/INPUT.md).

## Documentação e testes

[Arquitetura](docs/ARCHITECTURE.md), [protocolo](docs/PROTOCOL.md), [testes e pendências](docs/TESTING.md). Contrato e exemplos são idênticos aos do desktop. O app 0.6.0 funciona com desktop 0.2.x a 0.6.0; arquivos de até 1 GB nos dois sentidos, pesquisa e o nome do Windows exigem o desktop 0.6.0; imagens e PDFs exigem o 0.4.0; desktop 0.1.x conserva volume, mídia e clipboard, sem entrada remota. Na 0.6.1, energia do PC e imagens na área de transferência exigem o desktop 0.6.1; vários PCs e os controles de mídia fora do app funcionam com qualquer desktop compatível.

O [template CI](docs/ci/github-actions.yml) testa Kotlin, executa lint e gera APK sem Node. A credencial GitHub disponível não tem escopo `workflow`; para ativá-lo, publique em `.github/workflows/build.yml` com autenticação apropriada.

A versão 0.2.1 corrige a recópia de texto recebido, estabelece baseline no ganho de foco, evita escritas idênticas e detalha erros de clipboard. Há uma [fixture opcional de validação](verification/clipboard-probe/README.md), separada do APK distribuído.

A versão 0.2.3 inclui a correção de fechamento do leitor QR da 0.2.2 e revisa pareamento, falhas de gravação protegida, troca de rede e informações da sessão de mídia. Atualize instalando o APK sobre a versão Kotlin anterior; o pareamento salvo é preservado quando a assinatura corresponde. O leitor requer Google Play Services e pode precisar baixar seu módulo na primeira utilização; o pareamento por código funciona sem esse módulo.

A versão 0.2.4 confirma o volume alterado por teclado ou acessibilidade, desativa entrada ao sair da tela de controle e descarta previews de clipboard quando a conexão termina.

A versão 0.3.0 traz a interface nova, com abas, temas, touchpad com gestos e aviso de atualização.

A versão 0.4.0 envia imagens e PDFs ao PC, mantém o touchpad ligado ao trocar de aba e usa os botões de volume do celular no volume do PC nas abas Touchpad e Mídia.

A versão 0.5.0 baixa, confere e instala as próprias atualizações, com download automático opcional em redes sem limite de dados.

A versão 0.6.0 simplifica a Conexão e as preferências, deixa touchpad e área de transferência prontos após o pareamento, troca arquivos de até 1 GB nos dois sentidos, acrescenta pesquisa e comandos por voz e remove a vibração.

A versão 0.6.1 guarda vários PCs, leva os controles de mídia para a notificação, a tela de bloqueio, um widget e um bloco das Configurações rápidas, bloqueia, suspende, desliga ou reinicia o PC e sincroniza imagens na área de transferência.

A revisão de 15/10/2026 está documentada na [auditoria de segurança](docs/SECURITY-AUDIT.md) e no [relatório de bugs e regressões](docs/BUG-AUDIT-2026-10-15.md), com evidências e limites da validação.
