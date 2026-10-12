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

## Estados e precisão
Progresso indeterminado quando não há medição. Toda prévia usa MusicXML produzido pelos mesmos exportadores do documento aceito. Anotações pertencem ao SVG de apresentação, nunca ao MusicXML exportado. A grafia vem da altura estruturada, incluindo enarmonia; som real usa o perfil instrumental. Sem coordenadas OMR, a comparação é independente por página, sem alinhamento automático alegado.

## Interação
Foco visível, menus por setas/Escape, divisão ajustável por teclado e ponteiro, drag-and-drop validado, avisos recuperáveis. Tocar recolhe ferramentas; Editar abre propriedades; Estudar abre assistência contextual. Janelas menores recolhem áreas secundárias e mantêm controles acessíveis.

## Ativos
`frontend/public/brand/` conserva a geometria original do kit fornecido. Assinaturas clara e escura acompanham o tema. `packaging/arpeon.ico` usa o ICO fornecido; a janela Java usa `brand/arpeon-256.png`. Os mockups e textos do kit são referências visuais, não capacidades do produto. Não há avatar, busca global fictícia ou slider de volume sem mecanismo real.

## Instalação e manutenção

- Instalação em uma única tela de decisão: pasta do aplicativo e atalhos.
- Banner Ouro e Carvão (#E6C989 / #141416); instalador nativo com Segoe UI e controles do sistema para DPI e tecnologias assistivas.
- Progresso do instalador medido pelo MSI; sem percentuais fictícios ou conclusão antecipada.
- Na mesma versão instalada, oferecer abrir, reparar ou desinstalar com confirmação; preservar projetos e preferências.
- Manutenção no frontend acessível por Preferências, seguindo a tipografia, os tokens e o comportamento responsivo existentes.
- Verificação diária somente por adesão explícita; download e instalação por escolha do usuário. Feed inativo deve ser informado com clareza e desabilitar a verificação.
- Exibir estados reais de consulta e atualização; erros de operação ou preferência ficam separados do resultado atual, sem encobri-lo.
