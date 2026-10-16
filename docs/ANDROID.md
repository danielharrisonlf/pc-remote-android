# Android Kotlin nativo 0.2.3

Branch `native/kotlin`; `main` conserva React Native. Views nativas, Kotlin integrado ao AGP e cliente global na Application. Sem Compose, React Native, WebView, Node ou NDK. Pacote `com.pcremote.nativeandroid` permite coexistência, com credenciais/preferências independentes.

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

QR usa Google Play Services Code Scanner 16.1.0; módulo pode ser baixado externamente. Sem Play Services, pareamento manual continua disponível. POST_NOTIFICATIONS é solicitado contextual no Android 13+ ao habilitar PC→celular; recusar não impede uso em foco.

## Clipboard e ciclo de vida

ClipboardManager observa somente com app em foco. Retomar/habilitar direção estabelece baseline sem enviar conteúdo anterior. ACTION_SEND text/plain funciona em abertura inicial e onNewIntent. Texto recebido/compartilhado fica em RAM; não existe histórico ou replay. Sync inicia desligada, com direção consentida nos dois apps. Aplicação automática exige preferência e foco real.

Android 10+ não permite ler cópias de outros apps em segundo plano; serviço em primeiro plano não elimina a regra. Não há serviço permanente. Notificação genérica só pode avisar de evento que chegou enquanto conexão existia; suspensão pode interrompê-la. Abra o app ou use Compartilhar.

EXTRA_IS_SENSITIVE impede envio de conteúdo marcado, inclusive leitura manual. Isso não identifica toda senha. Limite 64 KiB UTF-8; Unicode e quebras preservados; NUL/UTF-16 inválido rejeitados. UUID/revisão, ordem autoritativa do PC e supressão de eco impedem loops. Sem imagens/arquivos, abertura de links ou colagem em aplicativos.

Pause cancela entrada e descoberta; callbacks de rede/backoff fazem nova autenticação e snapshot. Sem replay ou garantia de execução durante Doze. Reconectar explícito retoma tentativas depois de desconexão manual. Interface tem tema do sistema, texto em sp, alvos de 52dp, scroll e insets; Android 13+ usa OnBackInvokedCallback e versões anteriores fallback. Lint não utiliza baseline global para esconder erros. Testes físicos pendentes em TESTING.md.

## Diagnóstico do leitor QR

Se aparecer aviso de módulo indisponível, conecte o celular à internet para que o Google Play Services prepare o leitor e tente novamente; não é necessária conta do PC Remote. O controle do PC continua pela LAN. Se os serviços estiverem desabilitados ou desatualizados, corrija essa condição ou use os campos manuais. Cancelar o leitor retorna ao pareamento sem encerrar o aplicativo. A versão 0.2.2 corrigiu construtores do ML Kit removidos pelo R8; preserve as regras específicas de proguard-rules.pro ao gerar novas builds. A 0.2.3 mantém a correção.
