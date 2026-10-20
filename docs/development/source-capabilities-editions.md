# Entrega — capacidades de fontes e identificação de edições

Entrega 3a.2, 19/10/2026. Base `d902591`, branch
`codex/source-capabilities-editions`. Versão 0.1.2/Unreleased, sem tag/release.
Decisão: [ADR 0012](../adr/0012-source-capabilities-local-editions.md).

## Implementação e arquivos

O registro executável declara as capacidades reais das três fontes existentes.
Somente Commons oferece importação de MIDI por URL; alternativas de arquivo
exportado/texto colado permanecem manuais. Busca, seleção de partes e conexão de
conta não estão implementadas. A interface consulta e valida o contrato; falha
bloqueia o envio de importações. Política e formato continuam verificados antes
de qualquer rede. Um adaptador compartilhado preserva cooldown/backoff ao trocar
de perfil, sem persistir contato ou compartilhar credenciais.

A biblioteca permite registrar título de edição e citação/evidência, corrigir e
retirar a identificação por novos registros. O histórico é uma declaração local,
sem verificação editorial, reconciliação automática ou promoção de direitos.
Referências opcionais ficam como texto escapado e não são acessadas. Origem
inicial, arquivo original, obra/arranjo e conteúdo musical permanecem separados.

Schema 4 acrescenta histórico limitado a 16 registros; schemas 1–3 continuam
legíveis. Escritas transacionais preservam o histórico mais recente durante
autosave, cópia e salvar como novo. A API exige sessão, propriedade no perfil,
ativo correspondente e revisão esperada. Dados incompatíveis retornam erro;
concorrência não substitui registros. Limite de manifesto continua em 64 KiB.

O formulário mantém rascunhos em memória por projeto ao navegar ou recolher as
ações. Erros conservam os campos; recarregar consulta o histórico autoritativo.
Troca de perfil exige guardar/cancelar, com ação para localizar o projeto
pendente, inclusive em outra página da biblioteca. Cancelar/guardar o último
rascunho remove o aviso. Fechamento usa a confirmação existente de dados pendentes.

Java: `ScoreSourceProvider`, `SourceRegistry`, `WikimediaCommonsAdapter`,
`ProfileSessions`, `EditionEvidence`, `ProjectStore`, `ScoreWorkspace` e rotas
do `DesktopServer`. Angular: `SourceCapabilities`, `EditionDrafts`,
`edition-contract`, `EditionPanel`, integração na biblioteca/origem/importação e
proteções do estúdio. Testes dedicados Java/Angular e aceitação nativa opt-in.
Nenhuma dependência, asset, provedor ou módulo musical vazio foi acrescentado.

## Verificação

| Verificação | Resultado local |
| --- | --- |
| Angular format/lint/tipos | Passaram |
| Angular testes | 196 testes em 35 arquivos passaram |
| Angular produção | Passou; inicial 189,32 kB, estúdio sob demanda 408,51 kB |
| `npm audit --audit-level=low` | Zero vulnerabilidades |
| Licenças | 586 entradas verificadas, nenhuma dependência nova |
| Java build/installDist, `--warning-mode=fail` | Passou; 242 testes, 113 desktop, zero falhas/erros/skips |
| Java real + Chromium, rede externa bloqueada | Identificar/corrigir/retirar, rascunho protegido, edição real, salvar como novo e reinício passaram |
| Janela nativa JCEF | Perfis, biblioteca, original verificado e identificação pelo formulário passaram |
| Detector visual | Uma execução, zero achados em regex degradado; sem contraste calculado/seletores |
| Revisão independente | `ship`: aviso residual resolvido, atalho contextual visível, sem regressões observadas |

Testes determinísticos cobrem vínculo ativo/obra, campos estritos, entrada
malformada, referência insegura, concorrência com um vencedor, falha de escrita,
limites de registros/bytes sem truncar histórico, manifesto inválido e legado.
Autosave com snapshot antigo não apaga a identificação; reinício/duplicação
preservam registros. API recusa token ausente e GET/POST de outro perfil.
Provider fixtures verificam capacidades reais e cooldown entre workspaces.
Os testes de rede externa usam fixtures explícitas, não são prova de uma consulta
ao Commons em produção. A política foi revisada nas fontes primárias citadas no
[registro de provedores](../legal/provider-registry.md).

O teste integrado usa Java/Angular reais com o fixture musical existente,
verifica identificações pelo painel e pela API, edita uma nota no motor atual,
salva e reinicia processo/contexto sem rede externa. Referência `example.org` é
apenas dado de teste e nunca é consultada. Resultados e dados isolados ficam em
`.tools/editions-e2e.json`; capturas em `.impeccable/review/editions-*.png` e
`sources-*.png`, fora do Git. Não se usaram APIs simuladas nesse teste.

Aceitação nativa executou classes atuais e o runtime Chromium completo local em
`apps/desktop/build/native/delivery-0.1.2-20261018/ARPEON`, com dados novos isolados.
O roteiro espera o formulário renderizado antes de preencher; confirmação usa
texto de sucesso e o histórico persistido, não um atributo HTML do campo.
Resultado/capturas em `.impeccable/review/native-editions-final/`. Não representa
homologação de novo instalador, hardware de áudio ou leitores de tela.

Revisão encerrada em uma rodada de achados e uma confirmação. O primeiro revisor
falhou por limite de uso; um revisor novo recebeu o mesmo pacote, apontou o aviso
residual e confirmou a correção. Sem avaliação independente de contraste calculado
ou leitor de tela; detector degradado não representa homologação de acessibilidade.

## Limites e sequência

Histórico local não é trilha assinada contra edição externa dos arquivos.
Rascunhos em memória não sobrevivem ao encerramento do processo. Teto de 16
registros/64 KiB pode impedir novas correções/retiradas; falha preserva o anterior
e o formulário. Cópias são projetos independentes após a duplicação. Fazer backup
antes de downgrade para versão sem schema 4. Retenção de originais continua
restrita aos fluxos diretos MusicXML/XML/MXL/MSCZ já entregues.

Sem busca federada, novos conectores, OAuth, verificação editorial ou avaliação
de direitos. Próxima fatia: qualificar e implementar um provedor de arquivos
licenciados com revisão fixada e fixtures, antes da agregação de busca.
Os gates de redistribuição, Windows limpo, áudio físico e atualização assinada
continuam pendentes. Não criar tag/release. `tmp/` permanece intacto.
Commit/push/PR/CI/merge serão reportados após confirmação remota.
