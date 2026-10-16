# Revisão de dependências — 15/10/2026

Aplicativos: Windows 0.2.2, Android React Native 0.2.1 e Kotlin 0.2.4-kotlin. Lockfiles e Gradle registram as versões exatas. Esta revisão combina auditoria de dependências com inspeção do código; não equivale a uma certificação de ausência de vulnerabilidades.

## Correção aplicada

Rustls foi atualizado de 0.23.45 para **0.23.46**, com alteração restrita no Cargo.lock. As quatro notificações publicadas em 14/10/2026 têm correção nessa versão: [RUSTSEC-2026-0346](https://rustsec.org/advisories/RUSTSEC-2026-0346.html), [0347](https://rustsec.org/advisories/RUSTSEC-2026-0347.html), [0348](https://rustsec.org/advisories/RUSTSEC-2026-0348.html) e [0349](https://rustsec.org/advisories/RUSTSEC-2026-0349.html).

Os casos envolvem depuração de sessões TLS de cliente, ECH, descompressão Brotli opcional e cache de cliente pós-quântico. O produto usa servidor TLS sem autenticação de certificado de cliente; o ClientConfig é de teste e a funcionalidade Brotli não está habilitada. Mesmo assim, o componente foi corrigido. Consulta oficial OSV dirigida a rustls 0.23.46 retornou zero avisos na data da revisão.

## Atualização dentro do app — Android 0.5.0, 16/10/2026

Cadeia de verificação antes de qualquer instalação:

1. **Origem fixa por TLS.** A lista vem de `api.github.com/repos/danielharrisonlf/pc-remote-android/releases`. O APK e o `KOTLIN-SHA256SUMS-<versão>.txt` só são aceitos sob `https://github.com/danielharrisonlf/pc-remote-android/releases/download/`, sem query, escapes ou `..`. Cada redirecionamento é conferido antes de conectar: só HTTPS para github.com, objects.githubusercontent.com ou release-assets.githubusercontent.com. Limites de 50 MB e 64 KiB.
2. **SHA-256.** O APK precisa ter o hash da sua linha no SHA256SUMS da mesma release; nome ausente ou listado com dois hashes é recusado. Isso protege contra arquivo corrompido ou trocado no caminho, não contra uma release maliciosa publicada com a conta do GitHub, porque os dois arquivos vêm da mesma origem.
3. **Mesma assinatura, aplicada duas vezes.** O app exige pacote igual, versionCode maior e os mesmos certificados do app instalado (`getPackageArchiveInfo`), e repete a conferência antes de abrir a sessão. O Android recusa de novo qualquer atualização com outro certificado. É essa etapa que protege contra uma release maliciosa, enquanto a chave de assinatura estiver segura.

Instalação só pelo PackageInstaller, com `setAppPackageName` do próprio app e sempre depois de um toque do usuário; o download automático para em "pronta". Os dois receivers não são exportados; o PendingIntent da sessão é explícito; a confirmação do sistema é aberta sem repassar permissões de URI. REQUEST_INSTALL_PACKAGES e UPDATE_PACKAGES_WITHOUT_USER_ACTION servem só a essa sessão. O gancho de teste (`files/debug-releases-url.txt` e HTTP para 10.0.2.2) existe apenas em build debuggable: o manifesto mesclado do release não tem `networkSecurityConfig` nem `debuggable`, e `run-as` não funciona no release.

Testado no emulador contra um servidor falso: SHA-256 divergente, APK com outra assinatura, versionCode igual e redirecionamento para outro host foram recusados, sem instalar e com o arquivo apagado. Riscos residuais: os APKs continuam assinados com a chave debug de avaliação, então quem obtiver essa chave e publicar na conta do GitHub consegue entregar uma atualização; a conta do GitHub e o keystore são as raízes de confiança. Assinatura de produção continua pendente.

## Resultados e pendências

| Consulta | Resultado | Tratamento |
|---|---|---|
| npm audit desktop | Zero vulnerabilidades informadas | Lockfile preservado |
| npm audit React Native | 53 nós afetados: 48 high e 5 moderate, propagados por dois avisos | Não são 53 vulnerabilidades distintas; ver abaixo |
| OSV do Cargo.lock | Consulta de 518 crates públicas por nome/versão | Rustls corrigido; avisos residuais abaixo |
| Kotlin nativo | Sem npm; versões diretas fixas no Gradle | Não foi executado um scanner completo de todas as dependências Maven |

No React Native, **braces 3.0.3** ([GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm)) e **sprintf-js 1.0.3** ([GHSA-hp3w-g68c-fv3c](https://github.com/advisories/GHSA-hp3w-g68c-fv3c)) continuam afetados. Os avisos e o registro npm não oferecem versão publicada corrigida na data desta revisão. Atualizar sprintf-js para 1.1.3 não resolve o segundo aviso. Os caminhos identificados pertencem às ferramentas Jest/Metro/cobertura; não foi demonstrado um caminho de exploração por mensagens do aplicativo. Não se afirma que o risco seja nulo. Evite entradas de build/teste de origem não confiável e acompanhe as correções upstream. Não foi aplicado `npm audit fix`, cuja proposta inclui downgrade incompatível do React Native.

No grafo Rust permanecem avisos de manutenção: [proc-macro-error 1.0.4](https://rustsec.org/advisories/RUSTSEC-2024-0370.html) e [rustls-pemfile 2.2.0](https://rustsec.org/advisories/RUSTSEC-2025-0134.html). O segundo continua usado para PEM; o aviso de abandono não descreve por si só uma exploração. O aviso [glib 0.18.5](https://rustsec.org/advisories/RUSTSEC-2024-0429.html) está no lockfile multiplataforma, mas não no grafo de build Windows selecionado; proc-macro-error também não aparece nesse grafo. Nenhuma atualização ampla de Tauri foi feita nesta correção.

## Proteções revisadas no código

- Persistência de credenciais antes de publicar um novo pareamento; restauração do pareamento anterior quando a gravação falha.
- Revogação interrompe a sessão e bloqueia reconexão no processo mesmo quando o armazenamento falha. O erro exige nova tentativa: reiniciar antes de salvar a revogação pode restaurar o acesso antigo. Um novo pareamento exige aprovação e gravação bem-sucedida.
- Clipboard Android valida a descrição do mesmo ClipData que fornece o texto. Conteúdo marcado sensível não é lido para sincronização. Isso não detecta todas as senhas.
- Clipboard Windows confere a sequência sob OpenClipboard antes de uma escrita automática; uma alteração local pendente é preservada.
- Nenhum conteúdo de clipboard ou credencial foi incluído nos relatórios de testes. Consultas OSV enviaram somente nomes e versões de crates públicas.

APKs continuam assinados com chave debug de avaliação; o instalador Windows não possui Authenticode. Assinatura de produção, testes físicos/OEM, leitura óptica de QR e revisão externa de segurança permanecem pendentes. Consulte [testes](TESTING.md) e [revisão de bugs](BUG-AUDIT-2026-10-15.md).

## 0.6.0-kotlin — arquivos do PC, sincronização ligada por padrão, voz e touchpad automático, 16/10/2026

- **Recebimento PC → celular (files.v2).** Só aceita `file.push` numa conexão autenticada que negociou files.v2; o download usa o mesmo OkHttpClient fixado ao certificado do PC. O app não abre nem instala nada sozinho: no Android 10+ o arquivo fica pendente no MediaStore (`IS_PENDING`) até tamanho e SHA-256 conferirem, e qualquer falha apaga a entrada. A notificação só abre o arquivo pelo app que o usuário escolher. Um push com nome ou campo fora do contrato é rejeitado pelo parser estrito.
- **Qualquer tipo de arquivo do celular para o PC** só com PC files.v2; com PC antigo o app mantém PNG/JPG/PDF e 50 MB. O PC continua marcando tudo com `Zone.Identifier`.
- **Sincronização em tempo real ligada por padrão.** Continua respeitando `EXTRA_IS_SENSITIVE` e a regra do Android 10+ de não ler a área de transferência em segundo plano: um texto copiado em outro app só sai do celular quando o usuário volta ao PC Remote, uma vez, e nunca na primeira abertura do processo. O texto do PC é gravado também em segundo plano (o Android não restringe a escrita); quem não quer isso desliga a opção.
- **Touchpad sem passo extra.** O app arma sozinho só com um PC 0.6 (welcome com info.v1 ou files.v2), que guarda a parada de emergência como bloqueio até Liberar; com PC antigo, que encerra a conexão na parada de emergência, o app exige Ativar touchpad, para não desfazê-la ao reconectar. Desligar no celular vale enquanto o processo existir.
- **Microfone.** RECORD_AUDIO pedido só no primeiro toque no microfone; o reconhecimento usa o serviço do sistema, uma frase por vez, nunca contínuo, e o app encerra a escuta em no máximo 20 s mesmo se o serviço não responder. O texto reconhecido só vira comando de mídia ou pesquisa com origem fixa no PC.
- **Permissões.** Saiu VIBRATE. Entraram RECORD_AUDIO (sob demanda), FOREGROUND_SERVICE e FOREGROUND_SERVICE_DATA_SYNC (serviço não exportado, só exibe o progresso de transferências iniciadas com o app aberto) e a consulta `<queries>` ao RecognitionService.
- **Build debug.** `DebugInviteReceiver` (exportado só em `src/debug`) recebe um convite de pareamento pelo adb para testes no emulador. Ele não existe no APK release, e o convite ainda exige o código e a aprovação no PC.
