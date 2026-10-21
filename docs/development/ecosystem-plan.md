# Ecossistema musical — auditoria e entregas

Data da auditoria: 18/10/2026. Base: `c54c590`. Cada linha abaixo representa
entregas pequenas; não é uma declaração de funcionalidades implementadas.

Atualização em 19/10/2026: as entregas 0/1 estão registradas em
[central de configurações](settings-foundation.md); 2a/2b em
[conta local e perfis](local-study-profiles.md). O inventário abaixo preserva a
fotografia da auditoria inicial; o ADR 0010 define os escopos atuais por perfil.

[3a.1 — proveniência inicial](project-provenance.md) acrescenta evidência tipada
e originais MusicXML/MXL/MSCZ preservados por projeto.
[3a.2 — capacidades e edições](source-capabilities-editions.md) acrescenta
contrato executável de fontes e declarações bibliográficas com histórico local.
Busca, reconciliação bibliográfica e novos provedores dependem das próximas fatias.

[3b.1 — seleção Mutopia](mutopia-source-import.md) entrega um primeiro arquivo
adicional autorizado com revisão, hashes, prévia e original MIDI preservado.
É uma seleção inicial conferida, não busca federada ou catálogo integral.

## Evidências do repositório

- Java 25, Gradle Kotlin DSL, Angular 22 strict/SCSS e JCEF estão em uso.
  Python é ferramenta opcional de auditoria, não pipeline musical em produção.
- `arpeon-domain`, `arpeon-musicxml`, `arpeon-transport` e `arpeon-playback`
  já implementam documentos imutáveis, importação/exportação segura, pulsos
  racionais, mapa de execução e um sequenciador comum. O ADR 0008 delimita
  repetições, vozes, pautas e alinhamento de partes. Não substituir o motor.
- `ScoreWorkspace`, `TransportBar`, `PartImportPanel` e `ProjectLibrary`
  já oferecem edição, mixer, importação revisada e biblioteca local.
  `PracticePanel`, `TunerPanel` e `AnalysisPanel` compartilham preparação de áudio.
- MusicXML/MXL e MSCZ suportado entram no editor; MIDI/WAV/texto geram rascunhos
  limitados. PDF/imagem passam pelo Audiveris e revisão. Não há conversor
  genérico de LilyPond/ABC nem transcrição polifônica geral.
- Projetos e históricos são arquivos versionados com escrita durável, checksums
  e recuperação. Não há SQLite, conta Arpeon, multiperfis ou backend comunitário.
  A palavra "profile" nos diretórios de teste não representa identidade de estudo.
- `DesktopServer` exige token de sessão, valida Host/Origin e limita entradas.
  O token local não autentica uma conta remota. OMR roda em processo isolado.
- Há um adaptador Wikimedia Commons de MIDI por URL selecionada. Não há busca
  federada. O registro de políticas impede acesso a hosts não aprovados.
- CI existente: formatação, lint, tipos, testes, build, auditoria npm e licenças,
  matriz Java em três sistemas e empacotamento/OMR no Windows. Sua configuração
  não comprova que um novo commit passou remotamente.
- Histórico usa Conventional Commits; versão atual 0.1.2, tag existente v0.1.1.
  `origin/master` é a referência padrão local; `origin/main` partia do mesmo SHA.
  `tmp/` preexistente fica fora das entregas. Não reescrever a branch `main`
  que também está aberta em outro worktree.

## Inventário de configurações

| Superfície | Preferência/estado | Persistência e escopo atual |
| --- | --- | --- |
| Menu Exibir, Preferências | Tema da interface | `preferences.json`, instalação local |
| Editor, menu Exibir | Painéis, largura, modo e zoom | `preferences.json`; recolhimento responsivo é temporário |
| Editor, assistência de leitura | Papel, nomes, idioma, oitava, seleção, digitação | `preferences.json`, instalação local |
| Afinador, Prática, Análise, Preferências | Entrada e Lá de referência | `audio-preferences.json`, dispositivo; permissão nunca é restaurada |
| Transporte e mixer | BPM, mute, solo, volumes, pan, presets, loops e estudos | Manifesto do projeto; posição e relógio são sessão |
| Análise e exercícios | Critérios/tipo da tentativa atual | Sessão; resultados no histórico local existente |
| Atualizações | Adesão à consulta automática | `UpdatePreferences`, instalação; inicialmente desligada |
| Importação por URL | Contato para User-Agent, revisão de direitos | Sessão/importação; não constitui conexão OAuth |

A central passa a gerir as preferências locais já implementadas. Os comandos
contextuais continuam ativos. As demais categorias solicitadas só serão exibidas
quando tiverem operações reais, evitando configurações fictícias.

## Sequência e critérios

| Entrega | Depende de | Critérios verificáveis | Risco principal |
| --- | --- | --- | --- |
| 0/1. Central local de configurações | Stores e bridge existentes | Busca sem acentos, estado compartilhado com editor, restauração por categoria, migração legada, confirmação de escrita, reabertura; testes e build | Renderer desatualizado ou preferências sobrescritas por outra tela |
| 2a. Conta local e perfis | Escopos do ADR 0009 | Limite único configurável (padrão 8), criar/editar/selecionar, dados por identidade estável, migração recuperável da biblioteca atual | Mistura de bibliotecas ou troca com gravação pendente |
| 2b. Troca e seleção na abertura | 2a | Salvar antes de trocar; parar transporte/captura; preferências e histórico isolados; reinício no perfil escolhido apenas por adesão | Processos ou requests da identidade anterior |
| 3a. Catálogo e proveniência | 2a e matriz de fontes | Contratos de capacidades, Work/Edition/Arrangement/SourceAsset/RightsRecord distintos, identificadores externos, hash original | Confundir composição, edição e direitos |
| 3b. Busca autorizada | 3a | Dois provedores reais se disponíveis, cache limitado, paginação, falha parcial, normalização e fixtures licenciadas | Cotas, formatos sem conversor e termos mutáveis |
| 4. Descobrir → importar → ensaiar | 3b e importador existente | Seleção de partes verificadas, progresso/cancelamento, originais e linhagem, compatibilidade, projeto persistido e mixer sincronizado | Edições diferentes aparentarem alinhamento |
| 5. Composição incremental | Projetos/editor existentes | Primeiro criação solo/conjunto e compassos; depois ferramentas adicionais, undo/redo e round-trip musical | Representar notação que o transporte não suporta |
| 6. Comunidade autogerenciada | 2 e 3a | Monólito modular, Compose, PostgreSQL/objetos por hash, autenticação, direitos explícitos, revisão imutável, quotas e denúncias | Publicação indevida, abuso e custo operacional |
| 7. Métodos e progresso | 2 e 6; prática existente | Sessões por perfil, métodos persistentes e métricas medidas; publicar/importar método com direitos | Converter incerteza acústica em pontuação falsa |
| 8. OAuth e sincronização | Identidades/versões estáveis | Fluxo oficial, tokens protegidos pelo SO, outbox idempotente, resolução explícita de conflitos, exportação/exclusão | Vazamento entre perfis ou perda offline |
| 9. Distribuição | Gates de cada incremento | E2E real, migração e recuperação, instalador qualificado e release com licenças/assinatura verificadas | Gates nativos já pendentes no projeto |

Cada entrega inclui testes relacionados, changelog, revisão do diff, commit e
push. PR/merge dependem dos gates reais do remoto. Não criar tags nem publicar
binários enquanto os gates de `packaging/release-readiness.json` estiverem abertos.

## Modelo e migração seguintes

Preservar `Score`/`Part` e o manifesto atual. Introduzir Work (identidade da obra),
Edition (publicação específica), Arrangement (instrumentação), SourceAsset
(original imutável com hash) e RightsRecord (permissões por uso) na entrega do
catálogo. LibraryItem referencia o conteúdo por perfil; PracticeProject guarda
configuração de estudo; Publication aponta uma revisão autorizada. Não usar
título normalizado como identidade única nem reduzir esses objetos a Score.

Account será proprietário; Profile será identidade de estudo. SourceConnection
terá proprietário/escopo explícito e nunca será copiada implicitamente ao criar
perfil. A migração de dados locais deverá preparar uma cópia, conferir hashes,
confirmar a nova estrutura antes de ativar e manter recuperação da versão
anterior. SQLite deve ser avaliado nessa migração, sem substituir agora os
stores funcionais. Comunidade indisponível não pode bloquear edição local.

## Direitos, dependências e infraestrutura

Não foram acrescentadas dependências, bibliotecas sonoras ou assets nesta entrega.
AGPL-3.0-or-later e os avisos existentes permanecem. O inventário npm é verificado
por `scripts/check-licenses.mjs`; pendências de redistribuição nativa continuam
em `docs/legal/native-distribution.md`. A matriz de fontes registra a pesquisa
primária e as limitações de conversão. Nenhuma etapa depende de Oracle Cloud.
Hospedagem comunitária pública terá cotas, manutenção e custos a qualificar;
Docker Compose autogerenciado será o primeiro alvo, não uma promessa de free tier
ilimitado. Publicação e sincronização exigirão ação/autorização apropriada.

Resultados executados da primeira entrega: [relatório](settings-foundation.md).
