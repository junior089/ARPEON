# ARPEON — revisão da reprodução e paginação

Continuação da revisão de 10/10/2026. O objetivo foi concluir a conferência da partitura
com várias páginas e reduzir diferenças entre a posição das notas e a barra de reprodução.

## Ajustes concluídos

- O cursor consulta o próximo evento com a mesma travessia de eventos visíveis usada
  pelo renderizador. Eventos invisíveis deixam de antecipar a posição de uma nota futura.
- Pequenas correções do relógio dentro da mesma nota não reiniciam a travessia da partitura.
  Repetições e buscas para trás continuam usando o tempo executado.
- A barra permanece oculta durante a contagem, inclusive após zoom e redesenho.
  Quando a reprodução começa, a posição inicial reaparece corretamente.
- O acompanhamento automático atualiza a rolagem depois de tornar a barra visível.
  A atualização ocorre quando muda a nota exibida, evitando pedidos contínuos de rolagem
  a cada quadro da animação.
- O seletor indica a página com maior área vertical visível. Um fragmento da página
  anterior no topo deixa de manter a indicação incorreta. A seleção também é aplicada
  às opções criadas durante a renderização inicial e o recálculo da paginação.
- A linha sólida de 5px e a compensação da latência informada pelo sintetizador,
  implementadas na revisão inicial, permanecem ativas.

## Validação

- 48 testes de frontend, em 13 suítes, passaram. Incluem regressões de eventos invisíveis,
  correções do relógio, restauração do cursor, zoom durante a contagem e página dominante.
  A travessia de repetição também usa um MusicXML real.
- ESLint sem warnings, TypeScript, Prettier e build de produção passaram.
- O build Gradle completo com `--warning-mode=fail` passou; a distribuição incorpora
  o frontend atualizado. A verificação de licenças conferiu 586 entradas npm.
- Navegador com frontend de produção e backend real, em perfil local isolado:
  Badinerie apresentou quatro páginas, com 42 compassos escritos e 59 ocorrências
  na execução. A passagem automática para a segunda página foi conferida com a barra
  nessa página e o seletor indicando 2, mesmo com parte da página 1 ainda visível.
- Contagem ativa durante mudança de zoom manteve a barra oculta. Reprodução,
  pausa, retomada e parada foram verificadas com o sintetizador MIDI local.
- A fixture `fixtures/musicxml/two-parts.musicxml` mudou do primeiro para o segundo
  compasso com andamento efetivo de 30 para 40 BPM, preservando o fator aplicado
  aos andamentos originais de 90 e 120 BPM. A interface e o backend concordaram;
  na pausa do segundo compasso, ambos informaram 40 BPM e posição MIDI 1950.
- O sintetizador informou 120ms de latência. Não houve erros ou avisos no console
  durante a conferência da passagem de página.

As capturas locais de evidência estão em `.tools/identity-review`:
`count-in-zoom.jpg`, `playback-page-transition.jpg` e `tempo-change.jpg`.
A sessão de teste usa perfil próprio; a sessão já aberta do usuário foi preservada.

## Limite da conclusão

A compensação utiliza a estimativa do sintetizador. A sincronização acústica exata
com os alto-falantes, fones, Bluetooth e drivers do usuário exige escuta ou medição
da saída física. Os testes confirmam o relógio, os eventos e o comportamento visual;
não constituem uma calibração acústica desse equipamento.
