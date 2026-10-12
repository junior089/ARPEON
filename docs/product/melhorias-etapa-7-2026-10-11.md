# ARPEON — etapa 7: histórico de prática

O histórico de prática passa a ser consultado em páginas de 20 tentativas, com filtros por
exercício, conclusão/interrupção e período. O dia final selecionado é incluído no horário
local do computador. A consulta ordena por encerramento, do mais recente para o mais antigo,
com desempate estável por identificador. Limpar filtros não apaga registros.

O resumo mostra quantas tentativas correspondem à consulta, quantas estão concluídas ou
interrompidas e o total retido. São contagens; não são uma nota de desempenho nem uma avaliação
da qualidade acústica. Nomes musicais substituem códigos internos e datas usam dia/mês/ano.
Números de sustentação e estabilidade usam formatação brasileira; ritmo mostra pulsos.

## Contrato e integridade

A API exige a sessão local existente. `GET /api/practice/history` retorna `entries`, `page`,
`pageSize`, `matchingCount`, `totalCount` e `completedCount`. A paginação é zero-based na API;
a interface mostra a primeira página como 1. `pageSize` é limitado a 1–50, com padrão 20.
`kind`, `outcome`, `from` e `until` são opcionais; os instantes são inclusivo/exclusivo.
Página fora do intervalo é ajustada à última disponível, inclusive após mudança no histórico.
Parâmetros desconhecidos, repetidos ou inválidos retornam 400. Histórico ilegível ou com
data corrompida produz erro explícito, sem substituição por uma lista vazia.

O encerramento retorna apenas `summary`, mantém o salvamento atômico e atualiza a primeira
página com os filtros atuais. O aviso explica que os filtros podem ocultar a tentativa salva.
Consultas obsoletas são canceladas e uma resposta atrasada não altera a consulta mais recente;
fechar o painel impede publicação tardia de dados. Erros do histórico ficam separados dos
erros de prática, com ação de tentar novamente.

A retenção de 5.000 registros e o limite de 8 MiB continuam existentes. O backend ainda lê
esse JSON limitado em cada consulta. A melhoria limita transferência e renderização;
não introduz banco indexado, migração dos arquivos ou promessa de tempo constante de leitura.
Nenhuma amostra de áudio é armazenada e nenhuma dependência foi acrescentada.

## Validação e limites

Os testes cobrem histórico vazio, 5.000 registros, reinício do leitor, ordenação, última página,
datas de limite, exercício/resultado, entradas inválidas, corrupção e autenticação HTTP.
No frontend, cobrem consultas atrasadas, cancelamento ao fechar, metadados inconsistentes,
erro recuperável sem bloqueio do exercício, filtros preservados no salvamento, período
invertido, datas impossíveis e inclusão do último dia local.

Validação desta etapa: 94 testes frontend e 174 Java, sem falhas; lint, tipos, formatação,
build de produção e build Gradle com warnings tratados como erros. As 586 dependências npm
travadas mantêm licenças e integridade verificadas. A conferência em navegador usa um perfil
isolado com 5.000 resumos sintéticos, janelas 1280×900 e 390×844, filtros combinados, última
página parcial, consulta vazia e uma tentativa de ritmo encerrada pelo fluxo real do aplicativo.
Os números nas capturas pertencem ao perfil de teste, não à prática do usuário.

A revisão final apontou perda de foco ao remover os botões durante a consulta. A correção
restaura o controle de navegação após renderizar a resposta, usa a posição da página quando
o botão está desabilitado no limite e oferece foco na ação de nova tentativa em caso de erro.
Uma ação do usuário em outro controle durante a espera conserva esse novo foco. Dois testes
de componente cobrem a continuidade de teclado, o limite e a recuperação, além da formatação
brasileira dos números.

No navegador, Enter sucessivo sem refocar avançou de 1 para 2 e 3 mantendo Próxima; no limite
de 4 páginas levou o foco à posição da página. Erro de leitura durante Anterior focou a ação
de recuperação; Enter após restaurar o arquivo recuperou a página e focou sua posição. Voltar
à primeira página também usou esse fallback. A janela compacta manteve Próxima focado após
avançar. A revisão de acabamento encerrou com **ship**, sem achados materiais pendentes.

O teste de recuperação tornou temporariamente ilegível apenas o arquivo sintético, exibiu
o erro e o botão de nova tentativa, conservou a ação de iniciar prática e recuperou 20 linhas
após restaurar o arquivo. Não houve transbordamento horizontal em nenhuma largura capturada.
O texto secundário observado (`#B0B2AD`) contra o fundo (`#111315`) tem contraste 8,705:1.
O detector não encontrou padrões nas folhas alteradas, usando fallback regex por ausência
dos parsers; esse modo não verifica propriedades calculadas. Contraste e largura foram
conferidos separadamente no DOM. Nenhum erro ou aviso de console surgiu na interface final.

O pacote Windows atualizado desta etapa fica em
`apps/desktop/build/native/practice-history-20261011/`. Inclui runtime Java, interface offline,
source.zip, avisos, instruções e checksum. A verificação específica do pacote é registrada
ao lado do ZIP em `build-verification.json`; a inicialização do EXE é conferida com outro
perfil isolado. O executável é portátil e não assinado. Extraia e mantenha toda a pasta ARPEON.

Microfone físico, sintetizador MIDI audível, precisão de reconhecimento Audiveris e comparação
de evolução musical não foram avaliados nesta etapa. A centralização das preferências de áudio
e a preparação/calibração do dispositivo de R11 permanecem para uma etapa posterior.

## Interface registrada

O histórico é uma extensão local da área Prática, em modo Operate: consultar até 5.000
resumos sem renderizar todos, restringir a consulta e reconhecer o contexto de cada tentativa.
A autoridade visual continua em [workstation-design.md](workstation-design.md); o produto
segue o [README](../../README.md) e [beginner-mode.md](beginner-mode.md). Este registro
descreve o código de practice-history, practice-session, practice-panel e styles.scss e as
capturas existentes. Não estabelece uma nova identidade global. Composição aprovada e
imagens de QUALITY BAR estão ausentes por decisão de escopo desta extensão.

A seção mantém a workstation Charcoal + Muted Pastel Gold: fundo carvão (#111315), texto
principal claro (#ECECE7), informação secundária (#B0B2AD) e dourado herdado (#D8BB78).
Inter Variable é carregada localmente; o histórico usa a fonte de interface, com algarismos
tabulares, título de 16px, rótulos e controles de 13px e notas, resumo, paginação e linhas de
12px. Superfícies são planas, sem sombra própria no histórico; separadores discretos
(#2B2E31) organizam a área e as tentativas. Campos e botões herdam fundo elevado (#24272B),
altura mínima de 32px e cantos de 6px. Hover e pressionado seguem os tokens globais;
controles desabilitados usam opacidade 0,45. O tema claro também é herdado dos tokens,
sem variante própria desta seção.

No desktop, a área secundária coloca a carta completa de digitação à esquerda e o
Histórico local à direita, em duas colunas iguais, com intervalo de 24px e separador
superior. Até 950px, as áreas de prática passam a uma coluna; o histórico vem depois da
carta, separado por uma linha e espaço de 24px. Seus quatro campos formam duas colunas
com intervalo de 12px e passam a uma coluna até 560px. Ações e paginação permitem quebra
de linha com intervalo de 8px; no compacto, Próxima pode ocupar a linha seguinte.
Campos ocupam a largura disponível, colunas podem encolher e textos das tentativas
podem quebrar. A consulta longa usa a rolagem da área existente.

O título é “Histórico local”, seguido de “Até 5.000 tentativas neste computador.
Nenhuma amostra de microfone é guardada.” O formulário contém:

- “Exercício no histórico”: Todos os exercícios, Reconhecer nota, Nota longa, Escala,
  Arpejo ou Ritmo básico.
- “Resultado”: Todos os resultados, Concluídas ou Interrompidas.
- “De” e “Até”: campos de data nativos. A apresentação e o calendário desses campos
  dependem do navegador; a captura mostra a convenção dia/mês/ano.
- “Filtrar histórico”, que aplica os campos e volta à primeira página, e “Limpar filtros”,
  que limpa os quatro valores e refaz a consulta sem apagar resumos.

A instrução explicita: “Período pela data de encerramento, no horário deste computador.
Inclui o dia final.” Os limites usam meia-noite local do primeiro dia e, como limite
exclusivo, meia-noite local do dia seguinte ao último, respeitando mudanças de fuso/horário
de verão. Datas em branco deixam o respectivo limite aberto. Período invertido produz
“A data inicial deve ser anterior ou igual à data final.”; data inválida produz
“Escolha datas válidas para o período.”, sem disparar a consulta inválida.

O resumo antecede a navegação e informa “N de T tentativas · C concluídas · I interrompidas”.
N, C e I abrangem toda a consulta filtrada; T é o total retido. São contagens de registros,
sem nota agregada, avaliação acústica ou evidência de evolução musical. A navegação vem
antes das linhas, com “Anterior”, “Página P de Q · início–fim de N” e “Próxima”. A interface
solicita 20 resumos por página e renderiza somente as entradas recebidas, com possibilidade
de última página parcial. Anterior fica desabilitado na primeira página e Próxima na última.
A ordenação recebida é do encerramento mais recente para o mais antigo.

As tentativas formam uma lista nomeada “Tentativas salvas”, sem ações individuais nas
linhas. Cada linha apresenta exercício e tonalidade em destaque, data/hora de encerramento
em `dd/MM/yyyy HH:mm` no horário local, “Concluída” ou “Interrompida”, quantidade alcançada
sobre quantidade alvo e andamento em BPM. Ritmo usa “pulsos”; os demais exercícios usam
“notas”. Os nomes das tonalidades conhecidas são Dó maior, Sol maior, Fá maior, Ré maior,
Lá menor, Mi menor e Ré menor; identificadores não mapeados mantêm seu valor original.
Nota longa acrescenta “ms sustentados”, interrupções e, quando disponível,
“Estabilidade RMS: … cents”. As contagens do resumo e os milissegundos sustentados usam
agrupamento brasileiro sem casas decimais; RMS usa uma casa decimal com vírgula, por
exemplo “3.000 ms sustentados” e “8,5 cents”. Esse tratamento `pt-BR` é explícito no
componente; os outros números das linhas e da posição da página são interpolados
diretamente. Os resumos permanecem em `~/.arpeon/practice-history.json`, sem amostras de
áudio. Paginação limita transferência e DOM; a leitura continua sendo do JSON local limitado.

Durante cada consulta, campos e ações do formulário ficam desabilitados e
“Carregando histórico…” substitui resumo, paginação e linhas anteriores. Uma falha mostra
a mensagem de erro e “Tentar carregar novamente”; a recuperação repete a página solicitada
com os filtros aplicados. O carregamento e o erro do histórico são independentes do estado
do exercício e não exigem gerar outro exercício para recuperar a consulta. Requisições
anteriores são abortadas e respostas obsoletas são descartadas. Após Encerrar e salvar,
a primeira página é consultada com os filtros atuais e aparece “Tentativa salva. Os filtros
atuais podem ocultá-la.”

Sem registros retidos, o vazio orienta: “Nenhuma tentativa salva. Gere um exercício e use
Encerrar e salvar após a tentativa.” Com registros, mas nenhum resultado correspondente,
a orientação é “Nenhuma tentativa corresponde aos filtros. Ajuste o período ou limpe os
filtros.” O resumo de contagens permanece nesses vazios; navegação e lista não aparecem.
Erro de leitura tem estado próprio e não é apresentado como histórico vazio.

O teclado segue os controles nativos e a ordem do documento: campos, ações e, quando
presentes, Anterior e Próxima. O formulário aceita submissão nativa; não há atalhos próprios
do histórico nem linhas artificialmente focáveis. O foco visível herda contorno dourado
de 2px, afastado 2px. A seção tem título associado e `aria-busy`; carregamento, resumo,
posição e confirmação de salvamento usam `role="status"`, enquanto erros usam
`role="alert"`. A navegação tem nome “Páginas do histórico de prática” e a data de cada
linha usa elemento `time` com o instante original em `datetime`.

A continuidade após paginação ou recuperação assíncrona depende de o botão acionado
estar focado antes da consulta. Depois da renderização, se o foco caiu no corpo ou na
raiz do documento, uma falha leva a Tentar carregar novamente; uma navegação bem-sucedida
devolve o foco ao mesmo botão enquanto ele estiver habilitado. No limite, usa a posição
da página; sem navegação disponível, usa o resumo de contagens. Uma recuperação
bem-sucedida também usa posição ou resumo. Esses dois destinos têm `tabindex="-1"`,
permitindo foco programático sem incluí-los no percurso normal de Tab. Se o usuário focou
outro controle durante a espera, esse foco é respeitado. Destruir o componente cancela
o callback de foco pendente; destruir a sessão aborta a consulta e impede atualização tardia.
O foco nativo de Filtrar histórico e Limpar filtros não recebe restauração especial.
Movimento reduzido e cores forçadas seguem as regras globais.

As evidências existentes são a [captura desktop, 1280×900](../../.impeccable/review/history-desktop.png),
a [captura compacta, 390×844](../../.impeccable/review/history-compact.png) e a
[captura compacta da primeira rodada](../../.impeccable/review/history-compact-round1.png).
A última registra o topo dos filtros antes do ajuste de formatação numérica brasileira;
a estrutura permaneceu igual. Todos os totais e medições nessas imagens pertencem a
fixtures sintéticas de QA em perfil isolado, não à prática real de uma pessoa.

A revisão de acabamento já concluída teve disposição **ship**, com o achado de continuidade
de teclado resolvido. Este acréscimo é documental e não executa nova validação. A ausência
de transbordamento foi registrada no DOM nas duas larguras revisadas; isso não certifica
todas as combinações de navegador, zoom ou dispositivo. A suavidade da captura desktop
limita julgamentos finos de rasterização, e o detector em fallback regex tem cobertura
degradada, sem conferir estilos calculados. Para comportamento e números finais, prevalece
o código atual; as imagens registram as vistas capturadas. Essas evidências não demonstram
desempenho humano, qualidade acústica nem certificação integral de acessibilidade.
