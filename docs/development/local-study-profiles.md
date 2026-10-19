# Entrega — conta local e perfis de estudo

Execução: Windows x64, 19/10/2026. Entregas 2a/2b do plano do ecossistema.
Base: `041830b` em `main`; branch `codex/local-study-profiles`. Versão mantida
em 0.1.2, mudanças em Unreleased. Conta remota, PIN e sincronização continuam
fora desta entrega. Decisão de arquitetura: [ADR 0010](../adr/0010-local-account-profile-sessions.md).

## Comportamento entregue

- Conta local com UUID e nome; perfis com UUID, nome, iniciais/cor, instrumento
  principal e secundários do catálogo musical existente. O instrumento organiza
  o perfil; não transforma a partitura nem amplia a prática além dos exercícios
  de flauta já disponíveis.
- Criar, editar, selecionar, arquivar e restaurar. Limite único configurável pela
  propriedade Java `arpeon.accountProfiles.max`, padrão 8, faixa 1–32. Arquivados
  contam no limite, preservam dados e precisam ser restaurados para seleção.
  Reduzir o limite não exclui identidades existentes. Não há exclusão definitiva.
- Seletor na abertura, inclusive com um único perfil. Abrir diretamente no último
  perfil depende de consentimento salvo. Falha de gravação restaura o valor
  confirmado do checkbox e apresenta erro.
- Bibliotecas, preferências de interface/leitura, históricos de prática/análise
  e bancos de sons privados pertencem ao perfil. Dispositivos de áudio,
  atualizações, logs e cache do Chromium permanecem no dispositivo/instalação.
- Troca pelo cabeçalho ou pela categoria real Conta e perfis nas Preferências.
  Salvar o projeto e sincronizar ajustes antes de abrir o seletor. Captura,
  tentativas, reconhecimento, importação pendente e operações em andamento
  impedem a troca. O backend repete as verificações.
- Cada seleção cria servidor com stores fixos, porta e token novos. A sessão
  anterior é retirada e seus recursos encerrados. O estúdio Angular é recriado;
  o token anterior não autoriza acesso ao destino. Navegação JCEF fica restrita
  à origem corrente fornecida pelo host. IDs de projetos de outro perfil são
  recusados ao abrir, salvar, duplicar, renomear ou excluir.
- Seletor e estúdio carregados sob demanda. Nenhuma consulta a dados musicais
  ocorre antes da escolha inicial. Caches de preferências usam conta/perfil e
  não iniciam perfis novos a partir do localStorage legado.

## Migração e recuperação

Sob o lock de instância existente, copiar somente os escopos legados conhecidos
para `.identity-staging-<UUID>/profiles/<UUID>/`. Conferir SHA-256 da origem
antes/depois e da cópia, sincronizar arquivos e ativar por rename. Os originais
permanecem intactos. Validar links, tipos de arquivo, profundidade, quantidade,
volume e espaço disponível antes da ativação. Uma interrupção pode deixar pasta
de staging, que não é tratada como conta válida.

Registro em `identity/account.json`, com schema/tipos estritos e backup anterior
em `account.json.backup`. Não recriar conta corrompida; não sobrescrever alterações
externas. Uma falha ao registrar perfil novo remove somente seu diretório recém
criado e vazio. Recuperação de conta exige restaurar uma cópia válida com o
aplicativo fechado; não existe reparação silenciosa nem UI de recuperação nesta
etapa. A cópia legada representa o instante da migração: versões antigas não
recebem as mudanças posteriores. Fazer backup/exportação antes de reverter.

Perfis organizam dados locais; não constituem fronteira de segurança entre
pessoas com acesso à mesma conta do sistema operacional. Não há herança de
conexões privadas, autenticação remota ou autorização de publicação.

## Arquivos principais

- Java: `LocalAccountStore`, `ProfileMigration` e `ProfileSessions`; integração em
  `DesktopApplication`, `DesktopServer`, `DesktopWindow`, `ProjectStore` e
  `ScoreWorkspace`.
- Angular: `StudyProfiles`, `ProfileShell`, `ProfileSelector`; integração com
  o cabeçalho, a central e os stores compartilhados de preferências/leitura.
- Regressões: `LocalAccountStoreTest`, `ProfileSessionsTest`, specs dos serviços
  e seletor; aceitação nativa opt-in `NativeProfilesAcceptance`.

## Validação

Ferramentas locais: Node 24.21.0, JDK 25, Gradle 9.8.1. Sem nova dependência.

| Verificação | Evidência |
| --- | --- |
| Formatação, lint e tipos Angular | Passaram sem supressões |
| Testes Angular | 180 testes em 32 arquivos |
| Build Angular de produção | Inicial 189,32 kB, abaixo do aviso de 500 kB; estúdio e seletor em chunks próprios |
| `gradlew build :apps:desktop:installDist --warning-mode=fail` | Passou; 225 testes Java no conjunto, incluindo 96 desktop; sem falhas/skips |
| Licenças | 586 entradas npm fixadas; sem dependência nova |
| Chromium + servidor Java real | Passou com dados isolados, importação/renderização/salvamento MusicXML e reinícios sem rede externa; sem erros de página |
| Janela nativa JCEF | Passou: seleção inicial, rotação de sessão, importação real, biblioteca vazia no segundo perfil e biblioteca restaurada no primeiro |
| Detector visual | Uma execução, zero achados no modo regex degradado; parsers HTML/CSS indisponíveis, sem avaliação de contraste calculado |
| Revisão visual independente | `ship`, zero achados materiais abertos; contradição sobre avatares em DESIGN.md resolvida |

Os testes Java cobrem concorrência no limite de oito, arquivamento/seleção,
migração sem alterar originais, schema inválido, falha de escrita, alteração
externa, portas/tokens, projetos alheios, preferências e históricos independentes,
reinício, payloads inválidos, captura, projeto não salvo e request em andamento.
Os testes Angular incluem confirmação de respostas, rascunho preservado em
falhas/saída recusada, instrumento exibido na edição e consentimento não salvo.

Roteiro de navegador executado: editar o perfil migrado para Flauta; criar Piano
com secundário Flauta; selecionar e importar `fixtures/musicxml/first-notes.musicxml`;
habilitar leitura em português; trocar imediatamente; conferir biblioteca vazia
e ajustes padrão; salvar zoom próprio; retornar e conferir projeto, tema, zoom
e leitura originais; arquivar/restaurar e conferir dados; reiniciar em contexto
novo, testar seletor padrão e retomada somente após adesão. O servidor utilizou
`ProfileSessions` e arquivos reais, sem APIs simuladas.

Capturas locais em `.impeccable/review/profiles-{desktop,edit,compact,mobile}.png`
(1280×840, 800×600 e 390×844) e `native-profiles/`. Resultados auxiliares ficam
em `.tools/profiles-e2e.json` e `native-profiles/result.json`, fora do versionamento.
Os testes não homologam hardware de áudio, leitor de tela, Windows limpo,
atualização assinada ou instalador. A aceitação nativa usa classes atuais e o
runtime Chromium completo já disponível; não representa nova release empacotada.

## Integração e limites de distribuição

Commit, push, PR, CI remoto e merge são informados com identificadores verificados
no fechamento da entrega. `tmp/` preexistente é preservado e não é versionado.
Não alterar os gates de `packaging/release-readiness.json`: redistribuição,
Windows limpo, atualização assinada e áudio físico ainda impedem release pública.
Não criar tag nem aumentar versão por esta entrega. Próxima fatia: catálogo e
proveniência, distinguindo obra, edição, arranjo, original e direitos antes da busca.
