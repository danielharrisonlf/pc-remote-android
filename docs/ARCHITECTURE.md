# Arquitetura — Android Kotlin

Android e Windows são repositórios independentes. Protocolo 1 com as extensões negociadas input.v1 e files.v1 é o único contrato com o desktop Rust/Tauri; com desktop 0.2.x e 0.3.0 vale só input.v1.

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
| InputIntent.kt | Intenção de manter o touchpad ligado na conexão: pausa e rearme em foco |
| FileTransfer.kt | Envio files.v1: oferta, ticket, socket `/v1/files`, janela, SHA-256, prazos e fila |
| FileRules.kt | Regras puras de nome, mime, extensão, assinatura, tamanho, mensagens e fila |
| VolumeInteraction.kt | Rajadas de volume com valor final confirmado e passos das teclas físicas |
| ClipboardTracker.kt | Baseline em foco, supressão de eco e recópias sem histórico |
| CredentialStore.kt / CommittedValue.kt | Android Keystore/AES-GCM; falhas de gravação não expõem credencial candidata |
| QrScanner.kt / QrScanGate.kt | Scanner pertencente à Activity, uma leitura por vez e descarte após destruição |
| BackNavigation.kt | APIs exclusivas do Android 13 isoladas por verificação de versão |
| Protocol.kt | Validação estrita, limites, dedup/revisões e convite QR |
| InputMotion.kt | Acumulação fracionária, coalescência e um movimento em voo |

Activity mostra estado do cliente sem manter conexão numa tela. Gerações invalidam callbacks/comandos após troca de sessão. Sem pendências persistidas ou replay. Slider limita envio a 10Hz e aguarda ACK/estado final. As teclas físicas de volume, nas abas Touchpad e Mídia com o PC conectado e áudio disponível, passam pelo mesmo VolumeInteraction: cada toque, inclusive repetição, vai ao próximo múltiplo de 5 a partir do valor pendente, e a rajada termina com o valor final confirmado. Fora dessas condições, ou com um painel aberto, o Android cuida do volume do celular.

Entrada exige permissão PC e ativação confirmada. Trocar de aba não desarma. Perder o foco da janela ou ir para segundo plano desarma o PC na hora, mas InputIntent guarda a intenção e o RemoteClient rearma ao voltar o foco, depois que a desativação pendente termina. Por isso o painel de digitação fica dentro da janela principal; só folhas usadas fora do controle (pareamento, atualização) são Dialogs.

Arquivos: MainActivity entrega ao RemoteClient as URIs do seletor (`ACTION_OPEN_DOCUMENT`) ou do Compartilhar. FileTransfer confere cada documento numa thread própria (nome, tamanho, tipo e primeiros bytes) e o põe na fila. Com `file.transfer` no welcome e `files.allowed`, oferece um arquivo por vez no socket de controle, recebe o ticket antes do ack e abre `/v1/files` com o mesmo OkHttpClient da conexão: mesmo DNS e trust manager fixado. Uma thread lê o `ContentResolver`, calcula o SHA-256 e envia blocos dentro da janela do PC; o estado fica na thread principal. Cada callback confere o job ativo; queda da conexão, Remover este PC, pareamento novo, Cancelar ou 20 s sem progresso encerram o job, fecham o socket e, quando o controle existe, mandam `file.cancel`. A próxima oferta espera esse ack, para o PC estar livre.

Toda execução Windows permanece em Rust: Core Audio, GSMTC, SendInput e listener nativo do clipboard. Fechar janela desktop mantém serviço na bandeja. Identidade Windows é protegida por DPAPI; revogação remove token e fecha sessão. Descoberta apenas informa endereço; certificado fixado e hostname validado autenticam o transporte.

Clipboard sem histórico, duas direções opt-in e pausa. Android observa/aplica somente em foco; background depende da conexão sobrevivente. UUIDs e revisões ordenadas pelo PC, com supressão de eco, evitam loops. Conflito automático rejeita revisão antiga; ação manual substitui explicitamente. Nenhuma cópia anterior é transferida ao conectar/retomar.

Novas ações requerem tipo, validação, capacidade, autorização e integração Rust própria. Sem endpoint shell. Outros tipos de arquivo exigiriam mudar o contrato files.v1. Touchpad/teclado explícitos operam na janela em foco; não são fallback universal de mídia. Veja PROTOCOL.md, INPUT.md e MEDIA-COMPATIBILITY.md.

Pareamento mantém a credencial candidata apenas em RAM até receber Welcome válido. Só após gravar a credencial cifrada com sucesso o cliente marca a conexão autenticada. Falha ou cancelamento preserva o PC anterior. Falhas de commit que também alterem o cache do SharedPreferences não permitem recuperar a credencial candidata. Callbacks do socket encerrado são invalidados antes da reconexão; a perda de uma rede antiga não derruba a nova conexão.
