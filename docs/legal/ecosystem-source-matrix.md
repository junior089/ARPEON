# Matriz de fontes do ecossistema

Revisão documental: 18/10/2026. Esta matriz não habilita rede por si só; o registro
executável atual continua permitindo apenas o adaptador Commons já testado.
Métodos: A API pública; B API autenticada; C arquivos abertos; D HTML com permissão
expressa; E importação assistida; F referência externa. Nenhuma nova extração HTML
foi autorizada ou implementada nesta entrega.

| Serviço e fonte oficial | Método e conteúdo | Autenticação e limites | Download e redistribuição | Estado, testes e bloqueios |
| --- | --- | --- | --- | --- |
| [OpenScore Lieder](https://github.com/OpenScore/Lieder/blob/main/README.md) | C; índice TSV/YAML e partituras MSCX no repositório; conversão para MXL com MuseScore desktop | Git público sem conta; API GitHub sujeita às cotas abaixo | Corpus declarado CC0; manter procedência/crédito solicitado; preservar licença da revisão importada | Candidato prioritário, não implementado; faltam fixture fixada e conversão MSCX compatível. O repositório não é uma API nativa de MusicXML |
| [Mutopia](https://github.com/MutopiaProject/MutopiaProject), [licenças](https://www.mutopiaproject.org/legal.html) | C; acervo LilyPond, metadados e arquivos publicados; não foi identificada API oficial de busca nesta revisão | Git público; cotas GitHub se usada REST; sem limite específico de download confirmado | CC BY, CC BY-SA ou dedicação ao domínio público por edição; conferir atribuição e compartilhamento pela mesma licença | Candidato, não implementado; conversor LilyPond não existe no Arpeon. PDF não é representação editável; MIDI entra no importador limitado |
| [IMSLP/Petrucci](https://imslp.org/wiki/IMSLP:Copyright_Made_Simple) | E/F inicialmente; partituras e gravações, formatos por arquivo | Integração automatizada e cotas não qualificadas | Direitos variam por autor, edição, gravação e jurisdição; acesso não autoriza republicação | Sem conector; método autorizado de API/download ainda precisa de revisão. Não extrair HTML nem contornar condições de acesso |
| [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API), [limites](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting) | A para metadados/identificadores/relacionamentos; XML/JSON. Não fornece partituras | Consultas públicas sem autenticação; no máximo 1 request/s e User-Agent identificável; dados de usuário/escrita exigem autenticação | Avaliar licença dos campos usados antes de persistir/redistribuir; nenhum direito musical é inferido de um MBID | Planejado para normalização; sem implementação/fixtures. Não contar como segundo provedor de arquivos |
| [Flat API](https://flat.io/developers/docs/api/reference), [autenticação](https://flat.io/developers/docs/api/authentication) | B; partituras, coleções e exportações documentadas; formatos conforme ação | OAuth2/escopos; credenciais OAuth dependem de contato com equipe Flat; cotas e compatibilidade gratuita ainda não qualificadas | Permissões de leitura/exportação e direitos do titular são distintos; conta conectada não autoriza redistribuição | Bloqueado por qualificação/credenciais; sem código OAuth nem armazenamento de tokens. PKCE não foi confirmado pela documentação consultada |
| [CPDL/ChoralWiki](https://www.cpdl.org/) | E/F inicialmente; acervo coral, formatos variáveis | A página de políticas consultada retornou HTTP 403; limites não confirmados | Licença individual e política de integração pendentes | Sem conector ou testes; não contornar o bloqueio nem tratar MediaWiki como autorização de automação |
| [GitHub REST](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api) | A/C apenas para repositórios explicitamente licenciados; MusicXML/MIDI/ABC/LilyPond conforme repositório | REST público 60 requests/h por IP; autenticado geralmente 5.000/h; busca e limites secundários próprios | Licença de cada repositório/arquivo, revisão e hash exigidos; repositório público sem licença não basta | Sem busca genérica; futuros adaptadores usarão lista aprovada e cache/paginação, não crawlers |
| [MuseScore.com](https://musescore.com/legal/terms) | E/F; abrir fonte e importar exportação obtida legitimamente pelo usuário | Usuário gerencia o acesso; Arpeon não coleta senhas/cookies | Direitos e permissão de download/publicação independentes | Importação de arquivo existente; sem scraping, endpoints privados ou conector de download |
| [Wikimedia Commons](provider-registry.md) | A/C; Action API, metadados e original MIDI de uma página `File:` escolhida | Sem conta; contato no User-Agent; uma tentativa/2 s por instância, backoff em 429/503 no adaptador | Licença por arquivo exibida para revisão; não é autorização geral de redistribuição | Implementado antes desta entrega; `WikimediaCommonsAdapterTest`, hash/MIME/host/tamanho e política antes de rede. Sem busca federada/cache de mídia |
| Instituições e repositórios autorizados por usuários | C/E/F após qualificação por fonte | Não há serviço/API específico autorizado ainda | Declaração verificável de direitos e formatos necessários | Não implementado; cadastrar uma URL não poderá habilitar SSRF ou execução de arquivos |

## Gates antes de ativar um provedor

Fixar revisão e fixture licenciada; declarar capacidades reais, formatos e ações
indisponíveis; limitar tamanho, hosts e redirecionamentos; configurar timeout,
cache, concorrência, cancelamento e backoff; manter fonte, licença e hash do
original separados do documento normalizado. Não declarar partes ou MusicXML
quando houver apenas PDF ou metadados. Download privado, derivação e publicação
exigem permissões distintas. Revalidar políticas antes de implementar cada fonte.
