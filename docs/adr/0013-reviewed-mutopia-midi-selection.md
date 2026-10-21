# ADR 0013 — seleção Mutopia revisada e original MIDI

Status: aceito. Data: 20/10/2026. Evolui o ADR 0012.

## Decisão

Habilitar uma seleção real do Mutopia Project: Allemande da Partita BWV 1013
para flauta solo, edição de Jacob Shapiro, Mutopia-2017/10/31-1528. A página e o
LilyPond declaram Public Domain. Fixar separadamente a revisão Git da evidência
LilyPond, o SHA-256 do ZIP publicado e o SHA-256 do membro MIDI selecionado.
Isso não afirma que o MIDI foi recompilado localmente a partir daquela revisão.
Crédito, URLs, licença e hashes ficam no [aviso do fixture](../../fixtures/mutopia/README.md).

`MutopiaCatalogue` contém somente seleções revisadas; a capacidade
`catalogueImport` é distinta de `importByUrl` e de busca. O adaptador recebe um
ID, nunca uma URL fornecida pelo usuário. GET `/api/import/catalogue/mutopia`
consulta a lista local, sem rede externa; POST `/api/import/catalogue/preview`
valida provedor/seleção e baixa o pacote fixado. Ambos exigem sessão. Não há
HTML scraping, crawler, busca geral, OAuth ou acesso a IMSLP/MuseScore.

Um download HTTPS fixo, sem redirecionamentos, até 1 MiB; cabeçalhos e leitura
do corpo têm limites de tempo. Verificar o hash antes de abrir o ZIP; limitar
membros, nomes, tamanho expandido e duplicatas, sem extrair arquivos no disco.
Conferir também tamanho e hash do MIDI. Falha de integridade exige nova revisão,
não busca uma cópia alternativa. Sem retry automático; respeitar Retry-After
numérico/data em 429/503, com cooldown mínimo de cinco segundos entre tentativas.

Uma instância compartilhada entre perfis conserva o limitador e um único pacote
verificado em memória por dez minutos. Somente dados públicos são compartilhados;
rascunho, token de aceitação e biblioteca permanecem na sessão/perfil. Reanalisar
a mesma seleção usa cache verificado quando válido. Não há persistência de cache,
credenciais ou download em segundo plano.

Usar o parser MIDI monofônico e o construtor musical existentes, sem relaxar
recusas musicais. A seleção produz 735 notas e 47 compassos do rascunho 4/4.
Os demais membros do ZIP não são oferecidos: atravessam barras que o construtor
atual recusa. A UI informa perdas de grafia, andamento, repetições e notação e
exige revisão antes de abrir. A prévia não substitui a partitura atual.

Cancelar interrompe a espera do cliente e descarta a resposta tardia. O download
limitado já iniciado no servidor pode concluir e alimentar apenas o cache público;
não abre nem salva um projeto. Uma nova prévia invalida o token anterior.

Metadados declarados (título, compositor, instrumento) acompanham `Source` como
campos opcionais compatíveis com projetos antigos. Schema 5 permite original
MIDI: bytes exatos do membro selecionado, separados do MusicXML normalizado.
O ZIP não é preservado no projeto; seu hash e a seleção ficam na procedência.
Download privado, integridade, duplicação, edição e reinício usam os mecanismos
existentes. Schemas 1–4 continuam legíveis. Fazer backup antes de downgrade.
`NOT_REVIEWED`/`UNKNOWN` não são promovidos pela declaração Public Domain.

## Qualificação e alternativas

OpenScore Lieder oferece MSCX, que exige conversão não disponível. A derivação
OSSQ-OMR oferece MusicXML/CC0, mas os três candidatos testados exigem recursos
que o parser recusa (número de movimento, mudança de tonalidade ou notas sem
duração/grace). Não remover elementos dos arquivos para alegar compatibilidade.
As cópias temporárias de diagnóstico não são fixtures nem arquivos habilitados.
O parser permanece intacto; este incremento entrega um caminho autorizado real.

## Build e segurança

Os avisos de 20/10/2026 atingem Vite 8.3.0–8.3.2/launch-editor usado no builder
Angular. O builder atual fixa Vite 8.3.0; manter Angular e aplicar resolução
limitada a `@angular/build → vite@8.3.4`, versão corrigida já usada pelo Vitest.
Lockfile e inventário de licenças refletem a remoção da cópia vulnerável, sem
ocultar auditoria nem instalar downgrade forçado. Revalidar `npm ci`, auditoria,
testes e build. [Avisos oficiais](https://github.com/vitejs/launch-editor/security/advisories).
