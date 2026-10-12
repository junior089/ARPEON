# ARPEON — etapa 9: preparação mais simples

A etapa atende ao R05 do relatório de melhorias. Na Prática, exercício, tonalidade e
andamento precedem **Gerar exercício**. O tipo de escala aparece quando necessário.
Oitava, repetição da referência e incremento de BPM ficam em **Ajustes avançados do exercício**,
recolhidos inicialmente. Um resumo mostra a oitava e a repetição escolhidas.

Na Análise, modo e parte antecedem a preparação de áudio compartilhada e a ação de iniciar.
Uma explicação distingue acompanhamento ao vivo de comparação pelo andamento escrito.
Janela, tolerâncias e compensação de latência ficam em **Ajustes avançados da análise**,
recolhidos inicialmente. Os critérios não representam nível de habilidade; 0 ms significa
sem compensação, não latência física calibrada. Nenhum recurso foi removido.

## Valores e recuperação

Campos vazios, fracionários ou fora dos limites bloqueiam gerar/iniciar e mostram correção
junto à ação principal, inclusive quando ajustes avançados estão recolhidos. A tolerância
de entrada não pode ultrapassar a janela de alinhamento. Os limites correspondem às regras
do backend, que continua validando as requisições. Captura continua exigindo ação explícita.

**Restaurar ajustes da análise** repõe janela de 450 ms, entrada de 120 ms, altura de 50 cents,
ritmo/duração de 20% e compensação de 0 ms. Parte, modo, microfone e Lá são mantidos.
Campos e restauração ficam indisponíveis enquanto a tentativa está ativa ou sendo iniciada.
Recolher controles preserva seus valores e a navegação entre áreas também os conserva,
em serviços da janela. Esses critérios e opções de exercício não são guardados em disco;
reiniciar o aplicativo repõe os padrões. Preferências de áudio mantêm a persistência da etapa 8.

Repetição e incremento afetam a referência reproduzida. A tentativa avalia o BPM escolhido,
sem adotar o incremento da reprodução como andamento de avaliação.

## Distribuição

O pacote desta etapa fica em `apps/desktop/build/native/practice-setup-20261011/`.
Execute `ARPEON/ARPEON.exe` dentro da pasta completa extraída do ZIP. O runtime Java,
código correspondente, licença e avisos acompanham o aplicativo. A conferência do launcher
real e da integridade é registrada em `build-verification.json` junto ao ZIP.
O executável permanece sem assinatura digital. Nenhuma dependência foi adicionada.

## Validação

136 testes frontend e 179 Java passaram. Os novos casos cobrem limites, valores vazios,
frações, relação entre tolerância e janela, bloqueio de envio, restauração, conservação de
escolhas ao recriar painéis, seleção real de escala menor no DOM e partitura que chega após
abrir a Análise. Lint, tipos, formatação, build de produção, build Gradle com warnings como
erros e 586 licenças/integridades npm foram verificados.

Duas conferências de navegador, em perfil separado, verificaram desktop 1280×900 e janela
compacta 390×844. A seleção de escala menor corresponde ao tipo enviado; a Análise escolhe
uma parte válida mesmo se o carregamento terminar após a abertura do painel. Erros de
incremento e tolerância continuam visíveis com os ajustes recolhidos. A restauração repôs
os cinco padrões e conservou parte, modo, entrada e Lá de 442,1 Hz. Nenhuma captura foi ativada.

Não houve overflow horizontal no documento ou no conteúdo principal. Na janela compacta,
**Gerar exercício** ficou visível, com a borda inferior em 606 px no caso de escala. A Análise
mantém rolagem normal para iniciar depois da preparação de áudio; a ação não cabe no primeiro
viewport compacto. Resumo avançado e erro mediram 8,705:1 e 5,689:1 de contraste.
O teclado abre/recolhe os controles nativos e conserva o foco no resumo.

O detector rodou uma vez nos quatro HTML/SCSS alterados, sem achados por regex, em modo
degradado por ausência dos parsers. Contraste e dimensões foram conferidos separadamente
no DOM; isso não equivale a auditoria completa com leitor de tela. Uma captura desktop do
navegador integrado tem altura transitória de 720 px; a Análise desktop registra 900 px e
as capturas compactas registram 844 px. Ensaios acústicos e calibração física continuam fora
desta validação. Evidências locais: `.tools/setup-review/browser-qa.json` e
`.impeccable/review/setup-*.png`.

## Interface registrada

A preparação reduz escolhas técnicas antes de tocar. A workstation musical compacta mantém
Charcoal + Muted Pastel Gold, Inter local, superfícies planas, separadores discretos e foco
visível. Campos nativos e ajustes avançados recolhidos preservam a linguagem existente.

Na Prática, exercício, tom e BPM, com tipo de escala quando necessário, conduzem a gerar,
ouvir a referência, preparar o microfone, tocar e revisar. A escala menor selecionada acompanha
as opções disponíveis e o valor enviado. Na Análise, modo e parte precedem o áudio compartilhado
e o início explícito; uma parte válida é selecionada mesmo quando a partitura chega depois.

Erros permanecem junto à ação principal, fora dos ajustes recolhidos. Valores vazios,
fracionários ou fora dos limites alinhados ao backend bloqueiam gerar/iniciar. Campos ficam
indisponíveis durante processamento ou tentativa ativa. Opções do exercício e critérios da
análise sobrevivem à navegação na mesma janela, em serviços, sem gravação em disco.
Restaurar repõe apenas os cinco critérios e preserva modo, parte, microfone e Lá;
o ensaio conservou 442,1 Hz.

Em 390×844, **Gerar exercício** permanece no primeiro viewport, com borda inferior em 606 px.
A Análise exige rolagem normal até iniciar após preparar áudio. Duas rodadas confirmaram
ausência de overflow horizontal, contraste de 8,705:1 no resumo e 5,689:1 no erro, além de
abertura/recolhimento por Enter com foco visível. A API aceitou Ré menor natural, oitava 5,
80 BPM, repetição e incremento de 3 BPM, gerando 15 notas com captura inativa.
A revisão final recebeu **SHIP**, sem achados materiais abertos.
