# Arquitetura — alternativa Kotlin 0.2.3

Android e Windows são repositórios independentes. A branch native/kotlin é alternativa dentro do Android; não altera main nem cria monorepo. Protocolo 1 + input.v1 é o único contrato, com desktop Rust/Tauri 0.2.1 existente.

| Arquivo | Responsabilidade |
|---|---|
| RemoteApplication.kt | Singleton fora do ciclo de vida das telas |
| Models.kt | DTOs, estado, ações tipadas e RemoteController |
| MainActivity.kt | Views de controle, pareamento/configurações, acessibilidade e ciclo de vida |
| TouchPadView.kt | Gestos nativos com cancelamento |
| RemoteClient.kt | WSS/pinning, autenticação, reconexão, ACK/timeouts, NSD, clipboard, share e avisos |
| ClipboardTracker.kt | Baseline em foco, supressão de eco e recópias sem histórico |
| CredentialStore.kt / CommittedValue.kt | Android Keystore/AES-GCM; falhas de gravação não expõem credencial candidata |
| QrScanner.kt / QrScanGate.kt | Scanner pertencente à Activity, uma leitura por vez e descarte após destruição |
| BackNavigation.kt | APIs exclusivas do Android 13 isoladas por verificação de versão |
| Protocol.kt | Validação estrita, limites, dedup/revisões e convite QR |
| InputMotion.kt | Acumulação fracionária, coalescência e um movimento em voo |

Activity mostra estado do cliente sem manter conexão numa tela. Gerações invalidam callbacks/comandos após troca de sessão. Sem pendências persistidas ou replay. Slider limita envio a 10Hz e aguarda ACK/estado final. Entrada exige permissão PC e ativação confirmada; perder foco desarma.

Toda execução Windows permanece em Rust: Core Audio, GSMTC, SendInput e listener nativo do clipboard. Fechar janela desktop mantém serviço na bandeja. Identidade Windows é protegida por DPAPI; revogação remove token e fecha sessão. Descoberta apenas informa endereço; certificado fixado e hostname validado autenticam o transporte.

Clipboard sem histórico, duas direções opt-in e pausa. Android observa/aplica somente em foco; background depende da conexão sobrevivente. UUIDs e revisões ordenadas pelo PC, com supressão de eco, evitam loops. Conflito automático rejeita revisão antiga; ação manual substitui explicitamente. Nenhuma cópia anterior é transferida ao conectar/retomar.

Novas ações requerem tipo, validação, capacidade, autorização e integração Rust própria. Sem endpoint shell. Arquivos/imagens exigem novo contrato/limites. Touchpad/teclado explícitos operam na janela em foco; não são fallback universal de mídia. Veja PROTOCOL.md, INPUT.md e MEDIA-COMPATIBILITY.md.

Pareamento mantém a credencial candidata apenas em RAM até receber Welcome válido. Só após gravar a credencial cifrada com sucesso o cliente marca a conexão autenticada. Falha ou cancelamento preserva o PC anterior. Falhas de commit que também alterem o cache do SharedPreferences não permitem recuperar a credencial candidata. Callbacks do socket encerrado são invalidados antes da reconexão; a perda de uma rede antiga não derruba a nova conexão.
