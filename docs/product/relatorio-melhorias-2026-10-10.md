# Relatório de melhorias do ARPEON

Data: 10 de outubro de 2026. Escopo: design, interface, layout, experiência de uso, funcionalidades, novas adições e qualidade técnica.

O ARPEON já possui uma identidade visual coerente e uma base musical relevante. A melhoria de maior impacto é organizar os recursos em uma jornada de estudo mais direta: abrir uma música, preparar o áudio, praticar um trecho, compreender o resultado e repetir com um objetivo claro.

## 1. Base da avaliação

A avaliação combinou leitura do código Angular e Java, documentação do produto e inspeção da aplicação em navegador com backend real, executado em um perfil local separado. Foram examinadas as cinco áreas: Partitura, Importar, Prática, Afinador e Análise.

Foram inspecionadas janelas de 1440 × 900, 1280 × 800 e 390 × 844 pixels, abertura da fixture Badinerie, geração de exercício e revisão da sequência `DO4 RE4 MI4 FA4`. O frontend foi recompilado com o Node 24 disponível no projeto; o build de produção passou. Não foram alterados os componentes da aplicação.

As conclusões distinguem três tipos de informação:

- **Observado:** comportamento ou composição conferidos no navegador.
- **Identificado no código:** conclusão apoiada na implementação; quando depende de hardware ou uso prolongado, isso é explicitado.
- **Proposta:** melhoria ou funcionalidade ainda a implementar, sem alegação de eficácia já medida.

Não foram feitos ensaios acústicos de microfone, sessões longas, reconhecimento OMR completo, auditoria com leitor de tela ou benchmark de grandes partituras. A janela de 390 pixels simula espaço reduzido no computador; não comprova acesso pelo celular. O servidor atual atende em `127.0.0.1`.

## 2. O que vale preservar

- **Identidade visual:** neutros escuros, dourado como destaque e papel de partitura independente do tema da interface.
- **Tipografia e tokens:** Inter e JetBrains Mono locais, cores semânticas, escala de espaçamento e estados de foco.
- **Estrutura de workstation:** menus, navegação entre áreas, explorador, inspetor ajustável e transporte persistente.
- **Controle do usuário:** desfazer/refazer, prévia de transposição, revisão de importações e preservação do arquivo original.
- **Funcionamento local:** projetos, histórico e processamento musical sem dependência de conta.
- **Transparência musical:** limites monofônicos e métricas separadas, sem uma nota geral que simplifique indevidamente o desempenho.

Esses recursos já existem. As propostas abaixo ampliam ou organizam essa base.

## 3. Prioridades recomendadas

P1 indica dificuldade importante a resolver antes de ampliar a distribuição; P2 indica evolução relevante; P3 indica complemento. P, M e G representam esforço relativo pequeno, médio e grande, sem promessa de prazo. Não foi confirmado bloqueio P0 nos fluxos examinados.

| ID | Melhoria | Prioridade | Esforço | Benefício principal |
| --- | --- | --- | --- | --- |
| R01 | Persistir preferências entre reinícios | P1 | M | Tema e layout continuam iguais ao reabrir |
| R02 | Reduzir a interface fixa em janelas estreitas | P1 | M | Mais espaço para pauta e ações principais |
| R03 | Tornar transporte e áudio contextuais | P1 | M–G | Fica claro o que será reproduzido ou capturado |
| R04 | Reforçar o contraste do cursor musical | P1 | P | Posição atual mais fácil de acompanhar |
| R05 | Simplificar preparação de prática e análise | P1 | M | Menos decisões antes de tocar |
| R06 | Explicar e permitir recuperar erros | P1 | M | O usuário sabe como continuar |
| R07 | Transformar análises em ações de estudo | P2 | M–G | Resultados orientam a próxima tentativa |
| R08 | Criar biblioteca e início mais úteis | P2 | M | Retomar músicas e exercícios com facilidade |
| R09 | Melhorar edição e linguagem musical | P2 | M | Menos interpretação de campos técnicos |
| R10 | Melhorar revisão e cancelamento de importação | P2 | M–G | Mais controle sobre operações demoradas |
| R11 | Paginar históricos e consolidar preferências de áudio | P2 | M | Interface cresce sem perder usabilidade |
| R12 | Alinhar documentação e mensagens ao comportamento | P2 | P–M | Expectativas correspondem ao produto |

## 4. Design e layout

### R02 — Dar mais espaço ao conteúdo musical

**Evidência observada:** em 1280 × 800, com os dois painéis abertos, a região central da partitura tem aproximadamente 684 pixels de largura, cerca de 53% da janela. Em 390 × 844, o transporte ocupa 135 pixels de altura. Menus, cabeçalhos, ações e navegação também consomem espaço; na prática, o botão de gerar exercício fica abaixo da primeira área visível. No afinador, o botão de ligar o microfone exige rolagem.

**Recomendação:**

- Criar um preset **Tocar**, que recolha os painéis e priorize pauta, andamento e controles essenciais em uma única ação.
- Manter um preset **Editar**, com inspetor aberto, e um preset **Estudar**, com digitação e orientações contextuais.
- Em espaço reduzido, reunir menus secundários em um botão e transformar o transporte em uma linha compacta com expansão opcional.
- Deixar a ação essencial do afinador próxima da leitura da nota.
- Adaptar a navegação lateral conforme a largura disponível, preservando rótulos acessíveis.
- Oferecer “Ajustar à largura” e “Ajustar à página”, além do zoom percentual existente.

Não é necessário reconstruir toda a aparência. A mudança principal é redistribuir espaço conforme a tarefa.

**Aceite:** em 390 × 844, é possível localizar e acionar a preparação principal sem procurar abaixo de várias seções; a pauta mantém rolagem própria quando necessária, e os controles não geram rolagem horizontal da página. Em desktop, o preset Tocar amplia significativamente a região musical e pode ser revertido por teclado.

**Localização:** [estrutura global](../../frontend/src/app/app.html), [estilos globais da aplicação](../../frontend/src/app/app.scss) e [layout da partitura](../../frontend/src/app/features/score/score-workspace.scss).

### R04 — Melhorar a leitura do cursor e da seleção

**Evidência no código:** o cursor usa `#AA8E54` sobre papel `#F5F2EA`, com alpha de desenho de 0,8. O contraste calculado é aproximadamente 2,80:1 sem transparência e 2,22:1 após a composição de 80% sobre o papel. Isso fica abaixo da referência de 3:1 para informação gráfica necessária à compreensão. A avaliação completa deve incluir os demais indicadores de posição. [W3C: contraste não textual](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).

**Recomendação:** separar o token de cursor para papel claro e escuro, escurecer o indicador no papel claro e acrescentar um marcador de posição ou destaque da nota. Diferenciar visualmente a nota selecionada para edição da posição que está sendo reproduzida.

**Aceite:** cursor perceptível nos dois tipos de papel; contraste adequado contra o fundo; seleção e reprodução identificáveis sem depender apenas da cor. Preservar a sincronização e a compensação de latência já implementadas.

**Localização:** [tokens](../../frontend/src/styles/styles.scss), [aparência da notação](../../frontend/src/app/shared/score-appearance.ts) e [cursor de reprodução](../../frontend/src/app/shared/playback-cursor.ts).

### Refinamentos visuais complementares

1. **Hierarquia das ações:** destacar uma ação principal por etapa. Abrir, preparar, iniciar, concluir e repetir devem assumir esse papel conforme o estado.
2. **Densidade configurável:** manter controles compactos para desktop e oferecer uma opção confortável com texto maior e áreas de toque mais amplas.
3. **Informação técnica recolhível:** manter hashes, latências detalhadas e dados de origem acessíveis em “Detalhes”, dando prioridade à orientação musical.
4. **Estados vazios úteis:** aproveitar o espaço para demonstrar a próxima ação. A tela inicial já tem três caminhos; falta um exemplo que permita experimentar sem procurar um arquivo.
5. **Feedback visual discreto:** indicar conclusão de importação, salvamento e sessão com mensagens claras e breves, mantendo erros disponíveis até a recuperação.

Em dispositivos de toque, recomendar controles principais próximos de 44 × 44 pixels. Isso é uma meta de conforto; a WCAG 2.2 AA define mínimo de 24 × 24 pixels, com exceções de espaçamento e equivalência. Os controles atuais de 32 pixels não devem ser classificados automaticamente como violação. [W3C: tamanho mínimo do alvo](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html).

## 5. Interface e experiência de uso

### R01 — Fazer as preferências sobreviverem ao reinício

**Evidência no código:** `Appearance` e `WorkspaceLayout` guardam tema, zoom e painéis em `localStorage`. O `DesktopServer` solicita uma porta dinâmica ao sistema. Quando a porta muda, muda também a origem usada pelo armazenamento do navegador. Portanto, as preferências existentes não são recuperadas nessa nova origem. Essa conclusão decorre da implementação; não foi realizado um ensaio com duas inicializações completas. [MDN: armazenamento por origem](https://developer.mozilla.org/en-US/docs/Web/API/Window/localStorage).

**Recomendação:** persistir preferências não sensíveis no backend local, em um arquivo versionado do perfil, e restaurá-las no bootstrap. O armazenamento do navegador pode continuar como cache. Incluir tema, densidade, zoom, painéis e configurações de áudio apropriadas. Consentimento e captura de microfone devem continuar dependendo da ação do usuário.

**Aceite:** escolher tema claro, ajustar zoom e painéis, fechar e reabrir em outra porta mantém as preferências. Arquivo inválido retorna aos padrões sem impedir a abertura.

**Localização:** [tema](../../frontend/src/app/shared/appearance.ts), [preferências de layout](../../frontend/src/app/shared/workspace-layout.ts) e [servidor local](../../apps/desktop/src/main/java/org/arpeon/desktop/DesktopServer.java).

### R03 — Mostrar claramente qual áudio será controlado

**Evidência observada:** ao entrar em Prática, Afinador ou Análise com uma partitura aberta, o transporte global continua exibindo a partitura. A prática gerada possui seus próprios botões “Ouvir referência”, “Pausar” e “Parar áudio”. No código, partitura e exercício mantêm players próprios. Não foi testada reprodução simultânea.

**Recomendação:** criar uma coordenação de áudio que identifique a fonte ativa: **Partitura**, **Exercício** ou **Tom de referência**. O transporte deve controlar essa fonte e exibir seu nome. Oferecer uma ação explícita para voltar à partitura. Centralizar também o estado de captura: microfone desligado, pronto, escutando, sem sinal ou com erro.

Antes de iniciar avaliação por microfone, orientar sobre o uso de fones quando houver referência sonora, pois a saída pode interferir na captura. Determinar uma política de prioridade entre players para impedir ativações acidentais concorrentes.

**Aceite:** o usuário identifica a fonte antes de pressionar Play; andamento e controles correspondem a ela; parar e trocar de área liberam os recursos conforme a política definida.

**Localização:** [transporte](../../frontend/src/app/features/transport/transport-bar.html), [prática](../../frontend/src/app/features/practice/practice-panel.html), [player da prática](../../apps/desktop/src/main/java/org/arpeon/desktop/PracticeWorkspace.java) e [workspace da partitura](../../apps/desktop/src/main/java/org/arpeon/desktop/ScoreWorkspace.java).

### R05 — Reduzir decisões antes da primeira tentativa

**Implementação:** a [etapa 9](melhorias-etapa-9-2026-10-11.md) reúne oitava e repetição
em ajustes avançados da Prática, e tolerâncias e latência nos da Análise. A configuração
inicial pode ser usada sem interpretar critérios técnicos. Campos inválidos explicam como
corrigir antes de iniciar; a restauração da Análise mantém parte e áudio. As escolhas são
conservadas ao recolher ajustes e trocar de área na janela aberta; não entre reinícios.

**Evidência observada:** a prática apresenta tipo, tonalidade, oitava, andamento, repetição e incremento de BPM antes de gerar o exercício. No modo “Analisar tentativa”, há nove campos de preparação, incluindo janela de alinhamento, tolerâncias e compensação de latência.

**Recomendação:** apresentar primeiro objetivo, parte musical e entrada de áudio. Recolher tolerâncias e parâmetros de processamento em **Ajustes avançados**. Oferecer configurações iniciais explicadas e editáveis, sem apresentar tolerâncias como avaliação de proficiência.

Para a prática, usar uma sequência curta: **Escolher exercício → Ouvir → Preparar microfone → Tocar → Rever resultado**. O fluxo deve ser opcional para quem já conhece o aplicativo.

**Aceite:** um usuário consegue começar com a configuração inicial sem interpretar milissegundos ou cents; os ajustes avançados continuam disponíveis e mantêm seus valores ao alternar de área.

**Localização:** [preparação da prática](../../frontend/src/app/features/practice/practice-panel.html) e [preparação da análise](../../frontend/src/app/features/analysis/analysis-panel.html).

### R06 — Explicar o erro e oferecer a próxima ação

**Evidência no código:** parte das mensagens de backend está em inglês e pode ser apresentada diretamente pela interface. Há casos de fila de autosave cheia e falha ao salvar projeto. Falha no polling do transporte remove seu estado; a interface orienta reiniciar o ARPEON. Várias ações ficam desabilitadas sem uma explicação próxima.

**Recomendação:** padronizar códigos de erro e mensagens em português, indicar o que aconteceu e oferecer uma ação de recuperação. Exemplos:

- “Não foi possível salvar neste computador. Suas alterações continuam abertas. Tentar novamente.”
- “A conexão com o aplicativo foi interrompida. Reconectar.”
- “Esta ação exige uma pausa selecionada. Selecione uma pausa na pauta.”

Adicionar reconexão manual e distinguir servidor indisponível de sessão expirada. Se a nova instância exigir outra janela autorizada, explicar esse caminho. Em falha de salvamento, permitir exportação para proteger o trabalho quando possível.

**Aceite:** nenhuma falha prevista exige interpretar uma mensagem técnica em inglês; valores e trabalho permanecem disponíveis; recuperação não liga o microfone nem reinicia reprodução automaticamente.

**Localização:** [sessão de partitura](../../frontend/src/app/features/score/score-session.ts), [transporte](../../frontend/src/app/features/transport/transport-bar.html) e [respostas do servidor](../../apps/desktop/src/main/java/org/arpeon/desktop/DesktopServer.java).

### R08 — Melhorar o início e a biblioteca de projetos

**Evidência observada:** projetos ficam em um seletor, acompanhado de Abrir e Excluir. Não há nessa interface busca, ordenação por uso recente, favoritos ou renomeação. No estado vazio, aparecem controles indisponíveis para uma biblioteca ainda sem conteúdo.

**Recomendação:** introduzir uma biblioteca em lista com título, instrumento, última abertura e última alteração. Exibir projetos recentes na entrada do aplicativo, com busca e favoritos. Acrescentar renomear, duplicar e abrir um exemplo local com origem/licença documentadas. Agrupar exclusão entre ações secundárias, identificando o projeto na confirmação.

**Aceite:** localizar e abrir uma música conhecida não exige percorrer um seletor extenso; novos usuários podem experimentar uma partitura pronta; o nome do projeto a excluir fica explícito.

**Localização:** [explorador da partitura](../../frontend/src/app/features/score/score-workspace.html) e [persistência de projetos](../../apps/desktop/src/main/java/org/arpeon/desktop/ProjectStore.java).

## 6. Melhorias das funções existentes

### R07 — Transformar números em orientação de estudo

**Evidência no código:** os resultados apresentam percentuais, números MIDI, índices de notas e tabelas. Os percentuais consideram notas correspondidas; essa explicação aparece em um texto posterior. O acompanhamento já dispõe de conversão de MIDI para nomes musicais, mas as tabelas de relatório exibem os números.

**Recomendação:**

- Mostrar a cobertura da tentativa junto dos percentuais: “32 de 40 notas correspondidas”. Um percentual alto com cobertura baixa precisa ser contextualizado.
- Exibir nota e oitava, como “Dó 5”, deixando MIDI em detalhes.
- Apresentar os trechos com maior dificuldade por compasso e parte.
- Permitir clicar em um resultado para selecionar a nota ou o trecho na pauta.
- Acrescentar “Praticar este trecho novamente”, com andamento ajustável.
- Explicar pouca confiança de captura antes de interpretar o resultado como erro do músico.
- Mostrar tendências entre tentativas compatíveis, identificando diferenças de revisão, andamento e tolerâncias.

A primeira versão pode usar regras transparentes, por exemplo “Neste trecho, as entradas ficaram frequentemente atrasadas”. Recomendações devem informar sua base e preservar as métricas independentes.

**Aceite:** ao terminar uma tentativa, o usuário consegue escolher um trecho e uma ação de estudo. Os relatórios não escondem notas perdidas, dados insuficientes ou baixa confiança. Comparações não misturam critérios diferentes silenciosamente.

**Localização:** [resultado da análise](../../frontend/src/app/features/analysis/analysis-panel.html), [modelo da análise](../../frontend/src/app/features/analysis/analysis-panel.ts) e [interpretação das métricas](../analysis/scoring.md).

### R09 — Usar linguagem musical na edição

**Evidência no código:** a duração é editada por numerador e denominador. A sequência de estudo também mostra posição e duração em frações. A edição já permite seleção na pauta, alteração de altura, transformação e consulta de digitação.

**Recomendação:** oferecer um seletor com figuras rítmicas e nomes — semínima, colcheia, mínima — mantendo a fração no modo avançado. Separar identificação da nota, figura e posição no compasso. Disponibilizar preferência entre Dó/Ré/Mi e C/D/E, com oitava explícita.

Permitir ouvir a nota selecionada, respeitando a coordenação de áudio. Depois, ampliar seleção múltipla e edição de trechos com prévia e desfazer. Na flauta, aproximar a digitação da nota selecionada em um painel de estudo, preservando acesso à carta completa.

**Aceite:** uma edição comum não exige conhecer o denominador interno; a figura escolhida corresponde à duração musical; alterações que ultrapassem o compasso recebem explicação antes da aplicação.

**Localização:** [editor](../../frontend/src/app/features/score/score-workspace.html), [seleção e edição](../../frontend/src/app/features/score/score-workspace.ts) e [digitação](../../frontend/src/app/features/fingering/flute-fingering.ts).

### R10 — Dar controle sobre importações e reconhecimento

**Evidência observada e no código:** a importação já separa origem e revisão. A prévia de notas coladas explica a ausência de ritmo e pede aceite das hipóteses. No OMR, a imagem pode ser exibida, enquanto o PDF original abre em outra aba; o reconhecimento é listado por compasso. Não há botão de cancelamento equivalente ao da abertura de MusicXML nos fluxos de prévia examinados.

**Recomendação:**

- Unificar a entrada por arquivo, identificando o formato e encaminhando ao fluxo adequado.
- Mostrar previamente requisitos e limites relevantes ao arquivo escolhido.
- Oferecer progresso por fase: leitura, reconhecimento, preparação e revisão.
- Acrescentar cancelamento cooperativo, incluindo o trabalho no backend. Abortar a requisição no navegador, sozinho, não comprova que o processamento foi encerrado.
- Evoluir o OMR para comparação lado a lado entre original e notação reconhecida, com navegação sincronizada por página ou compasso.
- Separar informação preservada da origem, informação estimada e hipótese escolhida pelo usuário.

**Aceite:** cancelar libera o trabalho correspondente; a operação anterior não sobrescreve uma nova; o usuário consegue verificar o reconhecimento sem perder sua posição. Confiança por nota só deve ser mostrada se houver dado real para sustentá-la.

**Localização:** [interface de importação](../../frontend/src/app/features/import/import-panel.html), [operações de importação](../../frontend/src/app/features/import/import-panel.ts) e [reconhecimento local](../../apps/desktop/src/main/java/org/arpeon/desktop/OmrWorkspace.java).

### R11 — Preparar histórico e áudio para uso frequente

**Implementação:** histórico paginado e filtrado na etapa 7; preparação de áudio compartilhada,
persistência local, identidade de entrada estável, recuperação de entrada ausente e teste de
referência na [etapa 8](melhorias-etapa-8-2026-10-11.md). A referência calibra a medição; não
retuna o sintetizador MIDI nem substitui a calibração física de latência.

**Evidência no código:** a persistência de prática permite até 5000 entradas, enquanto a interface informa até 50 e renderiza a coleção recebida. O endpoint retorna o histórico completo. Esse é um risco de volume identificado, sem benchmark de degradação realizado. Entrada e referência de áudio também são configuradas separadamente em diferentes áreas.

**Recomendação:** paginar o histórico desde a API, permitir filtros por exercício e data e exibir resumos de evolução. Centralizar preferências de microfone e referência de Lá, com validação da disponibilidade do dispositivo. Acrescentar um assistente de preparação de áudio e um teste de nota de referência.

**Aceite:** histórico próximo do limite não exige renderizar milhares de linhas; trocar de área conserva as preferências relevantes; dispositivo ausente gera um caminho claro para selecionar outra entrada. A captura permanece desligada até a autorização apropriada.

**Localização:** [histórico da prática](../../frontend/src/app/features/practice/practice-panel.html), [sessão de prática](../../frontend/src/app/features/practice/practice-session.ts), [retenção](../../apps/desktop/src/main/java/org/arpeon/desktop/PracticeHistory.java) e [configuração da análise](../../frontend/src/app/features/analysis/analysis-panel.ts).

### R12 — Fazer documentação e mensagens corresponderem ao produto

**Evidências:**

- O README informa que uma partitura recém-aberta permanece sem projeto salvo até o comando explícito. A implementação agenda autosave ao concluir a abertura; no teste, Badinerie entrou na lista de projetos automaticamente.
- A interface da prática informa até 50 tentativas; o armazenamento permite 5000.
- Metadados e termos internos são apresentados com pouca tradução para o contexto musical, como números MIDI e timestamps sem formatação amigável.

**Recomendação:** decidir e documentar a política de criação automática de projetos; explicá-la na primeira utilização. Usar limites de produto compartilhados com a interface e revisar textos após mudanças funcionais. Formatar datas conforme localidade, mantendo o instante original nos dados.

**Aceite:** instruções, estados de salvamento e limites descrevem o mesmo comportamento; “salvo” corresponde à confirmação de persistência; diferenças entre salvar projeto e exportar MusicXML ficam compreensíveis.

**Localização:** [README](../../README.md), [sessão de partitura](../../frontend/src/app/features/score/score-session.ts), [plano anterior](improvement-plan.md) e [histórico de prática](../../frontend/src/app/features/practice/practice-panel.html).

## 7. Novas funcionalidades e adições

| Proposta | Entrega inicial sugerida | Prioridade | Esforço |
| --- | --- | --- | --- |
| **Loop A–B da partitura** | Selecionar compassos inicial e final, repetir o trecho e mostrar a passagem atual | P2 | G |
| **Prática a partir de um trecho** | Criar tentativa da parte e dos compassos selecionados, preservando vínculo com a revisão | P2 | G |
| **Rotinas de estudo** | Sequência local de nota longa, escala e trecho, com duração escolhida pelo usuário | P2 | M |
| **Marcadores e anotações** | Marcar compassos, adicionar observações e listar trechos a revisar | P2 | M |
| **Comparação de tentativas** | Comparar cobertura, afinação e tempo apenas entre execuções compatíveis | P2 | M–G |
| **Biblioteca de exercícios** | Salvar configurações favoritas e exemplos com origem documentada | P2 | M |
| **Paleta de comandos** | Buscar ações e atalhos sem percorrer os menus | P3 | M |
| **Exportação de relatório** | Resumo em formato compartilhável com métricas, cobertura e condições da tentativa | P3 | M |
| **Pacote de projeto portátil** | Exportar/importar projeto versionado com partitura e metadados selecionados | P3 | G |
| **Impressão e PDF da partitura** | Prévia de impressão com partes, escala e quebras de página revisáveis | P3 | M–G |
| **Outros instrumentos** | Ampliar exercícios e guias usando os perfis já existentes | P3 | G |
| **Controle por pedal ou MIDI** | Mapear avançar página, iniciar/parar e repetir trecho | P3 | G |

O loop deve distinguir compasso escrito de ocorrência na execução, pois repetições já produzem uma ordem própria. A prática por trecho deve preservar transposição, andamento e vínculo com a revisão; copiar apenas a sequência de nomes de notas não é suficiente.

O BPM progressivo já existe na reprodução dos exercícios. A evolução sugerida é aplicá-lo a trechos da partitura e tornar sua relação com a avaliação explícita. Acompanhamento ao vivo, OMR, histórico e projetos locais também já estão presentes.

Ampliar polifonia na análise ou adicionar um modelo de ML ao produto são trabalhos separados de pesquisa e engenharia. O benchmark existente não sustenta integrar o modelo experimental atual. Essas mudanças precisam demonstrar vantagem em corpus independente e situações reais antes de assumir prioridade sobre a jornada de prática.

## 8. Qualidade técnica, acessibilidade e desempenho

### Acessibilidade

A base já contém link para pular ao conteúdo, foco visível, labels, estados ARIA, alternativa de seleção de notas por teclado e preferências de movimento reduzido. As próximas verificações devem cobrir:

- Todo o fluxo Abrir → Selecionar → Editar → Salvar pelo teclado.
- Refluxo em janelas estreitas e zoom elevado, preservando rolagem independente da notação quando sua natureza bidimensional exigir.
- Anúncios de leitor de tela com frequência controlada. Algumas regiões ao vivo contêm dados atualizados pelo polling; isso merece teste específico para evitar anúncios repetidos.
- Rótulos e explicações para ações indisponíveis.
- Contraste do cursor e demais indicadores musicais nos dois tipos de papel.

A notação pode exigir apresentação bidimensional; isso não dispensa o restante da interface de adaptar seus controles e textos. [W3C: refluxo](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html).

### Desempenho

O build validado contém aproximadamente 351 kB iniciais e um chunk de notação carregado sob demanda de 1,39 MB, em tamanho bruto. As estimativas de transferência informadas pelo build foram 87 kB e 296 kB; elas não equivalem a tráfego medido do servidor local.

O carregamento tardio do renderer já é uma boa decisão. Priorizar medições antes de mudar a arquitetura:

1. Tempo de abrir, renderizar e trocar de área com partituras pequenas, médias e próximas dos limites.
2. Memória após renderizações, mudanças de zoom e alternância entre áreas.
3. Custo de históricos extensos e tabelas de relatório.
4. Resposta a cancelamento, falha de dispositivo e salvamento lento.

Se as medições mostrarem necessidade, aplicar paginação ou virtualização e consolidar a configuração do renderer em uma camada compartilhada. Cache deve ser limitado e invalidado por revisão e aparência.

### Confiabilidade e validação musical

- Separar o estado de salvamento em segundo plano do bloqueio de operações que realmente exigem exclusividade; hoje o autosave também usa o estado global de ocupado.
- Manter dados insuficientes e confiança baixa visíveis nos resultados.
- Ampliar testes de ataques rápidos, notas repetidas e desconexão de dispositivos em hardware real.
- Distinguir a latência estimada do sintetizador da calibração da captura utilizada na análise.
- Criar diagnóstico local exportável por ação do usuário, com versões e etapas, para investigar falhas reproduzíveis.

## 9. Sequência de implementação

### Etapa 1 — Resolver atritos básicos

R01, R02, R04, R06 e R12: preferências duráveis, interface compacta, cursor legível, recuperação de erros e mensagens coerentes. Incluir as primeiras verificações de teclado e contraste.

### Etapa 2 — Organizar a experiência de tocar

R03 e R05: coordenação de áudio, preparação simplificada, estados consistentes e presets Tocar/Editar/Estudar. Essa etapa estabelece a base das próximas funções.

### Etapa 3 — Melhorar estudo e continuidade

R07, R08, R09 e R11: biblioteca, edição musical, resultados acionáveis e históricos paginados. Acrescentar rotinas e exercícios favoritos.

### Etapa 4 — Ampliar recursos musicais

Loop A–B, tentativa por trecho, comparação de execuções e revisão visual de OMR. Planejar mudanças no domínio e nas APIs antes de desenhar apenas os controles.

### Etapa 5 — Complementos

Exportações, pacote portátil, paleta de comandos, outros instrumentos e controle externo, conforme a demanda observada e os resultados das etapas anteriores.

## 10. Critérios de sucesso

As metas abaixo são propostas para testes de aceitação, não resultados medidos nesta avaliação:

| Objetivo | Como verificar |
| --- | --- |
| Começar a estudar com menos atrito | Observar tempo e hesitações entre abrir uma música e iniciar a primeira tentativa |
| Compreender o áudio ativo | Usuário identifica a fonte antes de pressionar Play e consegue parar a fonte correta |
| Usar espaço reduzido | Ações essenciais acessíveis em 390 × 844 e 1280 × 800; ausência de overflow global |
| Recuperar preferências | Tema, zoom e painéis mantidos após reinício com mudança de porta |
| Entender o resultado | Usuário identifica cobertura, uma dificuldade e o próximo trecho de estudo |
| Recuperar falhas | Trabalho preservado e caminho claro após falha de salvamento ou conexão |
| Usar teclado | Fluxo principal concluído sem depender de clique na notação |
| Crescer com o histórico | Paginação evita renderizar toda a retenção disponível de uma vez |

Testar pelo menos três situações de uso: iniciante sem arquivo próprio, músico que já conhece a partitura e pessoa que navega por teclado ou usa ampliação de tela. Registrar conclusão, erros, hesitações e entendimento, além das medidas de tempo.

## 11. Notas da revisão

- Revisão realizada em um único contexto, com inspeção manual e do código; não houve avaliações independentes de subagentes.
- O detector da skill impeccable funcionou apenas em fallback por regex, pois os módulos de parsing estavam indisponíveis. Não calculou contraste nem avaliou seletores completos.
- O único alerta retornado apontou a imagem com binding Angular `[src]` na importação. Foi descartado como falso positivo: a URL é fornecida dinamicamente e a exibição depende de sua disponibilidade.
- Os valores de contraste deste relatório foram calculados separadamente a partir dos tokens, sem alegação de auditoria integral WCAG.
- Houve resposta 409 ao consultar prática antes de existir exercício; a interface apresentou o estado vazio e a geração subsequente funcionou. Esse registro isolado não foi classificado como bloqueio funcional.
- O primeiro comando de build encontrou Node 20 no PATH. A compilação foi repetida com o Node 24 local e concluída com sucesso.
- Capturas de apoio estão em `.tools/report-review/`, incluindo início, partitura, prática, análise, afinador, importação e versões compactas. Essa pasta é local e ignorada pelo Git; o relatório permanece compreensível sem as imagens.
- As alterações preexistentes no repositório foram preservadas. Este trabalho acrescenta o relatório, sem implementar as propostas.

Para execução posterior com a skill impeccable, a sequência sugerida é `shape`, `adapt`, `clarify`, `harden`, `optimize` conforme os achados medidos, `audit` e, ao final, `polish`.
