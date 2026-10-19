# ADR 0010 — Conta local e sessões de estudo independentes

Status: aceito para as entregas 2a/2b do ecossistema.

## Identidade e armazenamento

Uma pasta de dados possui uma conta local, com ID UUID estável, nome, perfil ativo
e adesão explícita à abertura no último perfil. Ela é propriedade de dados neste
computador, não uma autenticação remota. Cada perfil tem ID próprio, nome, avatar
por cor/iniciais, instrumento principal, instrumentos secundários e arquivamento.
Instrumentos usam o catálogo musical existente; não alteram implicitamente partes
importadas nem habilitam exercícios que o produto não oferece.

O limite vem de `arpeon.accountProfiles.max` (propriedade Java, padrão 8), validado
entre 1 e 32. Arquivados contam na quota e preservam os dados. Reduzir o limite
configurado não exclui perfis já existentes. Não é possível arquivar o ativo nem
selecionar um arquivado. Exclusão definitiva e PIN não fazem parte desta entrega.

`identity/account.json` registra a conta; `identity/profiles/<UUID>/` contém
projetos, preferências, históricos de prática/análise e bancos de sons privados.
Áudio de entrada/referência, atualizações, cache Chromium e logs permanecem no
escopo do dispositivo/instalação. A configuração geral de UI e leitura existente
passa a pertencer ao perfil, sem mudar o schema de seu arquivo. Cache do navegador
usa conta/perfil na chave e nunca inicia um perfil novo a partir de chaves legadas.

SQLite foi considerado, mas os stores atuais já garantem gravação durável,
checksums e recuperação de projetos. Nesta etapa, diretórios e um registro pequeno
atômico permitem isolamento sem reescrever esses contratos. Reavaliar SQLite para
catálogo/outbox/sincronização, com uma migração própria.

## Migração e falhas

Na primeira abertura, sob o lock de instância existente, preparar uma pasta irmã
temporária com registro e primeiro perfil. Copiar somente projetos, preferências,
históricos e bancos existentes; verificar SHA-256 da origem antes/depois e da
cópia, sincronizar arquivos e ativar a pasta `identity` por rename. Originais
permanecem intactos. Links simbólicos e arquivos especiais são recusados; a cópia
tem limites de profundidade, quantidade, volume e espaço disponível.

Uma conta já existente nunca é recriada diante de corrupção. O parser valida
schema, campos, tipos (incluindo consentimento booleano), identidades e instrumentos.
O registro anterior fica em `account.json.backup`. Gravações detectam alterações
externas antes de substituir o arquivo. Se registrar um novo perfil falhar,
remover somente seu diretório recém-criado e ainda vazio. Uma migração interrompida
pode deixar `.identity-staging-*`, que não é ativada nem usada como conta.

Depois de migrar, novas alterações não são espelhadas nos arquivos legados.
Reverter para uma versão antiga exige backup/exportação dos perfis atuais; a
cópia legada representa apenas o momento anterior à migração. Não unir bibliotecas
automaticamente por nome ou identificador de projeto fornecido pelo cliente.

## Troca de sessão

Não trocar stores mutáveis dentro de um servidor em uso. `ProfileSessions` cria
um `DesktopServer` com stores e workspaces próprios para cada perfil, em outra
porta loopback com novo token. A seleção inicial bloqueia dados musicais até o
usuário escolher um perfil, salvo adesão salva à abertura automática.

O frontend salva o projeto, sincroniza preferências e bloqueia troca durante
captura, operações ou rascunhos pendentes. O backend repete as verificações e usa
um lock de leitura/escrita: a troca é recusada enquanto outra requisição estiver
em andamento. Antes de substituir a sessão, validar/iniciar o destino e persistir
a seleção. Retirar a sessão antiga, parar reprodução e impedir novos requests;
encerrar seus recursos após responder. A página navega à nova sessão e reconstrói
os serviços Angular, sem reutilizar estado musical do perfil anterior.

A janela JCEF aceita navegação apenas para a origem loopback fornecida pelo host
atual, não para qualquer localhost. O frontend também valida o formato da nova
URL. Não registrar tokens. IDs de projetos são resolvidos somente no diretório do
perfil; IDs alheios são recusados inclusive ao salvar, renomear, abrir ou excluir.

Os construtores de servidor usados por testes/harnesses legados continuam sem
gerência de perfis e informam ausência de `/api/account` com 404. O launcher de
produção usa sempre `ProfileSessions`. Instância, diretório de instalação, DSP,
parsers, transporte musical e permissões de microfone preservam seus contratos.

Perfis locais organizam bibliotecas; não isolam pessoas que compartilham acesso
à mesma conta do sistema operacional. Nenhuma conexão privada externa ou
autorização de publicação é criada ou herdada nesta etapa.
