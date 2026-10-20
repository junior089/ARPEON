# ARPEON — workstation musical

## Direção
Modo Operate. A partitura é o documento central, o transporte permanece acessível e o inspetor acompanha a seleção. A direção está fixada pelo pedido: linguagem de software desktop técnico com identidade cromática preservada. Nenhum catálogo visual substitui essa restrição.

## Fundação
Tokens em `frontend/src/styles/styles.scss`: fundo #0B0B0D, lateral #141416, superfície #1A1A1F, controles #26262B e dourado #E6C989. A orientação complementar do usuário aprova o kit Ouro e Carvão como referência principal. Tema claro com dourado #806326. Inter local para interface, JetBrains Mono para medidas e identificadores. Controles de 30–32px, cantos de 3–4px; regiões separadas por linhas. O play principal é circular, conforme a referência funcional do player.

## Responsabilidades
- Menu: comandos globais, teclado, preferências e distribuição.
- Navegação lateral: Biblioteca, Partituras, Reprodução, Edição, Ferramentas, Aprendizado e Análise. Reprodução e Edição abrem contextos do mesmo documento. Importar e Preferências têm entradas próprias; nenhum item é demonstrativo.
- Biblioteca: projetos salvos, busca, ordenação, renomeação, duplicação e exclusão. Explorador contextual: fonte, salvamento e partes; a biblioteca não é duplicada ali.
- Notação: leitura, seleção, nomes das notas e resultados visuais.
- Inspetor: leitura da seleção, digitação, símbolos e propriedades; transposição por seção recolhível.
- Transporte: reprodução, tempo, metrônomo e trecho A–B.
- Status: documento e persistência; dados legais e versão em Ajuda.
- Perfis: troca pelo nome do perfil ativo na barra superior e pela categoria funcional Conta e perfis em Preferências; ambas abrem o seletor local.

## Estados e precisão

A Biblioteca apresenta “Origem inicial” como resumo somente de leitura dentro das ações do projeto selecionado, separado por linha e integrado aos tokens, à tipografia e aos controles compactos do modo Operate nos temas escuro e claro. A lista de definições organiza rótulos e valores em duas colunas; até 480px passa a uma coluna, com quebra de identificadores longos. Obra e compositor são declarados, a edição ausente aparece como “Não identificada” e os direitos permanecem “não avaliados”. “Baixar original” aparece somente quando o arquivo original foi preservado; o download verifica tamanho e SHA-256. Identidades locais e hashes da entrada e da versão salva ficam no elemento nativo recolhível “Identificadores e integridade”, com valores monoespaçados. Projetos legados exibem um aviso sobre a ausência de origem preservada e a impossibilidade de reconstruir os bytes originais a partir do MusicXML editável; o resumo distingue a entrada inicial das edições e partes acrescentadas ao projeto.

“Identificação bibliográfica” aparece junto à origem, entre separadores planos, como declaração local independente da edição registrada na importação. Identificar ou corrigir exige título e citação/evidência; retirar exige um motivo. A URL opcional aceita HTTPS sem credenciais, porta, consulta ou fragmento e aparece como texto monoespaçado, sem acesso ou verificação pelo aplicativo. Cada confirmação acrescenta um registro ao “Histórico bibliográfico” nativo recolhível, com data, citação e referência; correções e retiradas preservam os registros anteriores. Ao atingir 16 registros, novas inclusões ficam desabilitadas e o histórico permanece disponível. Origem inicial, original, notas e avaliação de direitos permanecem independentes da declaração. O formulário usa uma coluna de até 680px, campos que acompanham a largura disponível e ações com quebra de linha, preservando os temas e controles existentes.

Os rascunhos de identificação ficam em memória por projeto ao navegar ou recolher suas ações. Falhas preservam os campos e oferecem “Recarregar histórico”; cancelar limpa o rascunho e o erro. A troca de perfil com identificação pendente apresenta aviso e a ação “Revisar identificação”, que abre a Biblioteca no projeto correspondente, limpando o filtro e localizando sua página. Guardar ou cancelar o último rascunho remove o aviso; o fechamento usa a confirmação existente de alterações não salvas. Rascunhos não sobrevivem ao encerramento do processo.

Em “Abrir e importar”, as abas Arquivo, Colar notas e Wikimedia usam linhas e acento na opção ativa. “Fontes disponíveis” lista as capacidades consultadas nesta versão, com estado de consulta, erro e ação para tentar novamente. Somente Wikimedia Commons oferece MIDI pela URL de uma página File:, mediante contato de identificação enviado à API e não salvo pelo ARPEON; o envio depende da capacidade confirmada. As demais alternativas permanecem manuais, por arquivo exportado ou notas coladas, sem busca federada, seleção de partes ou conexão de conta externa. Rótulos, descrições e campos seguem a coluna compacta existente e permitem quebra de texto em janelas estreitas.

A central de Preferências usa pesquisa textual, navegação por categorias e linhas compactas de controles. A pesquisa ignora maiúsculas e acentos, combina todos os termos e exibe as categorias correspondentes; selecionar uma categoria limpa a pesquisa. Interface e acessibilidade, Partituras e estudo, Áudio e dispositivos e Atualizações correspondem a capacidades existentes. Os controles compartilham estado com editor e ferramentas; as restaurações de interface/layout e leitura/papel atuam somente na categoria indicada. Até 700px, a navegação passa da coluna lateral para botões em linhas e os controles permitem quebra de linha. Tema, tokens, navegação por teclado e diálogo nativo seguem o restante do aplicativo. Categorias futuras só aparecem com operações funcionais.

O seletor local apresenta “Quem vai estudar?” junto ao símbolo existente, com escolhas simples de perfil: avatar de iniciais e cor, nome e instrumento principal. Criar e editar abrem um editor adjacente à direita no desktop; até 800px ele passa para baixo das escolhas, separado por linha. A página permite rolagem vertical para manter o editor e as opções de conta acessíveis em janelas baixas. Nome da conta e consentimento para iniciar automaticamente no último perfil ficam em uma seção própria, separada da seleção e edição dos perfis. A composição preserva carvão, dourado, tipografia local e controles compactos do modo Operate.

Progresso indeterminado quando não há medição. Toda prévia usa MusicXML produzido pelos mesmos exportadores do documento aceito. Anotações pertencem ao SVG de apresentação, nunca ao MusicXML exportado. A grafia vem da altura estruturada, incluindo enarmonia; som real usa o perfil instrumental. Sem coordenadas OMR, a comparação é independente por página, sem alinhamento automático alegado.

## Interação
Foco visível, menus por setas/Escape, divisão ajustável por teclado e ponteiro, drag-and-drop validado, avisos recuperáveis. Tocar recolhe ferramentas; Editar abre propriedades; Estudar abre assistência contextual. Janelas menores recolhem áreas secundárias e mantêm controles acessíveis.

## Ativos
`frontend/public/brand/` conserva a geometria original do kit fornecido. Assinaturas clara e escura acompanham o tema. `packaging/arpeon.ico` usa o ICO fornecido; a janela Java usa `brand/arpeon-256.png`. Os mockups e textos do kit são referências visuais, não capacidades do produto. Os avatares dos perfis locais usam iniciais em uma superfície compacta de cantos suaves, com cor escolhida entre dourado, verde, azul e rosa; não dependem de imagens geradas. Não há busca global fictícia ou slider de volume sem mecanismo real.

## Instalação e manutenção

- Setup WPF próprio, em oito etapas, com uma tela de decisão para pasta e atalhos; MSI nativo preservado como autoridade transacional, inclusive na reparação, atualização e remoção.
- Janela Ouro e Carvão (#E6C989 / #101214), painel de marca #191C1F, símbolo vetorial original e Segoe UI nativa. Identidade à esquerda, operação à direita; esta composição pertence ao instalador, sem substituir o sistema visual do aplicativo.
- Ondas Bézier com pontos de controle animados, barras harmônicas nas verificações, anéis na preparação e na conclusão bem-sucedida; mensagens contextuais rotativas distinguem instalação, atualização, reparação e remoção. Movimento decorativo respeita a preferência de animação do Windows; texto e estado continuam disponíveis sem animação.
- A manutenção e o desinstalador usam a mesma interface própria, com confirmação da remoção, foco visível, nomes acessíveis e DPI PerMonitorV2. Acessibilidade implementada não equivale a homologação com leitor de tela ou monitores físicos.
- Progresso do instalador medido pelo MSI; sem percentuais fictícios ou conclusão antecipada.
- Extração por bytes e SHA-256 antes da instalação; validação dos arquivos, registro e destinos dos atalhos antes de confirmar sucesso. Percentual por fase do MSI; fase estimada sem percentual, cancelamento confirmado e retorno nativo aguardado.
- Arquitetura, estados e roteiro de validação em `docs/development/installer-design.md`.
- Na mesma versão instalada, oferecer abrir, reparar ou desinstalar com confirmação; preservar projetos e preferências.
- Aplicativos do Windows abre a manutenção própria por uma entrada HKCU com `WindowsInstaller=0`; somente a entrada nativa do MSI fica oculta. Cache dedicado conserva o MSI completo para reparação offline e um executável pequeno de manutenção. A remoção usa o produto registrado e limpa apenas cache e registro próprios após confirmação nativa; o host temporário pode permanecer para reutilização ou limpeza do Windows.
- Manutenção no frontend acessível por Preferências, seguindo a tipografia, os tokens e o comportamento responsivo existentes.
- Verificação diária somente por adesão explícita; download e instalação por escolha do usuário. Feed inativo deve ser informado com clareza e desabilitar a verificação.
- Exibir estados reais de consulta e atualização; erros de operação ou preferência ficam separados do resultado atual, sem encobri-lo.
