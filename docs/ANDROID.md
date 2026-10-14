# Android 0.1.0: implementação e limites

O app usa React Native Community CLI 0.87.1, React 19.2.3, TypeScript estrito, New Architecture, Hermes e componentes nativos. Expo Go não executa o módulo Kotlin obrigatório. Um development build Expo poderia hospedar um módulo local, mas adicionaria outra camada de prebuild sem resolver TLS pinning, KeyStore, NSD, clipboard e share intents; o Community CLI mantém essas integrações auditáveis no projeto Android. Dependências JS: Zod 4.6.7, Safe Area Context 5.10.1, Slider 5.2.1. O transporte usa OkHttp 4.12.0 e o scanner QR usa Play Services Code Scanner 16.1.0.

## Permissões e pareamento

Internet, acesso/estado Wi-Fi e multicast habilitam LAN e descoberta mDNS. A descoberta oferece somente o endereço do PC. O código temporário de oito dígitos e o fingerprint SHA-256 exibidos na janela Windows precisam ser confirmados; o QR reúne os mesmos campos. O scanner do Google Play Services pode requerer download do módulo de câmera pelo Play Services; use os campos manuais quando indisponível. A notificação é solicitada ao habilitar PC → celular em Android 13+. A ausência dessa permissão impede o aviso em segundo plano, não a transferência enquanto o app está ativo.

O WebSocket usa internamente `wss://pc-remote.local:43127/v1/ws`. Um Dns exclusivo do cliente direciona o nome ao IPv4 selecionado, enquanto o verificador padrão do OkHttp valida o SAN pc-remote.local do certificado. O trust manager dessa conexão aceita somente o certificado cujo SHA-256 DER confere com a identidade confirmada e cuja validade está correta; não modifica validação global. A credencial individual chega após aprovação no PC, fica cifrada com chave AES-256-GCM do Android Keystore em CredentialStore e nunca é passada ao JavaScript. A identidade não é inferida do mDNS. O backup do app está desativado. Uma reconexão autentica de novo e pede snapshot recente; comandos pendentes são descartados. Reconexão do mesmo dispositivo substitui sua sessão anterior sem apagar a credencial válida.

## Área de transferência

Os controles de sincronização começam desligados. Envio automático observa alterações apenas com o app em foco e direção celular → PC habilitada. Ao abrir ou retomar o app, não envia texto preexistente. O botão **Enviar para o PC** lê o texto atual ou o texto recebido via menu Compartilhar; o último tem prioridade até o envio. **Copiar do PC** solicita uma leitura explícita no Windows. Eventos recebidos aparecem no app, com botão de copiar. A aplicação automática no clipboard Android é uma preferência separada e funciona somente em foco. Se um evento chegar enquanto o app ainda mantém conexão em segundo plano, uma notificação genérica abre o app; o texto da cópia não aparece nela. A suspensão do Android pode encerrar essa conexão.

`EXTRA_IS_SENSITIVE` impede envio automático e leitura manual de conteúdo marcado pelo Android. Isso não detecta todo segredo ou senha. O limite é 64 KiB em UTF-8; NUL e texto acima do limite são recusados. A escrita no clipboard Android suprime o eco do próprio listener. Não há histórico persistente nem replay após reconexão. Somente texto simples é transferido. Não se abrem URLs nem se colam textos automaticamente em outro app.

## Build e assinatura

Use Node 22+, JDK 17 e Android SDK com `platforms;android-37.0`, build-tools 37.0.0 e NDK 27.1.12297006. `npm ci`, `npm run typecheck`, `npm test`, depois `cd android && gradlew.bat :app:assembleRelease`. O APK release inclui o bundle JavaScript e funciona sem Metro. Sem credenciais de assinatura de produção, a variante release usa a chave debug local gerada pelo Android SDK e serve apenas para validação. Para distribuir, crie um keystore próprio fora do repositório e forneça `PCREMOTE_KEYSTORE_PATH`, `PCREMOTE_KEY_ALIAS`, `PCREMOTE_STORE_PASSWORD`, `PCREMOTE_KEY_PASSWORD` no ambiente antes da build. Preserve a chave para atualizações. Nunca versionar keystore ou senhas. O identificador do pacote é `com.pcremote`.

Em Windows, Ninja pode exceder o limite de 260 caracteres no caminho de arquivos C++ gerados. Nesse caso, execute `subst R: "C:\caminho\para\pc-remote-android"`, abra `R:\android` e rode a build dali. Após a build, use `subst R: /D`. O Metro usa o caminho real e o caminho curto para resolver as dependências. Escolha uma letra de unidade livre.

## Verificação em dispositivo real

Confirmar em um Android com Google Play Services e PC Windows na mesma rede: QR e código manual; aprovação/negação/revogação; volume/mudo com alterações externas no PC; sessões de mídia e capacidades; Compartilhar texto com app aberto e fechado; Unicode e quebras; proteção de conteúdo sensível; alternância foco/segundo plano; suspensão/rede; build release instalada sem Metro. Testes JS cobrem validação de mensagens e limites de texto, mas não substituem essas verificações.
