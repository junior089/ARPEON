# ADR 0011 — Origem inicial e original verificável

Status: aceito para a entrega 3a.1 do ecossistema.

## Decisão

Preservar o motor musical e os stores por perfil. O manifesto de projeto passa
ao schema 3 com `ProjectProvenance` opcional. Separar `Work` (título/compositor
declarados), `Edition` (publicação identificada, ausente sem evidência),
`Arrangement` (partes/instrumentos da entrada), `SourceAsset` (arquivo/hash) e
`RightsRecord` (declaração e permissões não avaliadas). IDs de obra/arranjo são
locais e estáveis no projeto salvo; títulos iguais não unificam obras. O ativo
usa `sha256:<hash>`. Identificadores externos de arquivo, como a página Commons
já conhecida, pertencem ao ativo, não à obra.

Os objetos descrevem a importação inicial, não a revisão editada ou os direitos
de todas as partes combinadas. Renomear/duplicar/reabrir mantém essa evidência.
Trocar ou remover a proveniência inicial no mesmo projeto é recusado: outra
entrada exige outro projeto. Cópias de estudo compartilham a identidade de origem
e mantêm revisões próprias. Não há reconciliação bibliográfica, editor de
metadados nem catálogo global nesta fatia.

## Retenção e integridade

Novas aberturas diretas de MusicXML/XML, MXL e MSCZ conservam os bytes exatos em
`source.bin`, separados do `score.musicxml` normalizado/editável. `OriginalScore`
é imutável e limitado a 128 MiB, inclusive no recebimento por stream. O parser
continua StAX; o workspace agora também mantém um buffer limitado do original.
Manifesto limitado a 64 KiB, sem bytes codificados em JSON; 500 projetos por store.

Gravar original, score e manifesto no staging transacional existente e substituir
a pasta somente após todas as escritas duráveis. Erros preservam a versão anterior.
Reabertura, duplicação, recuperação de backup e download validam tamanho e SHA-256
do original; backup corrompido não é ativado. Duplicatas possuem cópias físicas;
excluir o projeto remove sua cópia privada. Não há deduplicação global nesta etapa.

Schemas 1/2 continuam legíveis; não fabricar registros ou reconstruir originais
com XML exportado. Legado salvo novamente pode ter schema 3 sem proveniência.
Fazer backup antes de retornar a versões antigas que não leem schema 3.
MIDI/WAV/texto, Commons, OMR e importação de partes conservam seus contratos:
esta entrega não retém seus bytes originais nem passa a armazenar áudio.

## Consulta e direitos

`GET /api/projects/<UUID>/provenance` retorna a evidência e o hash da revisão
salva. `GET /api/projects/<UUID>/original` entrega somente original validado,
como attachment; sem original retorna 404. Ambos exigem sessão e propriedade
no perfil atual, sob o lock de troca de sessão. Corrupção falha explicitamente,
sem substituição aproximada ou download externo. O navegador repete tamanho/hash.

A biblioteca apresenta origem, campos desconhecidos, download e identificadores
recolhíveis. Valores são texto escapado; identificadores não acionam rede.
Retenção é local e não concede publicação, redistribuição, treinamento ou
derivação. Licença recebida da fonte é apenas declaração: `NOT_REVIEWED` e
permissões `UNKNOWN` não são promovidos pelo hash, formato ou acesso ao arquivo.

Próxima fatia: capacidades executáveis dos provedores e edição identificada com
evidência, antes de habilitar busca federada.
