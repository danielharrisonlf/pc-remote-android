# Validação — branch Kotlin native/kotlin

Registro da alternativa 0.2.0-kotlin em 14/10/2026. Os resultados React Native da branch main não são apresentados como testes desta implementação. Integrações reais: Android SDK/Keystore/OkHttp e Windows Rust/Core Audio/GSMTC/clipboard/SendInput. Não há simulações no produto.

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

main conserva React Native; native/kotlin contém apenas Android Kotlin. O repositório desktop e o contrato permanecem independentes. Semantica não está disponível: decisões/evidências ficam nestes arquivos, sem alegar memória externa. O modelo CI está em docs/ci, pois a credencial GitHub não possui escopo workflow.
