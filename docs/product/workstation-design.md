# ARPEON — revisão da workstation

A implementação posterior, orientada pelo kit de marca fornecido em 12/10/2026, está em
[Redesign integral: implementação e validação](redesign-2026-10-12.md). Este documento preserva
o histórico da revisão anterior.

Revisão de 10/10/2026 sobre a aplicação Angular existente. A estrutura musical, os limites de
importação, os projetos locais, o consentimento do microfone e as licenças foram preservados.
Nenhuma dependência foi adicionada. Inter e JetBrains Mono continuam locais para uso offline.

## Identidade e estrutura

As cinco áreas — Partitura, Importar, Prática, Afinador e Análise — usam superfícies planas,
separadores discretos e controles consistentes. O dourado marca ações e seleção. Textos
promocionais, etiquetas redundantes e caixas decorativas foram removidos. O transporte
permanece acessível durante a rolagem da partitura.

O menu Exibir oferece temas escuro e claro, persistidos em `arpeon.theme.v1`. A preferência
resiste a valores inválidos e armazenamento indisponível. A notação mantém papel e tinta
independentes do tema do aplicativo, com opção própria de partitura escura.

O explorador reúne projetos e partes; o inspetor reúne seleção, edição e transposição. O
inspetor tem largura de 280–460px, ajuste por ponteiro e teclado. Preferências de painéis e
zoom de 50–200% usam `arpeon.layout.v1`. Até 980px, os painéis recolhem; até 600px, somente
a pauta mantém largura mínima e rolagem horizontal, preservando acesso aos controles.

## Sistema visual

Fonte única: `frontend/src/styles/styles.scss`. Componentes usam tokens semânticos; os aliases
existentes são mantidos para compatibilidade.

| Papel | Tema escuro |
| --- | --- |
| Fundo / lateral / superfície / elevado | #111315 / #151719 / #191B1E / #24272B |
| Dourado / hover / pressionado / seleção | #D8BB78 / #E8D39E / #AA8E54 / #3B3324 |
| Texto principal / secundário / discreto | #ECECE7 / #B0B2AD / #929690 |
| Separador / borda padrão / limite de formulário | #2B2E31 / #393D41 / #777D80 |
| Sucesso / aviso / erro / informação | #82AD8E / #D6AA65 / #CA7777 / #86A8BE |
| Papel / tinta / cursor | #F5F2EA / #252525 / #AA8E54 |
| Espaçamento | 4, 8, 12, 16, 24, 32px |
| Tipografia | 11–12px metadados, 13px controles, 14px corpo, 16px seções, 20px títulos |
| Controles e cantos | 32px de altura; 6px nos controles; 4px em elementos compactos |

O tema claro usa neutros quentes e dourado #806326 para legibilidade. Hover, pressionado,
selecionado, foco, desabilitado, erros e processamento seguem o mesmo sistema. Sombras
existem somente para separar sobreposições. Não há fontes externas, brilho ou gradientes
decorativos; as marcas da escala do afinador mantêm um fundo repetido funcional. Preferências
de movimento reduzido e cores forçadas são respeitadas.

## Páginas e reprodução

A partitura usa páginas A4 em orientação retrato, com seletor, anterior/próxima e indicação
que acompanha a rolagem. Todas as páginas geradas são exibidas. A paginação é recalculada
com zoom e largura disponível; não reproduz necessariamente as quebras impressas da fonte.
O cabeçalho conta compassos escritos; as ocorrências das repetições continuam na execução.

O clique usa a transformação do SVG da página selecionada e procura notas apenas nessa
página. A busca no transporte encontra a primeira ocorrência do compasso escrito, evitando
confundir índices de edição com ocorrências após uma repetição.

A barra é uma linha sólida de 5px, com opacidade de desenho de 100%. O cursor mantém a nota
até o próximo início e segue o tempo executado, incluindo repetições. Um iterador independente
antecipa somente a consulta do próximo instante; clonar a posição intermediária do OSMD
perdia o estado da repetição. Buscas para trás reiniciam a travessia corretamente.

O backend continua usando o relógio MIDI como posição canônica. Agora informa também
`audibleTick`, `audibleBpm` e `audioLatencyMs`. A compensação subtrai a latência informada
pelo sintetizador em segundos, respeitando mudanças de andamento e o fator de BPM escolhido.
A interpolação visual fica limitada a 160ms sem amostra nova; a barra fica oculta na contagem.
A latência informada é uma estimativa do sintetizador, não uma calibração acústica.

## Verificações da revisão inicial

- Frontend: ESLint sem warnings, TypeScript de aplicação e testes, Prettier e build de produção.
- 34 testes em 12 suítes, incluindo temas, preferências, menus, teclado, edição e cursor.
  O cursor é testado também com MusicXML real contendo repetição, além de limites e busca.
- Gradle completo com `--warning-mode=fail`: compilação, formatação, testes e distribuições.
  Três testes novos cobrem latência entre andamentos, fator de tempo e busca sem abrir áudio.
- Verificação de licenças: 586 entradas npm com licença e integridade conferidas.
- Navegador com frontend de produção e backend real em perfil isolado: Badinerie contém
  42 compassos escritos, distribuídos em quatro páginas a 1280px com painéis abertos.
  A última página e a seleção/busca de sua nota do compasso 39 foram verificadas.
- Reprodução real neste computador informou 120ms de latência: a 120 BPM, a posição audível
  ficou 115 ticks MIDI atrás da posição canônica. Reprodução, pausa, parada e contagem testadas.
- Temas claro/escuro, prática gerada, importação, afinador e análise inspecionados no navegador.
  Capturas locais ficam em `.tools/identity-review`. Verificações responsivas cobriram janelas
  de desktop e 390px. As capturas são evidência de revisão, não assets distribuídos.

## Limites de validação

A sincronização acústica exata ainda exige escuta ou medição na saída de áudio do usuário;
a estimativa do sintetizador pode diferir de hardware, Bluetooth e drivers. Não foram
revalidados precisão física do microfone, sessões longas de acompanhamento, reconhecimento
Audiveris, fontes externas e comportamento nativo em macOS/Linux. A análise segue monofônica,
e OMR exige revisão. Não há alegação de certificação integral de acessibilidade: foco,
semântica, estados e responsividade foram conferidos, sem auditoria especializada completa.

## Continuação: páginas e sincronização

A revisão complementar está registrada em
[Revisão da reprodução e paginação](playback-review-2026-10-10.md), com os ajustes finais
do cursor, contagem, acompanhamento automático e indicador da página visível. A suíte atual
do frontend contém 48 testes em 13 suítes.

## Referências consultadas

- [Dorico: painéis e zonas](https://www.steinberg.help/r/dorico-elements/6.2/en/dorico/topics/user_interface/user_interface_panels_r.html): prioridade da notação e contexto lateral.
- [Ableton: conceitos do Live](https://www.ableton.com/en/manual/live-concepts/): transporte e áreas de trabalho integradas.
- [Java: Synthesizer.getLatency](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/javax/sound/midi/Synthesizer.html): estimativa de atraso de processamento.
