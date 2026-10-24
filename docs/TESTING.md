# Validação — Android Kotlin

Registro da alternativa 0.2.0-kotlin em 14/10/2026. Os resultados React Native da branch main não são apresentados como testes desta implementação. Integrações reais: Android SDK/Keystore/OkHttp e Windows Rust/Core Audio/GSMTC/clipboard/SendInput. Não há simulações no produto.

## Release 0.6.7-kotlin — validação para publicação, 24/10/2026

- `versionCode` 607 e `versionName` `0.6.7-kotlin`, pacote `com.pcremote.nativeandroid`. A assinatura v2 foi conferida contra o APK 0.6.6 baixado da release publicada: mesmo certificado SHA-256 `70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`.
- `testDebugUnitTest assembleRelease lintRelease --no-daemon --console=plain`: sucesso em 58 s; 24 suítes, **166 testes passaram**, zero falhas/erros/ignorados. Lint: zero erros e 28 avisos.
- APK universal otimizado: **762593 bytes**; SHA-256 `d9296110b67b550235ca9e006acdef41fb297a9b1bcef6c79e9029260a6895a4`. Assets: `pc-remote-android-kotlin-0.6.7-evaluation.apk` e `KOTLIN-SHA256SUMS-0.6.7.txt` em `release-assets/0.6.7/`.
- Evidências desta build: `artifacts/release-0.6.7/gradle-final.log` e `validation.txt`. A revisão de código e a validação visual da correção estão no registro de 23/10 abaixo; para a release foram alterados somente versão e documentação. A conexão com dois PCs físicos continua não verificada.

## Correção local — conexão com outro PC, 23/10/2026

- Ao trocar de PC, o cliente consultava o endereço salvo mesmo quando já havia descoberto um IP novo para o mesmo `pcId`. Agora consulta a descoberta antes de abrir cada conexão; certificado e token continuam sendo os do pareamento salvo. O endereço atualizado só é persistido depois do `welcome` autenticado.
- O início da conexão sobrescrevia `Pareando` por `Conectando`, ocultando o estado de espera e cancelamento. O pareamento conserva seu estado até terminar; durante a espera, oculta o nome do PC anterior e os cartões de PC salvo e energia. Um endereço temporário não é mais apresentado como PC pareado. Falha ou cancelamento restaura o PC anterior; uma falha ao adicionar outro PC não inicia uma reconexão automática ao anterior que esconda o resultado.
- Resoluções mDNS agora usam fila limitada e uma operação nativa por vez, inclusive entre parar e reiniciar a descoberta. Callbacks de buscas anteriores liberam a operação sem publicar dados antigos. A descoberta usa IPv4 entre os endereços disponíveis na API 34+, e mantém o multicast lock somente durante a busca. Veja as referências oficiais de [NsdManager](https://developer.android.com/reference/android/net/nsd/NsdManager) e [NsdServiceInfo](https://developer.android.com/reference/android/net/nsd/NsdServiceInfo).
- Falhas de certificado, conexão segura, tempo limite, conexão recusada e rede indisponível têm orientações distintas, sem mostrar mensagens internas de exceções.
- `testDebugUnitTest assembleDebug assembleRelease lintRelease`: sucesso. **166 testes passaram**, zero falhas/erros/ignorados; 10 regressões novas cobrem seleção de IP com identidade preservada, fila serial, resolução antiga ainda ativa, callback duplicado, serviço perdido/reencontrado, limite da fila e classificação de falhas. Lint: zero erros e 28 avisos.
- Revisão independente aprovou o candidato após corrigir o controle da operação nativa de descoberta. APK release com assinatura v2 validada; versão local permanece `0.6.6-kotlin`, sem publicação.
- Emulador Ritual_Pixel, Android 16/API 36, sessão somente leitura: o candidato final mostra `Pareando`, `Conectando…` e `Cancelar`, sem o cartão indevido de PC pareado/energia (`07-candidate-waiting-final.png`). Falha de rede retorna ao formulário com orientação de tempo limite, sem crash. Cancelamento foi observado em `05-candidate-cancelled.png`, antes do ajuste final de visibilidade; na última captura (`08`), o tempo limite pode ter ocorrido antes do toque, portanto ela não comprova a ordem entre cancelar e expirar. A restauração de um PC anterior e a descoberta real de dois PCs não foram verificadas no emulador.
- APK final: `artifacts/qa/network-other-pc/20261023/pc-remote-android-release.apk`; SHA-256 `3770b8cbe87d640de5e79c4c4d73675f70a438ec6b90b8149fee8fd91aad2b13`.
- Evidências de build e emulador: `artifacts/qa/network-other-pc/20261023/` neste repositório (pasta de artefatos ignorada pelo Git). A ocorrência no segundo PC físico não foi reproduzida: seu endereço, firewall e mensagem original não estavam disponíveis. A inspeção deste Windows confirmou o serviço instalado ouvindo em TCP 43127 e regras permitindo seu executável; os bloqueios encontrados pertenciam a outra build local.
- Semantica indisponível nesta sessão; decisões e limites foram registrados aqui, sem memória externa.

## Correção 0.6.5-kotlin — cadência do ponteiro, 19/10/2026

- `testDebugUnitTest lintRelease assembleRelease assembleDebug`: sucesso; **156 testes passaram**, zero falhas. Lint release: zero erros e 28 avisos.
- Regressões JVM: cadência e agrupamento; janela adaptada ao RTT; limite real entre gestos cancelados; ACK antigo ou duplicado; erro e cancelamento; expiração sob congestionamento; último frame sem novo evento de toque; ordem movimento → clique → movimento; frações em arrasto lento; callback síncrono; entrada inválida/inativa.
- Comparação independente com o `InputMotion.kt` real da 0.6.4, congelado no commit `9b0a75c`, em cópias isoladas do projeto Gradle. Relógio, scheduler e ACKs simulados; nenhum movimento do cursor físico. A 0.6.5 recebe uma amostra de RTT de ativação antes dos cenários indicados; o app faz isso com ACKs de `input.enable` e `state.get`. A 0.6.4 não usa essa amostra.

| Entrada e rede simuladas | Maior intervalo entre envios, 0.6.4 | 0.6.5 com RTT de ativação |
|---|---:|---:|
| 120 Hz, RTT 100 ms | 20 ms | 16 ms |
| 120 Hz, RTT 200 ms | 120 ms | 16 ms |

- Cinco gestos cancelados com ACKs retidos: a versão anterior acumulava 30 pedidos realmente sem confirmação embora zerasse o contador interno; a correção manteve 6 nesse cenário sem amostra de RTT. A janela adaptativa tem teto de 16, também coberto por teste unitário.
- Limites: sem amostra inicial, o primeiro atraso ainda pode ocorrer até o primeiro ACK. O mesmo jitter súbito do baseline conservou intervalo máximo de 70 ms; a pausa de ACKs de 500 ms conservou intervalo de 420 ms. A expiração de 80 ms descarta parte do movimento ainda local sob congestionamento. Não equivale a cancelamento de pedidos já no socket nem elimina a latência da rede.
- Artefato release: **726.185 bytes**, versionCode 605, SHA-256 `55480613150f71cdb90a82fba7acef278a8ab0f40492dfedd09432e0993d9197`. `apksigner verify --print-certs` confirmou o mesmo certificado da 0.6.4 (`70c09aad2f64bd4a0ff345b63347b7c27c776bc0d52d8b23f9db5a04ac74aa32`).
- Android 12/API31, AVD dedicado em `emulator-5556`: APK release exato instalado com `adb install -r` sobre a 0.6.4, sem limpar dados. Preferência de sincronização marcada como desativada na 0.6.4 permaneceu desativada na 0.6.5; Maestro aprovou abertura, cinco abas e Deck/123/Slides; buffer de crash vazio. A primeira tentativa, ainda na 0.6.4, encontrou ANR de `com.android.systemui` após boot do emulador; escolher **Wait** estabilizou o sistema e a repetição passou. O emulador pessoal `5554` permaneceu intocado.
- Integração WSS/TLS da build debug 605 no mesmo API31, com servidor falso sem executor de mouse: pareamento e ativação aprovados com ACKs de `state.get`/`input.enable` atrasados em 200 ms. Um swipe de 700 ms enviou 16 movimentos antes de liberar os ACKs, confirmando que a janela ultrapassou os seis da versão anterior; máximo global 16 pedidos sem confirmação. Após liberar, 16 ACKs e zero pendências, MainActivity em foco e buffer de crash vazio. Esse teste confirma a integração da janela; não mede latência física nem verifica diretamente a corrida de detach. A primeira entrega do convite foi rejeitada por quoting do shell adb, sem conexão; repetir com JSON preservado resolveu a fixture.
- Evidências locais ignoradas pelo Git em `artifacts/qa/pointer-latency/`: baseline e candidato em CSV, harness, logs Gradle e relatório independente. Semantica indisponível nesta sessão; a memória durável não foi registrada.
- Celular físico, Wi-Fi real e latência até o cursor desenhado no Windows permanecem sem validação nesta rodada.

## Correção 0.6.4-kotlin — abertura e retorno da atualização, 18/10/2026

- `testDebugUnitTest lintRelease assembleRelease assembleDebug`: sucesso; **145 testes passaram**, zero falhas. Lint release: zero erros e 28 avisos.
- Android 12/API31, AVD dedicado `PCRemote_API31`, sessão somente leitura em `emulator-5556`. O emulador pessoal `emulator-5554` não foi modificado.
- O APK release publicado da 0.6.3 foi reproduzido fechando na abertura: NullPointerException no construtor da tela Controles, chamado por MainActivity. O APK release otimizado 0.6.4 foi instalado por cima e abriu normalmente; navegação nas cinco abas e seleção de Deck/123/Slides sem crash.
- O APK final tem 724.989 bytes, mesmo pacote e mesmo certificado da 0.6.3; SHA-256 `baabf15d25d84cd821bcf4ecccdb7ac99011e598f22674dfc2e945e2c0360126`.
- Esse arquivo final exato foi instalado com `adb install -r -d` sobre a fixture 609, sem limpar dados. Cold start da 0.6.4 aprovado: MainActivity em foco, versão 604, buffer de crash vazio, roteiro Maestro completo aprovado e preferência de sincronização ainda desativada.
- Atualização real pelo PackageInstaller, fixture debug 606 → release otimizada 607 com o mesmo código da correção: autorização **Exibir sobre outros apps** concedida pela UI do Android, APK substituído e MainActivity retornando sozinha em novo processo, sem comando de abertura após instalar. O log do sistema confirma que a autorização permitiu iniciar a Activity. A preferência `realtime=false` permaneceu salva.
- Alternativa sem autorização, fixture 608 → release 609: autorização revogada nos ajustes, escolha **Só atualizar**, versão nova instalada sem tomar o foco e notificação **PC Remote atualizado para 0.6.9 — Toque para abrir.** presente. Tocar na notificação abriu a versão nova, sem estado Installing preso; `realtime=false` e a autorização negada foram preservados.
- O roteiro `.maestro/flows/android12-startup-controls.yaml` passou no Maestro CLI no API31: abertura, cinco abas e seleção de Deck/123/Slides, sem crash. Os números 606 a 609 são apenas fixtures locais; não são releases publicadas.
- Evidências locais em `artifacts/qa/android12-update/`, na raiz do workspace, fora do repositório. Celular físico e retorno automático no Android 15/16 não foram validados.

## Atualização 0.6.1-kotlin — vários PCs, mídia fora do app, energia do PC e imagens na área de transferência, 16/10/2026

### Testes automatizados

- `testDebugUnitTest assembleDebug lintDebug`: sucesso. **137 testes JUnit passaram**, sem falhas. Lint debug: zero erros e 26 avisos, nenhum novo.
- `assembleRelease assembleSizeBaseline lintRelease`: sucesso. APK release (R8) de **759.953 bytes** (725.441 na 0.6.0, +34,5 KB), sizeBaseline de 2.634.807 bytes, lint release com zero erros e os mesmos 25 avisos da 0.6.0. Mesmo certificado (`70c09aad…74aa32`). O manifesto release tem `ClipImageProvider`, `MediaControlService` (`mediaPlayback`), `MediaWidget` e `MediaTileService` protegido por `BIND_QUICK_SETTINGS_TILE`, sem `DebugInviteReceiver` nem VIBRATE. O teste de ponta a ponta usou o APK debug; depois do bump para o versionCode 601, o APK release foi instalado por cima dele no Android 16 e testado com o executável release assinado do PC: pareamento mantido, agendar e cancelar energia, imagem do PC copiada no celular e controles de mídia (Pausar, Fechar).
- Novos: `PcListTest` (migração do pareamento único das versões anteriores com o nome do Windows, parear de novo o mesmo PC substitui o registro, limite de 8, remover o atual passa ao usado mais recentemente, ordem da lista, JSON defensivo, registro sem `pcId` identificado pela identidade fixada), `MediaRulesTest` (sessão seguida, alternar vira pausar ou tocar, comandos conforme as capacidades, quando o serviço começa e termina, textos do widget e do bloco), `Negotiated061Test` (exemplos `negotiated061`, estado `power`, welcome e `file.push` estritos, builders de energia), `ClipImagesTest` (limites, sincronização e revisão dos pushes, PNG/JPEG enviados como estão e o resto convertido, chaves de imagem que não colidem com texto, imagens da própria autoridade, tracker com imagens, regras das ofertas) e `PowerLabelsTest` (prazos oferecidos, contagem e mensagens).
- `CredentialStore`, `MediaControlService`, `MediaTileService`, `MediaWidget`, `PowerPanel` e `ClipImageProvider` dependem do Keystore e do sistema e foram cobertos pelos testes de ponta a ponta abaixo.

### Ponta a ponta: PC real + emuladores Android 13 e 16

Windows 11 Pro (build 26300) com o executável release desta branch (`tauri build --no-bundle`), o AVD PCRemote_API33 (Android 13) e o PCRemote_Validation (Android 16, API 36), ambos com o APK debug da branch. Pareamento por convite entregue pelo adb ao `DebugInviteReceiver` (só na build debug), com código e aprovação no PC. A ponte de clipboard do emulador ficou desligada durante o teste. Para ter dois PCs com um computador só, a segunda instância rodou com `APPDATA` próprio (identidade e certificado novos) e `COMPUTERNAME=PC-TESTE-B`, uma de cada vez na porta 43127. Evidências em `artifacts/qa/0.6.1-e2e/` (fora dos repositórios).

| Cenário | Resultado |
|---|---|
| Atualizar o celular 0.5.0 → 0.6.0 → 0.6.1 (Android 13) | Pareamento migrado para a lista `computers`; `credential` e `pcOs` removidas depois de gravar; reconectou sozinho com "Windows 11 Pro" |
| Celular 0.6.0 com PC 0.6.1 | Conectou com touchpad armado: o PC não mandou campos novos a quem não negociou |
| Agendar energia | "Desligar · Em 15 min": celular "Desliga às 22:24 (em 15 min)" (fuso UTC do emulador); painel "Desligar às 19:24 · pedido por Google sdk_gphone64_x86_64" com Cancelar; notificação do Windows |
| Cancelar | Pelo celular e pelo botão do painel; some nos dois lados |
| Interromper com agendamento | Agendamento descartado; celular com os quatro botões desabilitados e "Bloqueado no PC. Libere no PC Remote do Windows."; PC "Mouse, teclado e energia bloqueados"; Liberar reabilitou |
| Bloquear agora (Android 16) | O Windows bloqueou (LogonUI) e o celular continuou conectado |
| Imagem PC → celular, formato PNG | 1920×1080, 289.192 bytes, SHA-256 igual ao original; "Imagem do PC copiada no celular." com miniatura; o Gboard ofereceu a imagem e colou como anexo no Google Mensagens (nada enviado) |
| Imagem PC → celular, só bitmap | Convertida em PNG 800×600; 9.890 pixels amostrados iguais ao original, opaca; o cache guardou só a última |
| Sincronização desligada no celular | Push automático não chegou; "Buscar do PC" copiou a imagem (chip Manual) |
| Texto e imagem juntos no PC | Chegou o texto; a imagem foi ignorada |
| Imagem celular → PC ("Copy image" no Chrome, voltar ao app) | Android 13 e 16: PNG com SHA-256 igual ao do arquivo original (5.969 e 13.504 bytes); o Windows ficou com `PNG`, `CF_DIBV5` e bitmap. Funcionou também depois de 90 s com o processo congelado pelo Android 16 (`isFrozen=true`). "Enviar o que copiei" enviou a imagem. A primeira de cinco tentativas automáticas, logo depois das telas iniciais do Chrome, não enviou e não se repetiu |
| Eco | **Achado e corrigido:** o PC devolvia ao celular o que acabara de receber dele, texto também (já acontecia na 0.6.0). `CloseClipboard` acrescenta os formatos sintetizados e avança o número de sequência, e o PC guardava o número lido antes de fechar. Agora a escrita devolve o número depois de fechar; o histórico mostra só "Recebido do celular" |
| Vários PCs | O celular recusou a instância B no endereço do PC A (identidade fixada não confere). Convite + aprovação: PC-TESTE-B virou o atual e DESKTOP-HT6RNN3 foi para "Outros PCs". Seletor "Trocar de PC" na barra; trocar reconectou ao A; remover o B com confirmação tirou o cartão e o seletor |
| Mídia fora do app (Android 13) | Serviço `mediaPlayback` ao abrir o app com o PC tocando; controles na área de notificações e na tela de bloqueio ("PC Remote teste de midia · Reprodutor Multimídia"); Pausar e Tocar mudaram o Reprodutor Multimídia; continuou funcionando com Doze forçado por 40 s; "Fechar" encerrou o serviço |
| Mídia fora do app (Android 16) | Serviço `mediaPlayback` com notificação não dispensável; Pausar e Fechar |
| Bloco "Mídia do PC" | Android 13 e 16: conectado, alterna na hora; com o processo encerrado, sobe o serviço, conecta e alterna |
| Widget "Mídia do PC" | Android 13 e 16: com o processo encerrado, o toque sobe o serviço, conecta e alterna |

Não testado de ponta a ponta: suspender, desligar e reiniciar de verdade (encerrariam a sessão de teste; cobertos por testes com executor de registro), aviso de 60 s antes do prazo, limite de 3 pedidos em 10 s, limite de 8 PCs e "Remover este PC" passando ao próximo (testes JVM), conversão de WebP/GIF/HEIC no celular e imagens acima de 32 MB, celular físico e Wi-Fi real, Android 7 a 12. O seletor de saída dos controles de mídia do Android mostra "This phone": é o padrão do sistema para uma sessão local, e o app não assume as teclas de volume do celular.

## Atualização 0.6.0-kotlin — menos chaves, arquivos de até 1 GB nos dois sentidos, voz e sem vibração, 16/10/2026

### Testes automatizados

- `testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease`: sucesso. **109 testes JUnit passaram**, sem falhas. Lint release: zero erros e 25 avisos (26 na 0.5.0). APK release com 725.437 bytes; sizeBaseline com 2.587.047.
- Novos ou ampliados: `VoiceCommandsTest` (comandos com acentos e palavras de preenchimento, pesquisas sem o verbo e sem "no YouTube", limite de 200 caracteres sem partir emoji), `ProtocolTest` (todos os exemplos `negotiated06`: welcome com `os` e capacidades novas, `file.push`, mensagens do socket nos dois sentidos, `file.decline`, `search.open`), `FileRulesTest` (regras files.v1/files.v2, nomes, MIME, rótulos de progresso e de limite), `InputIntentTest` (armar sozinho, Desligar persistente, bloqueio do PC mantendo a intenção) e `ClipboardTrackerTest` (texto copiado fora do app enviado uma vez ao voltar, sem eco).
- `FileReceiver`, `TransferService` e `VoiceInput` dependem de ContentResolver, MediaStore, OkHttp e SpeechRecognizer e foram cobertos pelos testes de ponta a ponta abaixo.

### Ponta a ponta: PC real + emulador

Windows 11 Pro (build 26300) com o executável release desta branch (`tauri build --no-bundle`) e o AVD PCRemote_API33 (Android 13, Google APIs x86_64) com o APK debug e, no fim, o APK release (R8) instalado por cima, com a mesma chave. Pareamento por convite entregue pelo adb ao `DebugInviteReceiver`, que só existe na build debug (`src/debug`): o emulador não lê o QR do PC nem enxerga o mDNS. O código e a aprovação no PC continuam obrigatórios. **A ponte de clipboard do emulador foi desligada** (`HKCU\Software\Android Open Source Project\Emulator\set\clipboardSharing = false`, lido ao iniciar o emulador): ligada, ela copia texto entre Windows e Android sozinha, invalida os testes de clipboard e chegou a deixar o clipboard do Windows vazio. Evidências em `artifacts/qa/0.6-e2e/` (fora dos repositórios).

| Cenário | Resultado |
|---|---|
| Parear | Aprovado no PC; o celular abriu no Touchpad já armado, sem tocar em "Ativar touchpad" nem ligar nada no PC. Um arrasto no touchpad moveu o cursor do Windows (+376, +127; posição restaurada) |
| Seção Conexão | Só nome do PC, "Conectado", "Windows 11 Pro" (info.v1) e Desconectar / Outro PC / Remover este PC. Sem latência, IP, endereço salvo ou identidade |
| Clipboard PC → celular | Texto Unicode ("– ação ✓") chegou em "Texto do PC" e na área de transferência do celular (colado num campo) |
| Clipboard celular → PC | Cópia no app foi para o Windows na hora |
| Copiado em outro app (Contatos) | Nada enviado com o app em segundo plano; enviado uma vez ao voltar, sem eco |
| PC → celular com o app em segundo plano | Texto disponível para colar no app Contatos (gravação permitida em segundo plano) |
| Sincronização em tempo real desligada | Chip "Manual"; cópia no PC não chegou e cópia no celular não saiu; "Enviar texto" funcionou e "Buscar do PC" copiou direto no celular |
| 1 GiB PC → celular | Seletor nativo "Enviar arquivos ao celular"; 1.073.741.824 bytes em `Download/PC Remote`, SHA-256 igual ao original, cerca de 2 min 50 s pelo NAT do emulador; serviço em primeiro plano `dataSync` ativo durante o envio; PC com 84 MB de memória de trabalho e celular com 9 MB de heap Java depois do envio |
| 1 GiB celular → PC | Escolhido no seletor do Android; SHA-256 igual, `Zone.Identifier` ZoneId=3, nenhuma `.pcremote-part` restante, cerca de 3 min |
| Push com o APK release | `Relatório teste 0.6.mp4` (5 MB) com nome Unicode preservado e SHA-256 igual |
| Interromper (PC) | Celular mostrou "Bloqueado no PC" na hora, sem cair a conexão; o bloqueio continuou depois de encerrar e reabrir o app; `settings.json` com `inputBlocked:true` e `allowInput:false` para versões antigas |
| Liberar (PC) | Celular voltou a armar o touchpad sozinho |
| Mídia | Ver [MEDIA-COMPATIBILITY.md](MEDIA-COMPATIBILITY.md): Reprodutor Multimídia com play/pause, anterior/próxima e ±10 s pelo celular, estado do botão coerente; YouTube no Chrome detectado com os controles certos e como primeira sessão; Spotify não instalado |
| Pesquisa (search.v1) | Texto digitado no celular abriu `youtube.com/results?search_query=lofi+hip+hop+radio` no Chrome do PC |
| Voz | Permissão do microfone pedida só no primeiro toque; recusar mostra "Sem o microfone, digite a pesquisa."; permitir abre o painel "Ouvindo… / Pode falar." com o indicador de microfone do Android. Achado e corrigido: o reconhecedor do emulador (sem pacote offline pt-BR) parava de gravar sem nunca responder e o painel ficava preso; agora o app desiste após 8 s sem fala (20 s no total) e mostra "Não entendi. Toque no microfone e tente de novo." Reconhecimento de fala real não validado: o emulador não recebe voz |
| Vibração | Toques em teclas, abas, chave e duplo toque no touchpad: nenhuma vibração do app em `dumpsys vibrator_manager`; permissão VIBRATE removida |
| Celular 0.6 + PC 0.5.0 instalado | Touchpad não arma sozinho ("Ativar touchpad"), sem nome do sistema, Transferir explica "Este PC aceita PNG, JPG ou PDF de até 50 MB…" |
| APK release (R8) | Instalado por cima da debug mantendo o pareamento; não depurável; todas as abas, microfone e envio sem crash |

Achados corrigidos durante a revisão independente e o teste: rearmar automático contra um PC antigo depois da parada de emergência dele; recusa em cascata de arquivos após cancelar no PC; configurações antigas do painel desfazendo o bloqueio; thread de UI esperando o fsync de um arquivo grande; cancelamento logo depois de salvar apagando o arquivo; nome acima de 255 bytes derrubando a conexão; erro do celular com id nulo lido como protocolo inválido; pesquisa cortada no meio de um emoji; painel de voz preso; rótulo "0,0 de 1,0 GB" no começo de arquivos grandes (agora "43,5 MB de 1,0 GB").

Não testado: celular físico e Wi-Fi real, Android 7 a 9 (gravação na pasta do app), Android 14+ com o serviço `dataSync`, comandos pelo celular no YouTube (aba fechada no meio do teste), Spotify, reconhecimento de fala real, arrastar arquivos para a janela do PC (o caminho do código é o mesmo da fila do seletor) e `powercfg /requests` (exige administrador; o pedido de energia tem teste unitário).

## Atualização 0.5.0-kotlin — atualização dentro do app, 16/10/2026

### Testes automatizados

- `testDebugUnitTest assembleDebug assembleRelease assembleSizeBaseline lintRelease`: sucesso. **92 testes JUnit passaram**, sem falhas. Lint release: zero erros e 26 avisos; o mesmo lint sobre a 0.4.0, no mesmo ambiente e data, dá 28. Saíram três SetTextI18n da folha antiga e entrou um AndroidGradlePluginVersion, que depende da data; nenhum aviso nos arquivos novos. O R8 passou sem regra nova de keep e manteve os nomes de UpdateInstallReceiver e UpdatedReceiver.
- Testes novos: UpdateRulesTest (14) cobre a lista de origens e redirecionamentos (outro repositório, `..`, `%2e`, query, HTTP, outra porta, userinfo), a origem de teste, o formato do SHA256SUMS, a identidade do APK (pacote, versionCode, assinatura), a limpeza do cache, as mensagens de falha da sessão, a máquina de estados (download automático para em pronta; o toque instala; Agora não desliga a instalação automática; cancelar mantém o arquivo) e os textos do aviso e do botão. ReleasesTest ganhou 2 (SHA256SUMS da mesma versão e origem de teste) e passou a exigir arquivos deste repositório.
- Manifesto mesclado do release sem `networkSecurityConfig` nem `debuggable`; o APK release não tem o XML de rede da build debug.

### Ponta a ponta no emulador API 33

AVD PCRemote_API33 (Google APIs x86_64). A cópia de teste anterior foi desinstalada. APK debug 0.5.0 (versionCode 9) instalado com `adb`; builds de teste 0.5.1 a 0.5.7 (versionCode 10 a 16) com a mesma chave debug, e uma 0.5.1 reassinada com uma chave descartável. Um servidor Python em 10.0.2.2:8765 imitou a lista de releases, os SHA256SUMS e o redirecionamento 302 do GitHub; o app foi apontado para ele com `files/debug-releases-url.txt`, escrito por `run-as`.

1. **Versão encontrada** e download automático no Wi-Fi sem limite, com progresso no aviso ("Baixando a versão 0.5.1… 20%"), na barra da folha ("1,1 de 3,1 MB") e no botão ("Baixando… 35%").
2. **SHA-256 divergente**: "O APK baixado não confere com o SHA-256 publicado. Nada foi instalado."; arquivo apagado.
3. **Outra assinatura**: "O APK baixado tem assinatura diferente da do app instalado. Nada foi instalado."
4. **Redirecionamento para 127.0.0.1**: "O download foi desviado para um endereço não permitido."; o app não conectou ao destino.
5. **0.5.1 válida por download automático** (com um redirecionamento permitido): aviso **Atualização pronta — toque para instalar**, versionCode ainda 9.
6. **Instalar agora**: permissão de notificações do Android 13, explicação e "Instalar apps desconhecidos". Voltar sem permitir mostrou a recusa na folha e não instalou. Com "Permitir desta fonte", a instalação seguiu sozinha ao voltar.
7. **Sem confirmação do sistema já na primeira vez**: 0.5.0 → 0.5.1 instalou sem tela do Android (atualização do próprio app com UPDATE_PACKAGES_WITHOUT_USER_ACTION). `dumpsys package` passou a mostrar `installerPackageName=com.pcremote.nativeandroid`. O sistema encerrou o processo, o UpdatedReceiver publicou "PC Remote atualizado para 0.5.1" e tocar abriu a 0.5.1; cache vazio.
8. **0.5.1 → 0.5.2 manual** (download automático desligado): nada baixado antes do toque; Atualizar agora baixou, conferiu e instalou **sem confirmação do sistema**. 0.5.2 → 0.5.3 e 0.5.3 → 0.5.4 também.
9. **Confirmação do sistema**: o Android 13 permite uma atualização silenciosa por app a cada 30 s. Atualizar de novo 13 s depois gerou `STATUS_PENDING_USER_ACTION` e o app abriu "Do you want to update this app?" em primeiro plano. Cancelar mostrou "Instalação cancelada." e Instalar agora, reusando o arquivo conferido sem novo download; Update instalou a 0.5.7.
10. **Rede limitada** (Wi-Fi desligado, dados móveis): com o automático ligado, nenhum pedido de APK ou SHA256SUMS. De volta ao Wi-Fi o download começou, e uma "0.5.8" com o mesmo versionCode instalado foi recusada: "O APK baixado não é mais novo que a versão instalada."
11. **Dispensar esta versão** escondeu o aviso.
12. **Release 0.5.0 (R8)**: `run-as` recusado (não debuggable), arquivo de teste ignorado, nenhum pedido ao servidor falso; consulta ao GitHub real: "Você está na versão mais recente."

Android 12 (AVD PCRemote_API31): 0.5.0 → 0.5.1 e 0.5.1 → 0.5.2 também instalaram sem confirmação do sistema depois de "Permitir desta fonte", com o aviso de atualizado.

Achados corrigidos durante o teste: mensagens da atualização ficavam atrás da folha (Dialog) e agora aparecem nela; o cliente de atualização não reaproveita conexões e tenta de novo uma vez se a conexão cair; o ícone pequeno da notificação usava o ícone do launcher, que aparecia como um quadrado. O NAT do emulador corta o fim de respostas quando o servidor fecha logo após uma escrita rápida: o app recusou o arquivo incompleto, como deve, e o servidor de teste passou a esperar 0,5 s antes de fechar.

Não testado: celular físico, Android 7 a 11 (sempre com confirmação do sistema), Android 14+ (dono da atualização), download de uma release real do GitHub, processo encerrado no meio do download e TalkBack. Capturas e dumps ficaram fora do repositório.

## Atualização 0.4.0-kotlin — touchpad persistente, teclas de volume e files.v1, 16/10/2026

### Ponta a ponta no emulador, com o desktop real

Emulador API 33 (x86_64), APK release 0.4.0-kotlin (versionCode 8) instalado com `adb install -r` por cima da 0.2.4, pareamento preservado. PC real em `10.0.2.2:43127`: primeiro o desktop 0.3.0 instalado, depois o executável release 0.4.0 com a mesma identidade. Não houve cliques nem texto enviados ao PC; só movimentos curtos do cursor, com a posição restaurada.

- Desktop 0.3.0: conexão, touchpad ativo (painel em "Em uso"), volume e mudo do PC pelas teclas físicas. Transferir → Arquivos mostrou "Atualize o PC Remote no Windows para enviar arquivos." com o botão desligado; um PNG compartilhado ficou "aguardando o PC" e nenhum pedido de arquivo chegou ao PC.
- Interface: barra superior sem "NN ms" (a latência aparece só no cartão de Conexão), Touchpad sem o chip "Controle ativo", cartão do PC em Conexão sem a faixa de cores.
- Touchpad: continuou ligado em Mídia, Conexão e Transferir (sem camada de bloqueio, PC em "Em uso", movimento curto chegou). Home e volta, cortina de notificações e o seletor de documentos desarmaram o PC e o app rearmou sozinho no retorno, mostrando **Ativando…** (captura com latência de rede artificial). **Desligar**, **Interromper controle** no PC, bloqueio de "Mouse e teclado pelo celular" e o PC fechado e reaberto deixaram o touchpad desligado, também depois de trocar de aba, de ir para segundo plano ou de reconectar.
- Teclas de volume (`input keyevent`) no Touchpad e em Mídia: 33 → 35, 35 → 30 e 33 → 30, mudo alternado, toque longo (`--longpress`) convergiu em 45 no PC e no app. O volume de mídia do celular não mudou e o painel de volume do Android não apareceu. Snackbar "Volume do PC: 35%" no Touchpad. Em Conexão e Transferir, as teclas mudaram o volume do celular.
- Arquivos com o desktop 0.4.0: com a opção do PC desligada, aviso "No Windows, abra Transferência e ligue Receber imagens e PDFs do celular." e botão desligado; ao ligar a opção no PC, o app atualizou na hora e enviou o PNG da fila. Seletor do Android com um arquivo e com dois; Compartilhar do app Arquivos com um e com três (SEND e SEND_MULTIPLE), inclusive com o app fechado. PNG, JPG e PDFs de 3 MB e 40 MB chegaram com SHA-256 igual, `Zone.Identifier` com `ZoneId=3` e nomes `(1)`, `(2)`, `(3)` sem sobrescrever. Progresso visto no app ("8,3 de 40,0 MB", 20%) e no PC.
- **Cancelar** a 20% e a opção desligada no PC a 4% (rede limitada com `tc`) terminaram sem arquivo nem parte no PC; o app mostrou "Envio de … cancelado." e "… não foi enviado. O PC não está recebendo arquivos agora.". Um `.png` com texto foi recusado no celular: "pcr_falso.png não é um PNG válido.".
- Sem envio duplicado ao reabrir pelo Recentes depois de o processo ser encerrado. Texto compartilhado, **Enviar texto**, **Enviar o que copiei** e **Buscar do PC** continuaram funcionando.
- `adb shell am start … --grant-read-uri-permission` com URI do MediaStore não serve para testar o Compartilhar: o MediaProvider recusa a leitura ao app ("O Android não deu permissão para ler o arquivo."). Por isso o Compartilhar foi feito pelo app Arquivos.
- Não testado: celular físico e Wi-Fi real, TalkBack, arquivo acima de 50 MB, tempos limite de 20 s, recusa de assinatura pelo PC (o app confere antes) e mídia com sessão ativa.

### Testes automatizados

- `testDebugUnitTest --rerun assembleDebug assembleRelease lintRelease`: sucesso. **76 testes JUnit passaram**, sem falhas. Lint release: zero erros e 29 avisos, os mesmos tipos de antes, nenhum nos arquivos novos. O R8 do release passou sem regra nova de keep.
- Testes novos: InputIntentTest (6) cobre pausa que mantém a intenção, desativação em voo que adia o rearme, bloqueio ou desligamento no PC e Desligar. TabNavigationTest (3) foi refeito: trocar de aba e receber Intent não desarmam. VolumeKeysTest (4) cobre os passos de 5%, os limites e as abas. FileRulesTest (9) cobre a limpeza de nome igual à do PC, mime, assinaturas, regras de `file.offer`, mensagens e fila. ProtocolTest ganhou 6: capacidade e estado `files` estritos, `file.ticket`, códigos novos, mensagens de `/v1/files`, ações e frames, e a leitura de `protocol-v1.examples.json`.
- FileTransfer, o motor do envio, não tem teste JVM porque depende de Handler, ContentResolver e OkHttp; foi coberto pelos testes de ponta a ponta acima.
- Repetido sobre o código da release: `testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease`, 76 testes JUnit, lint com zero erros e 29 avisos. `PROTOCOL.md` e `protocol-v1.examples.json` são idênticos byte a byte aos do desktop.

## Atualização 0.3.0-kotlin — redesign da interface, 16/10/2026

- **52 testes JUnit passaram**; `testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease` concluídos. Lint: zero erros e 29 avisos. Testes novos cobrem navegação por abas, rolagem em lotes, leitura das releases do GitHub e notas de versão.
- Emulador API 33 com o servidor TLS de teste: pareamento por convite injetado numa build de depuração temporária (removida antes do commit), touchpad ativo com gesto, teclas e painel de digitação (`keyboard.text` e `keyboard.key` chegaram ao servidor), mídia com duas sessões, Buscar do PC, temas claro e escuro, e a aba Conexão com latência e identidade.
- Atualizações contra o GitHub real: com versionName temporário 0.2.3, o app encontrou v0.2.4-kotlin, mostrou o aviso, o ponto na aba Conexão e a folha com as notas e o botão de download.
- Defeito achado neste teste e corrigido: a folha de digitação em Dialog tirava o foco da janela e desarmava a entrada. Ela passou a ser um painel na própria janela.
- APK 0.3.0 assinado com o mesmo certificado da 0.2.4 (apksigner); instalado por cima da 0.2.4 no emulador, preservando os dados.
- Pendentes: pareamento por código de ponta a ponta (o mDNS não chega ao emulador), leitura óptica de QR e celular físico.

## Atualização 0.2.4-kotlin — revisão de bugs, 15/10/2026

- **33 testes JUnit passaram**; `testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease` concluídos. Lint: zero erros e 13 avisos. As regressões cobrem volume sem toque, navegação com entrada ativada e descarte de clipboard ao encerrar a conexão.
- APK release em emulador próprio API36, com fixture TLS: alteração por DPAD para 55% foi confirmada; o estado remoto posterior de 17% apareceu na interface. Abrir Configurações ou Parear desativou a entrada. Preview recebido em segundo plano permaneceu disponível na mesma sessão e foi descartado após desconexão.
- Estes resultados não certificam celular físico nem leitura óptica de QR no Android 12/13; as pendências e evidências históricas abaixo permanecem válidas.

Consulte a [auditoria de segurança](SECURITY-AUDIT.md) e o [relatório de bugs e regressões de 15/10/2026](BUG-AUDIT-2026-10-15.md) para os achados, correções e evidências consolidadas.

## Atualização 0.2.3-kotlin — revisão de regressões, 15/10/2026

- **20 testes JUnit passaram**: protocolo (5), clipboard (4), scanner (5), gravação de credenciais (4) e estado visível da interface (2). Os novos testes reproduzem um armazenamento cujo cache muda mesmo quando commit falha, incluindo falha da restauração, primeira gravação, remoção e recuperação posterior. São testes da regra de armazenamento; Android Keystore e gravação física continuam usando as APIs nativas.
- `testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease`: sucesso. Lint: zero erros, 13 avisos. Assinatura APK v2 verificada, sem bibliotecas `.so` ou bundle JavaScript. Construtores CommonComponentRegistrar e VisionCommonRegistrar continuam presentes no relatório R8 seeds.
- Nova credencial permanece em RAM até Welcome válido. A conexão só é marcada autenticada após a gravação cifrada ter sucesso. Falha ou cancelamento mantém o PC anterior; falha de armazenamento não deixa o candidato disponível no cache para reconexão.
- Callback de socket encerrado é invalidado antes do cancelamento; retry anterior é removido. Callback de perda de uma rede antiga não encerra a rede atual. Nenhum comando antigo é reproduzido.
- Se o título muda sem trocar o ID da sessão, o seletor de mídia agora atualiza seu texto e preserva a sessão selecionada. A leitura QR pertence à Activity; foi removida a entrada duplicada do cliente global.
- A interface compara somente estado exibido antes de atualizar Views: revisões do protocolo e posição de mídia, que não é exibida, continuam no cliente sem forçar uma renderização. Testes preservam atualizações de volume, mudo, título, capacidades, entrada, texto recebido, conexão e erro. Ainda houve falha de espera por idle da UIAutomator durante a sessão real de mídia; não se considera essa espera certificada por esta otimização.
- Revisão independente do candidato não encontrou defeitos materiais nos fluxos de persistência, rollback em memória, callbacks, troca de rede, sessão de mídia e ciclo de vida QR. Uma tentativa anterior de revisão/teste foi interrompida por limite do provedor; os trabalhos foram retomados. DeepSeek não está exposto nesta sessão; o worker usou o modelo herdado, com teste Luna e revisão Astra.

Os testes de falha de gravação são determinísticos em JVM, não falhas físicas induzidas no armazenamento do celular. Recuperação após encerramento do processo durante falha de disco não foi certificada. Resultados de execução por versão Android constam abaixo quando concluídos; nenhum teste em celular físico foi realizado nesta atualização.

### Android 12 e 13 — APK final otimizado 0.2.3

| Verificação | Android 12 / API31 | Android 13 / API33 |
|---|---|---|
| Instalação e início com processo encerrado | Sucesso, `Status: ok`, `LaunchState: COLD` | Sucesso, `Status: ok`, `LaunchState: COLD` |
| Tocar em QR e repetir rapidamente três vezes | App permaneceu aberto, com orientação sobre módulo indisponível | App permaneceu aberto, com orientação sobre módulo indisponível |
| Crash, NPE original ou falha de construtor ML Kit | Ausentes nos logs capturados; buffer de crash vazio | Ausentes nos logs capturados; buffer de crash vazio |
| Abrir câmera do leitor, ler convite e cancelar | Pendente: módulo `barcode_ui` indisponível | Pendente: módulo `barcode_ui` indisponível |

Foram usados AVDs próprios com imagens oficiais Google APIs x86_64, não celulares físicos. O Google Play Services falhou ao baixar o módulo opcional, apesar de rede validada; o aplicativo apresentou o erro tratado e conservou os campos manuais. Não se declara leitura óptica validada nessas versões. O APK 0.2.1 foi reproduzido fechando no API36 antes da correção; o APK antigo não foi retestado no API31/33. Todos os testes finais usaram SHA-256 `4f14cff589b3f0c1cf1897f2436351bd579cd5195700c409d70abcdb80c7b389`, 597.197 bytes.

Os emuladores de teste foram encerrados; imagens/AVDs foram mantidos para reprodução. Nenhum celular físico ou AVD pessoal foi alterado.

### Windows real + Android API36 — APK final 0.2.3

- Pareamento manual com código/fingerprint e aprovação no PC resultou em conexão autenticada. Reinstalar com `-r` e encerrar/reabrir o processo Android recuperou a credencial e reconectou sem nova aprovação.
- Desabilitar Wi-Fi e dados móveis no emulador exibiu desconexão. Uma tentativa de novo pareamento com destino local sem servidor falhou. Após restabelecer a rede, o PC anterior reconectou; encerrar/reabrir manteve esse pareamento.
- Revogar o dispositivo de teste no PC interrompeu a conexão; Android exibiu acesso revogado. Reabrir não autenticou novamente. O dispositivo de teste foi removido e as preferências Windows permaneceram com sync e entrada desativadas.
- Abrir o QR no APK final acionou `BarcodeScanningActivityProxy` do Google Play Services, mantendo o processo Android vivo e buffer de crash vazio. Voltar retornou ao pareamento. Não houve leitura óptica de convite real.

## Atualização 0.2.2-kotlin — leitor QR, ciclo de vida e resiliência de pareamento

- **14 testes JUnit passaram**: 5 de protocolo, 4 de tracking de clipboard e 5 de comportamento do leitor QR (gate único, reuso, detach de Activity destruída, mensagens sem vazamento de exceção e detecção de cancelamento). assembleRelease, assembleSizeBaseline e lintRelease passaram (0 erros, 12 avisos).
- **Correção de ProGuard/R8**: classes que implementam `ComponentRegistrar` (Google ML Kit / Firebase Components) tiveram seus construtores `<init>()` explicitamente preservados em `proguard-rules.pro`, evitando a remoção por reflexão durante a otimização R8 que causava crash ao tocar em "Ler QR code".
- **Isolamento de ciclo de vida do leitor**: `QrScanner` e `QrScanGate` garantem execução única por vez, desacoplamento de callbacks em Activities destruídas e mensagens de erro amigáveis sem expor dados internos de credenciais.
- **Resiliência de credenciais**: o registro cifrado por AES-GCM com chave Keystore passou a usar `SharedPreferences.commit()`; se uma nova tentativa de pareamento falha, o cliente restaura o pareamento do PC anterior. A revisão 0.2.3 ampliou a proteção contra falhas de commit.
- **Proteção de notificações**: disparo de `POST_NOTIFICATIONS` protegido contra `RuntimeException` de permissões em versões Android heterogêneas.
- **Validação em emulador API 36**: APK de release otimizado instalado e executado; abertura do leitor de QR acionou o scanner do Google Play Services (`BarcodeScanningActivityProxy`) sem crash, com retorno à tela de pareamento ao pressionar Voltar. Isso verifica abertura e cancelamento, não leitura óptica de um convite real.

## Atualização 0.2.1-kotlin — clipboard/foco

- **9 testes JUnit passaram**: 5 de protocolo e 4 regressões do tracker. assembleRelease, assembleSizeBaseline e lintRelease passaram (0 erros, 12 avisos). Assinatura de avaliação preservada; APK universal.
- Reprodução anterior confirmada com bridge do emulador desligada: aplicar A remoto, desabilitar download, copiar B e depois A deixava Windows em B. O novo APK entregou B e A corretamente, com aumento finito da sequência e sem eco.
- Download com aplicação automática alterou o clipboard Android real para a amostra esperada. Upload bidirecional funcionou; sequência Windows estabilizou após cada ação, sem loop.
- EXTRA_IS_SENSITIVE real no Android bloqueou upload e envio manual mostrou erro. Formato Windows ExcludeClipboardContentFromMonitorProcessing preservou o clipboard Android; leitura manual exibiu bloqueio pelo PC.
- Texto de 65.537 bytes foi bloqueado nos dois lados; envio/leitura manual apresentaram limite de 64 KiB. Nenhum conteúdo original entrou em logs.
- Clipboard Windows ocupado por outro processo com HWND próprio retornou erro visível. Nova leitura explícita sem ocupação limpou o erro.
- Pausa bloqueou cópias nas duas direções; retomar manteve clipboard anterior sem replay. Home bloqueou upload e a retomada absorveu baseline; somente cópia nova foi enviada.
- Com o painel desktop fechado, HWND invisível e processo ativo, upload automático continuou atendido pelo backend Rust.
- A bridge nativa do emulador causava um loop independente do protocolo, ao reescrever conteúdo entre host e Android. Ela foi desativada e o isolamento nos dois sentidos comprovado com aplicativos parados; essa configuração faz parte da reprodução. O produto agora também evita reescrita de texto idêntico.
- Ganho de foco/reconexão estabelece baseline, perda de foco desarma entrada e suspende tracking. Metadata do ClipData retornado é revalidada para não transferir texto que mudou para sensível entre leituras.
- Revisão independente aprovou tracker, foco, idempotência, erro sem conteúdo e separação da fixture. DeepSeek não estava disponível; implementação delegada ao modelo herdado, testes a Luna e revisão a Astra.

A [fixture de clipboard](../verification/clipboard-probe/README.md) usa APIs Android reais e é apenas de teste; não integra o APK distribuído. Celular físico, OEM/Doze, câmera QR, Wi-Fi físico e players permanecem pendentes.

## Evidências anteriores — 0.2.0-kotlin

## Automatizado

| Verificação | Resultado |
|---|---|
| Gradle testDebugUnitTest | 5 testes JUnit passaram, zero falhas/erros |
| Contrato | Rejeição de campos extras, coerção de tipos, frames acima do limite, estado aninhado e enum/limites de ações |
| Texto | Unicode válido, surrogate inválido, NUL, limite UTF-8 e caracteres de controle do teclado |
| Clipboard | eventId duplicado, revisão antiga, conflito automático contra revisão autoritativa e reset de dedup na nova conexão |
| Convite QR | Seis campos usados pelo desktop, incluindo pcId; tipos, versão e extras rejeitados |
| Build | assembleRelease com R8 e assembleSizeBaseline sem R8 passaram |
| Lint release | Passou: 0 erros, 12 avisos; sem baseline global |
| Assinatura/estrutura APK | apksigner validou assinatura v2; ZIP sem .so/bundle JS |
| Contrato entre repos | SHA-256 de PROTOCOL.md e exemplos JSON iguais aos do desktop |
| Revisão independente | Reconexão explícita, cleanup de entrada, QR, API24/insets e sinais de rolagem revisados |

Avisos de lint remanescentes incluem target36 deliberado, versões mais novas de bibliotecas, trust manager específico do certificado fixado, configuração de backup, localização de textos, View criada programaticamente e build comparativa sem redução de recursos. Aviso de trust manager não indica validação desativada: há pin DER, validade e verificador normal de hostname; a identidade incorreta foi rejeitada em execução.

## Windows real + emulador Android API36

APK release otimizado instalado em emulator-5554, com o desktop Windows 0.2.0 real. Não houve Metro ou servidor JavaScript.

| Fluxo | Evidência |
|---|---|
| Pareamento manual | Código, fingerprint e aprovação local no PC; Android recebeu welcome/state e mostrou conexão |
| Credencial salva | Fechar processo/reabrir e reinstalar APK com -r manteve autenticação Keystore |
| TLS incorreto | Fingerprint de zeros rejeitado antes da autenticação; reiniciar recuperou credencial correta preservada |
| Revogação | Remover dispositivo no PC encerrou conexão; nova autenticação foi rejeitada e Android exibiu Acesso revogado, removendo a credencial local |
| Volume/mudo | +5 refletiu 76→81%, −5 restaurou 76%; silenciar/ativar som passaram |
| Clipboard manual nas duas direções | Texto conhecido com acentos, emoji, link, ideogramas e quebras enviado ao Windows e relido exatamente no Android |
| Clipboard Windows | Fixture comparou texto de teste em RAM e retornou unicode_matches=true; conteúdo original restaurado |
| Compartilhar | ACTION_SEND text/plain recebido com app encerrado e aberto; preview e envio explícito |
| Cópia Android | Ação Copiar no celular confirmou aplicação do texto recebido |
| Desconectar/reconectar explícito | Mostrou Desconectado e voltou a Conectado com estado atual |
| Mouse | Gesto no TouchPadView moveu cursor real do Windows em 181 pixels horizontais; posição original restaurada |
| Teclado | Texto ASCII conhecido digitado na janela descartável própria; fixture confirmou conteúdo e foco corretos |
| Segundo plano | Home desativou entrada no PC; retomada não reativou automaticamente |
| Mídia sem sessão | Controles desabilitados e explicação de ausência de sessão |

Os testes de movimento e digitação não operaram em aplicativos reais do usuário. Somente evidências booleanas/contagens da amostra conhecida foram registradas; não há logs do clipboard original ou de segredos.

## Pendências reais

- Celular físico ARM64: comportamento OEM/Doze, consumo de bateria/RAM, layout em diferentes tamanhos/fontes e instalação fora das ferramentas de desenvolvimento.
- Câmera/scanner QR real (parser de convite foi automatizado), notificações e permissões reais; Android mínimo API24.
- Wi-Fi físico/mDNS, mudança de interface/rede/IP e suspensão/retomada física do PC.
- Players GSMTC reais: múltiplas sessões, play/pause/faixas e seek; mesma matriz MEDIA-COMPATIBILITY.md do desktop, sem promessa universal.
- Repetir sync automática, flags de sensibilidade e clipboard ocupado em hardware do usuário; os fluxos reais no emulador/Windows foram ampliados na versão 0.2.1.
- Arraste rápido/revisões atrasadas em hardware e troca/desconexão de dispositivo de áudio.
- Entrada Unicode e teclas em diferentes apps/IMEs, clique duplo, roda e bloqueios UIPI/UAC.
- Encerramento físico/suspensão longa, firewall do usuário e inicialização Windows. O backend desktop é o existente, com verificações próprias no repositório desktop; não foi reimplementado nesta branch.
- Assinatura de produção Android e Authenticode Windows: artefatos atuais são de avaliação.

Não há garantia de conexão em segundo plano ou leitura de cópias de outros apps no Android 10+. Abra o app ou use Compartilhar; serviço em primeiro plano não resolveria a restrição. Nada antigo é reenviado automaticamente ao reconectar. Notificação só pode avisar de evento recebido enquanto a conexão ainda existia.

## Reprodução e publicação

Na pasta android: `gradlew.bat testDebugUnitTest assembleRelease assembleSizeBaseline lintRelease`. Em Linux/macOS: `bash gradlew`. Consulte ANDROID.md para assinatura e SIZE.md para tamanhos/hashes.

A main contém apenas o Android Kotlin. As releases e tags do React Native foram excluídas em 16/10/2026; o código antigo existe só no histórico do Git. O repositório desktop e o contrato permanecem independentes. Semantica não está disponível: decisões/evidências ficam nestes arquivos, sem alegar memória externa. O modelo CI está em docs/ci, pois a credencial GitHub não possui escopo workflow.
