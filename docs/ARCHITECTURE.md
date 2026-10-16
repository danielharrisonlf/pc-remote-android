# Arquitetura — Android Kotlin

Android e Windows são repositórios independentes. Protocolo 1 + input.v1 é o único contrato com o desktop Rust/Tauri 0.2.x e 0.3.0.

| Arquivo | Responsabilidade |
|---|---|
| RemoteApplication.kt | Singleton fora do ciclo de vida das telas |
| Models.kt | DTOs, estado, ações tipadas e RemoteController |
| MainActivity.kt | Casca: barra superior, abas, aviso de atualização, insets, tema e ciclo de vida |
| Theme.kt / UiKit.kt | Paleta clara/escura com os papéis de cor do desktop e fábrica de Views |
| Shell.kt | Abas, barra de navegação, snackbar, folhas em Dialog e painéis na própria janela |
| TouchpadScreen.kt / MediaScreen.kt / ClipboardScreen.kt / ConnectionScreen.kt | Uma tela por aba |
| TouchPadView.kt | Gestos nativos com aceleração, rolagem com dois dedos e faixa de rolagem |
| ScrollMotion.kt | Rolagem em lotes, um pedido em voo |
| Releases.kt / ReleaseNotes.kt | Consulta ao GitHub Releases e notas em Markdown simples |
| Fingerprint.kt | Faixa de cores e blocos da identidade, iguais aos do desktop |
| RemoteClient.kt | WSS/pinning, autenticação, reconexão, ACK/timeouts, NSD, clipboard, share e avisos |
| ClipboardTracker.kt | Baseline em foco, supressão de eco e recópias sem histórico |
| CredentialStore.kt / CommittedValue.kt | Android Keystore/AES-GCM; falhas de gravação não expõem credencial candidata |
| QrScanner.kt / QrScanGate.kt | Scanner pertencente à Activity, uma leitura por vez e descarte após destruição |
| BackNavigation.kt | APIs exclusivas do Android 13 isoladas por verificação de versão |
| Protocol.kt | Validação estrita, limites, dedup/revisões e convite QR |
| InputMotion.kt | Acumulação fracionária, coalescência e um movimento em voo |

Activity mostra estado do cliente sem manter conexão numa tela. Gerações invalidam callbacks/comandos após troca de sessão. Sem pendências persistidas ou replay. Slider limita envio a 10Hz e aguarda ACK/estado final. Entrada exige permissão PC e ativação confirmada; perder o foco da janela desarma. Por isso o painel de digitação fica dentro da janela principal; só folhas usadas fora do controle (pareamento, atualização) são Dialogs.

Toda execução Windows permanece em Rust: Core Audio, GSMTC, SendInput e listener nativo do clipboard. Fechar janela desktop mantém serviço na bandeja. Identidade Windows é protegida por DPAPI; revogação remove token e fecha sessão. Descoberta apenas informa endereço; certificado fixado e hostname validado autenticam o transporte.

Clipboard sem histórico, duas direções opt-in e pausa. Android observa/aplica somente em foco; background depende da conexão sobrevivente. UUIDs e revisões ordenadas pelo PC, com supressão de eco, evitam loops. Conflito automático rejeita revisão antiga; ação manual substitui explicitamente. Nenhuma cópia anterior é transferida ao conectar/retomar.

Novas ações requerem tipo, validação, capacidade, autorização e integração Rust própria. Sem endpoint shell. Arquivos/imagens exigem novo contrato/limites. Touchpad/teclado explícitos operam na janela em foco; não são fallback universal de mídia. Veja PROTOCOL.md, INPUT.md e MEDIA-COMPATIBILITY.md.

Pareamento mantém a credencial candidata apenas em RAM até receber Welcome válido. Só após gravar a credencial cifrada com sucesso o cliente marca a conexão autenticada. Falha ou cancelamento preserva o PC anterior. Falhas de commit que também alterem o cache do SharedPreferences não permitem recuperar a credencial candidata. Callbacks do socket encerrado são invalidados antes da reconexão; a perda de uma rede antiga não derruba a nova conexão.
