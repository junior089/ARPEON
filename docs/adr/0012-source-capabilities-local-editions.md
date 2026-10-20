# ADR 0012 — capacidades executáveis e identificação bibliográfica local

Status: aceito. Data: 19/10/2026. Evolui o ADR 0011.

## Decisão

`SourceRegistry.Provider` declara nome, capacidades, formatos automatizados e
limitações. `ScoreSourceProvider` representa o adaptador real de prévia por URL;
o Commons o implementa. A política e o formato são verificados antes de rede.
Somente MIDI de uma página Commons escolhida possui importação por URL. Nenhum
provedor declara busca, seleção de partes ou conexão de conta implementadas.
O Angular consulta o contrato autenticado e bloqueia importação se não puder
validá-lo. Não criar métodos vazios de busca/OAuth para preencher uma interface.

Uma instância do adaptador Commons atende às sessões dos perfis da mesma
aplicação. Seu cooldown/backoff não reinicia na troca; contato e rascunho continuam
por requisição/sessão, sem credenciais de terceiros compartilhadas.

Identificação bibliográfica é uma declaração local separada da proveniência
inicial imutável. Manifesto schema 4 acrescenta `editions`, lista ordenada de até
16 registros. Cada registro tem UUID, ativo de origem, edição com obra vinculada
ou retirada explícita, citação obrigatória, referência HTTPS opcional e data do
servidor. Correções e retiradas acrescentam registros; não apagam os anteriores.
UUIDs de edição são locais e não reconciliam catálogos nem provam autenticidade.

A referência é texto escapado, nunca buscado. Aceitar HTTPS sem credenciais,
porta explícita, query ou fragmento; título até 200 caracteres, citação/URL até
1.000, texto NFC sem controles ou quebras. Identificação não altera notas,
original, direitos ou declaração recebida da fonte. Uma data de upload da API
Commons não identifica uma edição bibliográfica.

GET/POST `/api/projects/<UUID>/editions` exigem sessão e propriedade no perfil.
POST valida campos exatos, ativo e revisão esperada; concorrência retorna 409.
A gravação usa a transação existente do projeto. Autosave preserva o histórico
mais recente do disco; duplicação e salvar como novo preservam sua linhagem.
O limite global do manifesto (64 KiB) continua valendo; falha não trunca registros.

Rascunhos do formulário ficam em memória por projeto ao recolher a biblioteca.
Erro de escrita/concorrência preserva os campos. Troca de perfil exige guardar
ou cancelar; fechamento usa a proteção existente de dados pendentes. Rascunhos
não são recuperáveis após encerrar o processo.

## Compatibilidade e consequências

Schemas 1–3 permanecem legíveis, com histórico vazio quando ausente. Schema 4
exige a lista e valida os vínculos; dados inválidos falham sem fabricar origem.
Fazer backup antes de downgrade para uma versão sem schema 4. O histórico não é
um log assinado contra alterações externas; é evidência local com integridade
estrutural e persistência transacional.

Sem novas dependências, fontes, busca federada, verificação editorial, avaliação
jurídica ou tráfego para referências. Próxima fatia: um provedor de arquivos
autorizados com revisão fixada e fixture licenciada, antes da agregação de busca.
