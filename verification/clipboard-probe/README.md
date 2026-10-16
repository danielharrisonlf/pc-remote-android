# Validação real do clipboard Android

Fixture somente para testes, fora do módulo `android/app` e do APK do produto. Usa ClipboardManager do Android, sem mocks ou transporte. Não fornece funcionalidade de usuário e não é publicada junto com os instaladores.

Use um emulador descartável. Em **Extended Controls → Settings → General**, desligue **Enable clipboard sharing** antes de validar o protocolo; esse recurso cria um caminho adicional entre Android e host. Com os apps parados, confirme que escrever uma amostra Android não altera o clipboard Windows e vice-versa. Restaure a preferência ao terminar, com o serviço parado e antes de restaurar o clipboard original do host.

## Build e instalação da fixture

Na raiz da branch native/kotlin, com JDK17 e SDK configurado:

```powershell
.\android\gradlew.bat -p verification/clipboard-probe assembleDebug
adb -s emulator-5554 install -r verification/clipboard-probe/build/outputs/apk/debug/PCRemoteClipboardProbe-debug.apk
```

A fixture usa applicationId `com.pcremote.validation`. Não possui acesso à rede, armazenamento, credenciais ou permissões adicionais. O receiver exportado atende somente modos fixos; instale apenas no emulador de validação. Para remover: `adb -s emulator-5554 uninstall com.pcremote.validation`.

## Estímulos reais

Mantenha PC Remote Kotlin conectado e com foco antes de mudar o clipboard. Aguarde o foco: iniciar a Activity e escrever imediatamente pode fazer a amostra virar o baseline de retomada, corretamente sem upload.

```powershell
adb -s emulator-5554 shell am broadcast -n com.pcremote.validation/.ClipboardProbe --es mode unicode
adb -s emulator-5554 shell am broadcast -n com.pcremote.validation/.ClipboardProbe --es mode second
adb -s emulator-5554 shell am broadcast -n com.pcremote.validation/.ClipboardProbe --es mode sensitive
adb -s emulator-5554 shell am broadcast -n com.pcremote.validation/.ClipboardProbe --es mode too_large
adb -s emulator-5554 shell am broadcast -n com.pcremote.validation/.ClipboardProbe --es mode baseline
```

`unicode` contém acentos, emoji, link, ideogramas e quebras de linha; `second` é texto distinto; `sensitive` usa o flag de sensibilidade na ClipDescription; `too_large` tem 65.537 bytes ASCII. Somente resultado escrito/erro é retornado, sem conteúdo. A Activity de inspeção compara o clipboard atual com amostras conhecidas e mostra apenas booleans, nunca o texto:

```powershell
adb -s emulator-5554 shell am start -n com.pcremote.validation/.ClipboardInspection
```

A inspeção espera ganhar foco e não lê o conteúdo marcado sensível. Ao abrir essa Activity, o app principal perde foco; não é teste de leitura em segundo plano pelo produto.

## Cenários

1. Aplicar A do PC, copiar B localmente e depois A de novo: ambas as cópias locais devem chegar ao Windows. Para isolar a regressão, desligue download após aplicar A, mantendo upload habilitado.
2. Ativar as duas direções e aplicação automática: conferir texto e estabilidade de GetClipboardSequenceNumber entre novas cópias; aplicar remotamente o texto já existente não deve reescrever o Android.
3. Marcar conteúdo sensível no Android: não sobe automaticamente, e envio manual informa o bloqueio. Repetir no Windows com ExcludeClipboardContentFromMonitorProcessing; Android não recebe o texto e leitura manual é recusada.
4. Usar 65.537 bytes em ambos os sistemas: recusar e informar limite de 64 KiB. Não registrar conteúdo em logs.
5. Pausar, copiar, retomar: conteúdo anterior não é enviado. Colocar Android em Home, copiar e voltar: estabelecer baseline sem envio antigo.
6. Fechar painel desktop: backend continua atendendo. Para simular clipboard Windows ocupado, um processo auxiliar deve abrir clipboard com HWND próprio válido; verificar erro e nova leitura explícita após liberá-lo.

Preserve o clipboard original do Windows em RAM e restaure-o somente depois de desativar direções/encerrar o serviço de teste e a integração de clipboard do emulador. Não salvar o conteúdo original em arquivos. Registre apenas igualdade de amostras, revisões e contagens. Veja [TESTING.md](../../docs/TESTING.md) para resultados efetivamente executados e pendências de hardware.
