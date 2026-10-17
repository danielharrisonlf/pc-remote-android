# Android Kotlin nativo

Views nativas, Kotlin integrado ao AGP e cliente global na Application. Sem Compose, React Native, WebView, Node ou NDK. Pacote `com.pcremote.nativeandroid`, com credenciais e preferências próprias.

## Build e assinatura

JDK 17; SDK com platform-tools, platforms;android-37.0 e build-tools;37.0.0. Configure ANDROID_HOME/ANDROID_SDK_ROOT ou android/local.properties (não versionar). Na pasta android:

```powershell
.\gradlew.bat testDebugUnitTest assembleRelease lintRelease
.\gradlew.bat assembleSizeBaseline
```

Linux/macOS: `bash gradlew`. Android Studio pode abrir a pasta android. Release funciona sem servidor de desenvolvimento. Saídas em app/build/outputs/apk/release e app/build/outputs/apk/sizeBaseline. sizeBaseline desliga R8/redução de recursos para comparação. APK universal, sem bibliotecas JNI por ABI. JUnit usa debug: testReleaseUnitTest não é criada nesta configuração do AGP.

Sem credenciais de produção, release usa chave debug do SDK, somente avaliação. Gere um keystore próprio fora do Git:

```powershell
keytool -genkeypair -v -keystore C:\seguro\pc-remote.jks -alias pcremote -keyalg RSA -keysize 3072 -validity 10000
```

Forneça todas as variáveis: PCREMOTE_KEYSTORE_PATH, PCREMOTE_KEY_ALIAS, PCREMOTE_STORE_PASSWORD, PCREMOTE_KEY_PASSWORD, em ambiente seguro. Não versionar chaves/senhas. Preserve a chave para atualizar instalações. Outra assinatura exige desinstalar esta avaliação e parear novamente. Verifique com `apksigner verify --verbose app-release.apk`.

## Integrações e permissões

RemoteClient usa OkHttp 4.12.0 e trust manager exclusivo da conexão: SHA-256 do DER confirmado no PC e certificado válido. Verificador padrão valida SAN pc-remote.local; DNS dedicado aponta ao IPv4 selecionado. Sem alteração global da validação TLS. Token recebido após aprovação fica em AES-GCM com chave Android Keystore; backup desativado. Desde a 0.6.1 a mesma credencial cifrada guarda até 8 PCs, cada um com seu token.

NsdManager descobre _pcremote._tcp. INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE e CHANGE_WIFI_MULTICAST_STATE atendem LAN/descoberta. Target 36: antes de elevar para 37, implementar ACCESS_LOCAL_NETWORK contextual ou seletor do sistema. Firewall Windows deve permitir servidor somente nas redes desejadas, normalmente privadas; sem configuração de roteador.

QR usa Google Play Services Code Scanner 16.1.0; módulo pode ser baixado externamente. Sem Play Services, o pareamento por código continua disponível. POST_NOTIFICATIONS é solicitado uma vez no Android 13+ quando o primeiro arquivo começa a se mover e uma vez antes da primeira atualização dentro do app; recusar não impede o uso. RECORD_AUDIO é pedido no primeiro toque no microfone; depois de uma recusa definitiva, o app oferece Abrir ajustes. FOREGROUND_SERVICE e FOREGROUND_SERVICE_DATA_SYNC servem só ao TransferService durante transferências; FOREGROUND_SERVICE_MEDIA_PLAYBACK, ao MediaControlService enquanto os controles de mídia aparecem fora do app. O `<queries>` de `android.speech.RecognitionService` deixa o SpeechRecognizer encontrar o reconhecedor do sistema no Android 11+. VIBRATE saiu na 0.6: o app não vibra. REQUEST_INSTALL_PACKAGES e UPDATE_PACKAGES_WITHOUT_USER_ACTION servem só para o app instalar as próprias atualizações.

## Voz

O botão de microfone da aba Mídia abre um painel na própria janela ("Ouvindo…", texto parcial e nível do microfone) e usa `SpeechRecognizer` do sistema em pt-BR, com resultados parciais e uma fala por vez; o microfone fecha no fim da fala, em Cancelar ou ao sair do app. Sem reconhecedor no aparelho, o app diz que a voz está indisponível e a pesquisa continua por texto. Comandos (pausar, continuar, próxima, anterior, voltar/avançar 10 segundos, aumentar/diminuir volume, mudo) agem na sessão selecionada; o resto é pesquisa no YouTube do PC (search.v1), deixada no campo para correção. O reconhecimento pode depender da internet, conforme o serviço de voz do aparelho.

## Vários PCs, energia e mídia fora do app

Conexão → **Adicionar PC** pareia outro PC sem apagar os anteriores (até 8; com 8, o app pede para remover um). Os demais aparecem em **Outros PCs**, com Conectar e lixeira; com mais de um, o nome na barra superior abre **Trocar de PC**. O celular conecta a um PC por vez, e o widget, o bloco e a notificação agem sempre no PC atual.

**Energia do PC**, abaixo do PC atual, bloqueia, suspende, desliga ou reinicia o PC (power.v1, PC 0.6.1). Suspender e Desligar oferecem Agora, 15 min, 30 min, 1 hora e 2 horas; Reiniciar pede confirmação. Um agendamento aparece com a hora e o tempo restante e pode ser cancelado. Com Interromper ativo no PC os botões ficam desabilitados; com PC mais antigo o cartão pede para atualizar o Windows.

Com o PC tocando e o app aberto, a notificação de mídia aparece e fica também na tela de bloqueio e nos controles de mídia do sistema, com Anterior, Tocar/Pausar, Próxima e Fechar. Ela some com Fechar, depois de 30 s sem sessão de mídia no PC, depois de 15 min pausada com o app fechado ou ao desconectar. O widget **Mídia do PC** (tela inicial) e o bloco **Mídia do PC** (Configurações rápidas) conectam na hora do toque, se preciso, e alternam ou trocam de faixa. O seletor de saída dos controles de mídia do Android mostra "Este telefone", porque a sessão é local; as teclas de volume continuam controlando o celular fora das abas Touchpad e Mídia.

## Atualizações

ReleaseChecker lê `https://api.github.com/repos/danielharrisonlf/pc-remote-android/releases?per_page=30` com OkHttp, sem token. Aceita só tags `vX.Y.Z-kotlin` publicadas e arquivos em `github.com/danielharrisonlf/pc-remote-android/releases/download/`; rascunhos ficam de fora. Compara com o versionName instalado e escolhe o APK que não seja `unminified` e o `KOTLIN-SHA256SUMS-<versão>.txt` da mesma release. Sem conexão ou com limite da API, a aba Conexão mostra o motivo.

Desde a 0.5.0 o UpdateManager baixa o APK para `cacheDir/updates`, confere SHA-256, pacote, versionCode e certificado, e instala por uma sessão do PackageInstaller depois de um toque em Atualizar agora ou Instalar agora. Com **Baixar atualizações automaticamente** (padrão) e rede sem limite, o download e a verificação acontecem antes, com o app aberto. Na primeira vez o Android pede **Instalar apps desconhecidos** para o PC Remote; no Android 12+ as atualizações seguintes, e nos testes já a primeira, instalam sem confirmação do sistema. O receiver de MY_PACKAGE_REPLACED avisa "PC Remote atualizado para X". Detalhes em [ARCHITECTURE.md](ARCHITECTURE.md) e na [auditoria](SECURITY-AUDIT.md).

Teste com servidor próprio, só em build debug: grave a URL da lista de releases com `adb shell "echo http://10.0.2.2:8765/releases.json | run-as com.pcremote.nativeandroid sh -c 'mkdir -p files && cat > files/debug-releases-url.txt'"`. A build debug aceita HTTP apenas para 10.0.2.2; o release ignora o arquivo. Apague-o para voltar ao GitHub.

## Clipboard e ciclo de vida

ClipboardManager observa somente com app em foco. **Sincronização em tempo real** (Conexão → Preferências) é a única opção e começa ligada; as preferências antigas por sentido são ignoradas. Com ela, o texto copiado no PC vai direto para a área de transferência do celular, também em segundo plano enquanto a conexão existir; com o app em foco, cada cópia vai para o PC; ao voltar para o app, o que foi copiado em outro app é enviado uma vez (na primeira abertura, nada). ACTION_SEND text/plain funciona em abertura inicial e onNewIntent; ACTION_SEND e ACTION_SEND_MULTIPLE de qualquer arquivo põem os arquivos na fila de envio, e Activity recriada ou aberta pelos Recentes não repete o compartilhamento. Texto recebido/compartilhado fica em RAM; não existe histórico ou replay.

Android 10+ não permite ler cópias de outros apps em segundo plano; serviço em primeiro plano não elimina a regra, por isso o envio acontece ao voltar para o app. Não há serviço permanente: o TransferService existe só durante transferências de arquivo, e o MediaControlService só enquanto os controles de mídia aparecem fora do app. Se escrever na área de transferência falhar em segundo plano, uma notificação genérica sem conteúdo avisa; suspensão pode interromper a conexão. Abra o app ou use Compartilhar.

Arquivos (files.v2, PC 0.6): qualquer arquivo de até 1 GB nos dois sentidos, exceto atalhos e configurações do Explorador (`.lnk`, `.url`, `.scf`, `.library-ms`, `.searchconnector-ms`, `.settingcontent-ms`, `.appref-ms`, `.website`, `desktop.ini`). Recebidos do PC ficam em `Download/PC Remote` (Android 10+) ou em `Android/data/com.pcremote.nativeandroid/files/Download/PC Remote` (Android 7 a 9). Com PC 0.4/0.5, só PNG, JPG ou PDF de até 50 MB vão ao PC e nada vem dele.

EXTRA_IS_SENSITIVE impede envio de conteúdo marcado, inclusive leitura manual. Isso não identifica toda senha. Limite 64 KiB UTF-8; Unicode e quebras preservados; NUL/UTF-16 inválido rejeitados. UUID/revisão, ordem autoritativa do PC e supressão de eco impedem loops. Com PC 0.6.1 (clipboard.v2), imagens PNG e JPEG de até 32 MB vão e vêm pela área de transferência com as mesmas regras do texto; outros formatos de imagem são convertidos em PNG antes de ir ao PC. Arquivos continuam na aba Transferir (files.v1/files.v2). O clipboard não abre links nem cola em aplicativos.

Pause desarma a entrada no PC, que volta sozinha em foco se o usuário não a desligou, e para a descoberta; callbacks de rede/backoff fazem nova autenticação e snapshot. Sem replay ou garantia de execução durante Doze. Reconectar explícito retoma tentativas depois de desconexão manual. Interface com tema claro, escuro ou do sistema, texto em sp, alvos de pelo menos 48dp, scroll e insets de ponta a ponta; Android 13+ usa OnBackInvokedCallback e versões anteriores fallback. Lint não utiliza baseline global para esconder erros. Testes físicos pendentes em TESTING.md.

## Diagnóstico do leitor QR

Se aparecer aviso de módulo indisponível, conecte o celular à internet para que o Google Play Services prepare o leitor e tente novamente; não é necessária conta do PC Remote. O controle do PC continua pela LAN. Se os serviços estiverem desabilitados ou desatualizados, corrija essa condição ou use o código. Cancelar o leitor retorna ao pareamento sem encerrar o aplicativo. A versão 0.2.2 corrigiu construtores do ML Kit removidos pelo R8; preserve as regras específicas de proguard-rules.pro ao gerar novas builds. A 0.2.3 mantém a correção.
