# Entrega — seleção Mutopia revisada

Entrega 3b.1, 20/10/2026. Base `7b881f8`, branch
`codex/mutopia-source-import`. Versão 0.1.2; publicação experimental Windows
solicitada em 21/10/2026, com limites descritos nas notas da pré-release.
Decisão: [ADR 0013](../adr/0013-reviewed-mutopia-midi-selection.md).

## Implementação

Seleção real adicional ao Commons: Allemande da Partita BWV 1013, edição Mutopia
1528, por Jacob Shapiro. A fonte declara Public Domain; o projeto continua com
direitos `NOT_REVIEWED`/`UNKNOWN`. O catálogo inicial contém um movimento
validado, não o acervo inteiro. Não há busca federada ou acesso por URL livre.

Lista local autenticada; download somente ao analisar. Revisão Git da evidência
LilyPond e hashes separados do pacote/membro MIDI. Um download HTTPS fixo, até
1 MiB, sem redirects, com timeout, espera de cinco segundos, Retry-After em
429/503 e cache público único por dez minutos em memória. Nada é extraído no
filesystem; membros/nomes/expansão e integridade são limitados. Cache/limitador
atravessam perfis; tokens, rascunhos e bibliotecas permanecem isolados.

Prévia usa parser MIDI e construtor existentes (735 notas, 47 compassos em 4/4),
permite visualizar o rascunho e exige aceitação. UI confirma que hash, arquivo,
licença e metadados correspondem à seleção. Falha não altera a partitura aberta.
Cancelar interrompe a espera do cliente e ignora respostas tardias; o download
limitado no servidor pode concluir apenas para o cache público. Nenhum projeto
é criado nessa conclusão. Não há retry automático.

Schema 5 preserva o membro MIDI exato, separado do MusicXML normalizado, com
download privado/integridade, cópia e reabertura. Título, compositor, instrumento,
crédito e declaração de licença acompanham a origem. Schemas 1–4 continuam
legíveis. MIDI local/Commons e outros fluxos mantêm a política anterior de
retenção; esta fatia acrescenta original somente à seleção Mutopia.

## Arquivos e módulos

Java desktop: `MutopiaCatalogue`, `MutopiaAdapter`, `ScoreSourceProvider`,
`ImportWorkspace`, `SourceRegistry`, `ProfileSessions`, rotas do `DesktopServer`,
`ScoreWorkspace`, `OriginalScore`, `ProjectProvenance` e `ProjectStore`.
Angular: `features/import/mutopia-catalogue.*`, `ImportPanel`, capacidades e
validação da origem da biblioteca. Parser, domínio e construtor MIDI não foram
alterados. [Fixtures e declaração](../../fixtures/mutopia/README.md) incluem ZIP
e LilyPond reais, crédito, hashes e fontes; avisos de terceiros atualizados.

Durante o gate de auditoria, avisos novos atingiram Vite 8.3.0–8.3.2 usado no
builder. Resolver `@angular/build → vite@8.3.4` preservou Angular 22.2.2 e removeu
a cópia vulnerável duplicada; não houve downgrade forçado ou supressão de avisos.
O lockfile e inventário agora têm 585 entradas, nenhuma biblioteca nova de runtime.

## Verificações locais

| Verificação | Resultado |
| --- | --- |
| Angular format/lint/tipos | Passaram |
| Angular testes | 200 testes, 36 arquivos, passaram |
| Angular produção | Passou; inicial 189,32 kB; estúdio sob demanda 418,46 kB |
| `npm audit --audit-level=low` | Zero vulnerabilidades após corrigir a resolução do Vite |
| `node scripts/check-licenses.mjs` | 585 entradas verificadas |
| `gradlew build :apps:desktop:installDist --warning-mode=fail` | Passou; 246 testes Java, 117 desktop, zero falhas |
| Java + Chromium reais | Download ao vivo, hash, prévia sem substituir projeto, notação, aceitação, autosave, download privado e reinício passaram |
| Janela nativa JCEF | Modo opt-in `mutopia-live`: seleção/aceitação, autosave e original conferidos |
| Detector visual | Uma execução, zero achados no regex degradado; sem contraste computado |
| Revisão visual independente | `ship`; quebra de identificadores, CTA em 800×600 e documentação resolvidos |

Testes unitários usam o pacote licenciado real com fetchers explícitos, sem rede
externa no CI. Verificam seleção desconhecida antes de rede, pacote alterado/
excessivo, redirects/HTTP inválidos, cache e expiração, Retry-After numérico/data
inclusive superior a uma hora e limitação entre workspaces. API exige sessão e
campos exatos, impede URL livre, mantém partitura atual na prévia, exige revisão
e persiste/download do MIDI exato. UI cobre contrato/lista, capacidade indisponível,
retry, confirmação da seleção e cancelamento com resposta tardia.

O E2E usa Java/Angular de produção e baixa somente o ZIP aprovado. O navegador
bloqueia rede externa; o adaptador Java faz a consulta real. Após salvar, processo
e contexto novos reabrem o projeto sem refazer a importação. Esperas verificam a
presença real do novo projeto, não um texto antigo de “Projeto salvo”. Zero erros
de página. Resultado: `.tools/mutopia-e2e.json`; capturas desktop/compacto/estreito,
temas claro/escuro, prévia e origem em `.impeccable/review/mutopia-*.png`.

O parecer final confirmou as três correções materiais, incluindo prévia em
800×600 e 390×844. O parecer de confirmação usou um revisor novo porque o
anterior não estava disponível após a retomada; não houve segunda execução do
detector. Contraste computado e capturas de loading/erro não foram realizados;
esses estados têm cobertura comportamental, sem alegação de homologação visual.

Aceitação nativa mantém o modo original offline e acrescenta argumento opcional
`mutopia-live`. Espera o botão habilitado após a revisão e confere o projeto
gravado e o hash do MIDI. Classes atuais com runtime Chromium completo local;
resultados em `.impeccable/review/native-mutopia-final/`. Não representa novo
instalador homologado nem teste de áudio físico.

## Limites e sequência

Não habilitar os outros três movimentos do ZIP: a conversão atual recusa notas
que atravessam barras do rascunho 4/4. OpenScore Lieder ainda exige conversor;
três candidatos OSSQ-OMR foram recusados por recursos musicais não suportados.
Nenhuma remoção de elementos foi incorporada para declarar compatibilidade.

Seleção MIDI é uma transcrição revisável, não uma edição fiel da notação
LilyPond. O arquivo original pode ser conservado mesmo quando o documento
normalizado diverge. O ZIP não é copiado ao projeto. Fazer backup antes de
downgrade sem schema 5. Direitos não são promovidos automaticamente.

Próxima etapa: melhorar conversão MIDI e qualificar novas seleções compatíveis;
agregação de busca exige provedores de pesquisa reais e dados licenciados.
Gates de redistribuição, Windows limpo, áudio físico e atualização assinada
permanecem abertos. A publicação solicitada é uma pré-release experimental,
sem aprovação dos gates estáveis, assinatura ou feed de atualização.
`tmp/` permanece intacto. Git/push/PR/CI/merge e artefatos serão reportados
após confirmação remota.
