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

RemoteClient usa OkHttp 4.12.0 e trust manager exclusivo da conexão: SHA-256 do DER confirmado no PC e certificado válido. Verificador padrão valida SAN pc-remote.local; DNS dedicado aponta ao IPv4 selecionado. Sem alteração global da validação TLS. Token recebido após aprovação fica em AES-GCM com chave Android Keystore; backup desativado.

NsdManager descobre _pcremote._tcp. INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE e CHANGE_WIFI_MULTICAST_STATE atendem LAN/descoberta. Target 36: antes de elevar para 37, implementar ACCESS_LOCAL_NETWORK contextual ou seletor do sistema. Firewall Windows deve permitir servidor somente nas redes desejadas, normalmente privadas; sem configuração de roteador.

QR usa Google Play Services Code Scanner 16.1.0; módulo pode ser baixado externamente. Sem Play Services, o pareamento por código continua disponível. POST_NOTIFICATIONS é solicitado contextual no Android 13+ ao habilitar PC→celular e, uma vez, antes da primeira atualização dentro do app; recusar não impede uso em foco. REQUEST_INSTALL_PACKAGES e UPDATE_PACKAGES_WITHOUT_USER_ACTION servem só para o app instalar as próprias atualizações.

## Atualizações

ReleaseChecker lê `https://api.github.com/repos/danielharrisonlf/pc-remote-android/releases?per_page=30` com OkHttp, sem token. Aceita só tags `vX.Y.Z-kotlin` publicadas e arquivos em `github.com/danielharrisonlf/pc-remote-android/releases/download/`; rascunhos ficam de fora. Compara com o versionName instalado e escolhe o APK que não seja `unminified` e o `KOTLIN-SHA256SUMS-<versão>.txt` da mesma release. Sem conexão ou com limite da API, a aba Conexão mostra o motivo.

Desde a 0.5.0 o UpdateManager baixa o APK para `cacheDir/updates`, confere SHA-256, pacote, versionCode e certificado, e instala por uma sessão do PackageInstaller depois de um toque em Atualizar agora ou Instalar agora. Com **Baixar atualizações automaticamente** (padrão) e rede sem limite, o download e a verificação acontecem antes, com o app aberto. Na primeira vez o Android pede **Instalar apps desconhecidos** para o PC Remote; no Android 12+ as atualizações seguintes, e nos testes já a primeira, instalam sem confirmação do sistema. O receiver de MY_PACKAGE_REPLACED avisa "PC Remote atualizado para X". Detalhes em [ARCHITECTURE.md](ARCHITECTURE.md) e na [auditoria](SECURITY-AUDIT.md).

Teste com servidor próprio, só em build debug: grave a URL da lista de releases com `adb shell "echo http://10.0.2.2:8765/releases.json | run-as com.pcremote.nativeandroid sh -c 'mkdir -p files && cat > files/debug-releases-url.txt'"`. A build debug aceita HTTP apenas para 10.0.2.2; o release ignora o arquivo. Apague-o para voltar ao GitHub.

## Clipboard e ciclo de vida

ClipboardManager observa somente com app em foco. Retomar/habilitar direção estabelece baseline sem enviar conteúdo anterior. ACTION_SEND text/plain funciona em abertura inicial e onNewIntent; ACTION_SEND e ACTION_SEND_MULTIPLE com PNG, JPG ou PDF põem os arquivos na fila de envio, e Activity recriada ou aberta pelos Recentes não repete o compartilhamento. Texto recebido/compartilhado fica em RAM; não existe histórico ou replay. Sync inicia desligada, com direção consentida nos dois apps. Aplicação automática exige preferência e foco real.

Android 10+ não permite ler cópias de outros apps em segundo plano; serviço em primeiro plano não elimina a regra. Não há serviço permanente. Notificação genérica só pode avisar de evento que chegou enquanto conexão existia; suspensão pode interrompê-la. Abra o app ou use Compartilhar.

EXTRA_IS_SENSITIVE impede envio de conteúdo marcado, inclusive leitura manual. Isso não identifica toda senha. Limite 64 KiB UTF-8; Unicode e quebras preservados; NUL/UTF-16 inválido rejeitados. UUID/revisão, ordem autoritativa do PC e supressão de eco impedem loops. O clipboard não leva imagens nem arquivos (PNG, JPG e PDF vão por files.v1, na aba Transferir), não abre links nem cola em aplicativos.

Pause desarma a entrada no PC, que volta sozinha em foco se o usuário não a desligou, e para a descoberta; callbacks de rede/backoff fazem nova autenticação e snapshot. Sem replay ou garantia de execução durante Doze. Reconectar explícito retoma tentativas depois de desconexão manual. Interface com tema claro, escuro ou do sistema, texto em sp, alvos de pelo menos 48dp, scroll e insets de ponta a ponta; Android 13+ usa OnBackInvokedCallback e versões anteriores fallback. Lint não utiliza baseline global para esconder erros. Testes físicos pendentes em TESTING.md.

## Diagnóstico do leitor QR

Se aparecer aviso de módulo indisponível, conecte o celular à internet para que o Google Play Services prepare o leitor e tente novamente; não é necessária conta do PC Remote. O controle do PC continua pela LAN. Se os serviços estiverem desabilitados ou desatualizados, corrija essa condição ou use o código. Cancelar o leitor retorna ao pareamento sem encerrar o aplicativo. A versão 0.2.2 corrigiu construtores do ML Kit removidos pelo R8; preserve as regras específicas de proguard-rules.pro ao gerar novas builds. A 0.2.3 mantém a correção.
