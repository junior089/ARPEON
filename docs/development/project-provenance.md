# Entrega — proveniência inicial e originais

Entrega 3a.1, Windows x64, 19/10/2026. Base `7c83ca5`, branch
`codex/project-provenance`. Versão 0.1.2/Unreleased, sem tag/release.
Decisão: [ADR 0011](../adr/0011-initial-source-evidence.md).

## Implementado e arquivos

Objetos distintos de obra, edição quando identificada, arranjo, ativo e direitos.
IDs de obra/arranjo locais; ativo por SHA-256; nenhuma fusão por título. Preservar
originais exatos de novas importações diretas MusicXML/XML/MXL/MSCZ, separados da
revisão MusicXML salva. Schema 3 transacional com recuperação e integridade;
schemas 1/2 legíveis sem inventar original. A origem inicial não pode ser trocada
silenciosamente no mesmo projeto. Direitos continuam não avaliados.

Consulta/download autenticados e restritos ao perfil proprietário. O navegador
repete a verificação do arquivo. Na biblioteca, origem e download ficam nas ações
do projeto; identificadores e hashes são recolhíveis. Falhas permitem nova
tentativa; mudar seleção cancela respostas e downloads pendentes.

Java: `OriginalScore`, `ProjectProvenance`, `ProjectStore`, `ScoreWorkspace` e
rotas do `DesktopServer`. Angular: `features/score/project-provenance.*` dentro
de `ProjectLibrary`. Testes: `ProjectProvenanceTest`, `ProfileSessionsTest`, specs
do painel e aceitação opt-in `NativeProfilesAcceptance` estendida. Domínio musical,
parser e transporte preservados; nenhuma dependência ou provedor novo.

## Verificação local

| Verificação | Resultado |
| --- | --- |
| `npm run format:check`, `lint`, `typecheck` | Passaram |
| `npm test` | 186 testes, 33 arquivos, passaram |
| `npm run build` | Passou; inicial 189,32 kB; estúdio sob demanda 390,57 kB |
| `npm audit --audit-level=low` | Zero vulnerabilidades |
| `node scripts/check-licenses.mjs` | 586 entradas; sem dependências novas |
| `gradlew :apps:desktop:formatJava build :apps:desktop:installDist --warning-mode=fail` | Passou; 234 testes Java, incluindo 105 desktop; zero falhas/erros/skips |
| Java real + Chromium, rede externa bloqueada | Importação/renderização, salvamento, download, edição real de nota, cópia, reinício, legado e corrupção passaram |
| Janela nativa JCEF | Troca de perfis, painel de origem, biblioteca restaurada e bytes originais idênticos passaram |
| Detector visual | Uma execução, zero achados no regex degradado; sem contraste calculado/seletores |
| Revisão visual independente | `ship`, sem achados materiais; sem homologação independente de leitor de tela/contraste calculado |

Testes de persistência verificam bytes MusicXML, MXL e MSCZ; buffers imutáveis;
IDs de obra distintos para títulos iguais e mesmo ativo para bytes iguais;
origem após edição/cópia; falha de escrita sem modificar o projeto anterior;
backup corrompido não ativado; legado sem original fictício; importação inválida
sem substituir a origem. API recusa token ausente e projeto de outro perfil.
Metadados aninhados incompletos falham como dados inválidos, sem interromper a
listagem dos demais projetos; remover o registro não oculta um original presente.

No Chromium, importar `fixtures/musicxml/first-notes.musicxml`, baixar e comparar
bytes/hash; alterar uma nota no motor existente, conferir hash da revisão diferente
e origem inalterada; duplicar pela biblioteca; reiniciar processo/contexto sem
cache e baixar novamente. Projeto schema 2 não oferece original. Corrupção de
mesmo tamanho em cópia isolada gerou erro e bloqueou consulta/download. Zero erros
de página; erros Java de integridade foram esperados. Não se usaram APIs simuladas.

Capturas em 1280×840, 800×600 e 390×844, estados claro/escuro, integridade e legado,
com uma rodada e uma confirmação das transições de tema. Resultados locais em
`.tools/provenance-e2e.json`, capturas `.impeccable/review/provenance-*.png` e
`native-provenance/`, fora do Git. O teste nativo usa classes atuais e runtime
Chromium completo disponível; não representa novo instalador homologado.

## Limites e próxima etapa

Esta fatia inicia o catálogo pela evidência do projeto. Não implementa busca,
reconciliação bibliográfica, editor de metadados, direitos avaliados, proveniência
de todas as partes combinadas ou novos provedores. MIDI/WAV/texto, Commons e OMR
não têm bytes retidos nesta etapa; a UI informa a ausência. Áudio não é armazenado.
Originais ocupam espaço adicional e ficam em memória limitada durante a sessão;
duplicatas possuem cópias físicas. Arquivo máximo 128 MiB, manifesto 64 KiB,
500 projetos por store. Fazer backup antes de downgrade sem schema 3.

Gates de `packaging/release-readiness.json` permanecem pendentes: redistribuição,
Windows limpo, áudio físico e atualização assinada. Não criar tag/release pública.
`tmp/` permanece intacto. Commit/push/PR/CI/merge são reportados após confirmação.
Próxima entrega: capacidades dos provedores e identificação de edição com evidência,
preparando busca autorizada sem confundir metadados e arquivos.
