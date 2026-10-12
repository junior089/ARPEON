# Etapa 6 — edição por figuras rítmicas

Continuação de R09 do relatório de melhorias. O inspetor da partitura mantém a identidade
Charcoal + Muted Pastel Gold e substitui a entrada cotidiana de frações por nomes musicais.

- Seletor de semibreve a quartifusa, com zero, um ou dois pontos de aumento.
- Fração avançada preserva durações que não correspondem às figuras oferecidas, incluindo
  tercinas. Selecionar uma nota não arredonda nem altera sua duração.
- A sequência de estudo mostra o nome da figura; durações fora da lista conservam a fração
  explícita em unidades de semibreve.
- Antes da aplicação, mensagens explicam sobreposição com outra nota da mesma voz, fim do
  compasso e inserção maior que a pausa selecionada. Operações inválidas ficam desabilitadas.
- O limite escrito do compasso vem da API como fração exata, inclusive em mudanças de métrica.
  Vozes simultâneas são tratadas separadamente; pausas e lacunas seguintes permitem alongar.
- O texto explica o efeito da edição: encurtar cria uma pausa; alongar usa silêncio sem mover
  notas vizinhas; inserir usa somente a pausa selecionada.

O backend continua validando cada edição. Frações avançadas podem ser recusadas pelos limites
do MusicXML e da resolução MIDI existente. Ligaduras, desfazer/refazer e salvamento automático
mantêm seus contratos. Não foi acrescentada reprodução de nota isolada, seleção múltipla,
preferência de nomes Dó/C, calibração acústica ou análise de trechos nesta etapa.

## Validação e pacote

- Frontend: 83 testes passaram em 17 suítes; lint, tipos, formatação e build de produção aprovados.
- Java: 168 testes nos relatórios, zero falhas/erros; build completo com `--warning-mode=fail`.
  O teste de troca atômica do autosave precisou ser executado fora do sandbox; os arquivos de
  teste permanecem no projeto. As falhas iniciais foram de acesso a diretórios temporários.
- Licenças: 586 entradas npm conferidas, sem novas dependências.
- API real e frontend de produção: colcheia pontuada aplicada como 3/16; pausa restante criada
  e início da próxima nota preservado. Fração 1/12 aplicada, desfeita, refeita, salva e reaberta
  sem arredondamento. Valor que alcança outra nota desabilitou a aplicação com motivo explícito.
- Desktop 1280×900 e compacto 390×844: sem transbordamento horizontal da interface;
  controles acessíveis via Editar e teclado. Contraste medido do texto auxiliar: 8,068:1.
  Console sem erros/avisos nos fluxos verificados. Temas existentes foram preservados; esta
  verificação visual cobriu o tema escuro.
- Detector estático executado uma vez: nenhuma ocorrência, em modo degradado por ausência dos
  parsers. O resultado não é uma auditoria integral de acessibilidade.

Subdivisões que não cabem em 1920 ticks por semibreve recebem explicação antes de aplicar;
isso inclui algumas combinações pontuadas das menores figuras. Não houve teste físico de
microfone ou escuta/calibração de MIDI nesta etapa.

Pacote de saída: `apps/desktop/build/native/rhythm-20261011/ARPEON/ARPEON.exe` e ZIP portátil
adjacente. Extrair a pasta inteira; o runtime Java e a interface offline acompanham o EXE.
O registro externo `build-verification.json` contém hash, conteúdo e teste do launcher.

### Revisão independente — ship

| Critério | Veredito |
| --- | --- |
| Figuras, pontos e frações exatas | match |
| Limites prévios e mensagens de recuperação | match |
| Identidade, tipografia, controles e foco | match |
| Composição compacta | adaptation ao padrão existente |
| Achados materiais pendentes | nenhum no escopo revisto |

Revisor em contexto independente, com três capturas e arquivos primários. O screenshot
desktop limita a avaliação fina de tipografia por sua suavidade. Testes e prova da API foram
fornecidos como evidência; o revisor não os repetiu. O pacote nativo é verificado separadamente.

## Registro da interface

**Contexto e autoridade.** Registro descritivo da extensão existente, em modo **Operate**:
selecionar um evento, escolher sua duração e compreender os limites antes de aplicar.
A verdade do produto vem do [README](../../README.md) e deste relatório. Embora não exista
`PRODUCT.md` na raiz, a interface já tem autoridade visual em
[workstation-design.md](workstation-design.md) e
[styles.scss](../../frontend/src/styles/styles.scss). A direção herdada é Charcoal + Muted
Pastel Gold, Inter local e workstation musical plana; JetBrains Mono identifica alturas e
metadados musicais. Superfícies e separadores organizam o trabalho; dourado marca seleção e
foco, e a cor de aviso acompanha explicações. Este registro aplica a referência `document`
do impeccable ao escopo da etapa, sem estabelecer uma nova identidade global.

**Posição dos controles.** No desktop, a pauta ocupa o centro, o explorador fica à esquerda
e o inspetor à direita. Dentro de “Propriedades da seleção”, abaixo do resumo do evento e
das instruções, os campos seguem uma grade de duas colunas: Nota / Alteração, Oitava /
Figura rítmica e Pontos de aumento. A ajuda de duração e as consequências da edição aparecem
logo abaixo dos campos, seguidas dos avisos e dos botões Alterar nota, Alterar duração,
Inserir nota na pausa e Dividir pausa. A transposição permanece abaixo desse grupo.

**Figuras e fração exata.** O seletor oferece Semibreve, Mínima, Semínima, Colcheia,
Semicolcheia, Fusa, Semifusa e Quartifusa, além de Fração avançada. Pontos de aumento oferece
Sem ponto, Um ponto e Dois pontos; a ajuda explica os acréscimos de metade e de metade mais
um quarto e mantém a fração em unidades de semibreve visível. Fração avançada desabilita
Pontos de aumento e revela Numerador ao seu lado e Denominador na linha seguinte. Selecionar
um evento copia seu numerador e denominador originais; uma duração fora das figuras
reconhecidas abre automaticamente o modo avançado. A identificação por equivalência não
reescreve a fração. Entrar nesse modo também conserva os valores; escolher explicitamente
uma figura passa a definir uma nova duração.

**Explicação antes da aplicação.** O preflight exige numerador e denominador inteiros entre
1 e 1920 e duração representável por um número inteiro de ticks na resolução MIDI existente
(`numerador × 1920 / denominador`, por semibreve). Compara frações exatas para impedir que a
duração alcance outra nota da mesma voz ou ultrapasse o limite escrito do compasso informado
pela API. Inserir nota ou dividir pausa usa somente a pausa selecionada. A ajuda esclarece
que encurtar cria pausa, alongar consome o silêncio seguinte sem deslocar outras notas e a
inserção conserva o restante como pausa. Avisos textuais usam `role="status"`; os botões
correspondentes ficam desabilitados quando a operação é inválida ou está em processamento.
O backend mantém a validação final, inclusive os limites de representação do MusicXML.

**Janela estreita.** Até 980px, os painéis recolhem ao entrar na faixa compacta; selecionar
Editar abre o inspetor e recolhe o explorador. O inspetor passa a sobrepor a área da pauta,
ancorado à direita, com largura máxima de 320px limitada pelo espaço disponível e rolagem
própria. Até 600px, os atalhos Projetos e partes / Inspetor do cabeçalho ficam ocultos;
Editar continua sendo o acesso ao editor. Os campos conservam duas colunas, a ajuda quebra
linhas e os botões permitem rótulos em mais de uma linha. A pauta mantém largura mínima de
640px e rolagem horizontal; a alça de redimensionamento do inspetor fica oculta.

**Teclado e estados.** Labels envolvem os selects e inputs nativos, e Figura rítmica aponta
para a ajuda com `aria-describedby`. Esses controles seguem a navegação nativa por Tab e
Shift+Tab; o CSS define foco visível com contorno dourado de 2px e afastamento de 2px.
Os modos e os eventos da lista expõem seleção por `aria-pressed`; controles desabilitados
usam o estado nativo e opacidade reduzida. Na pauta focável, Enter abre Notas e digitação e
leva o foco ao primeiro botão da sequência. No desktop, a alça do inspetor aceita setas
esquerda/direita em passos de 16px e Home/End para 280/460px. Esses são contratos observados
no código, sem atribuir a este registro uma execução completa por teclado.

**Nomes na sequência de estudo e evidência visual.** Em Notas e digitação, abaixo da pauta,
os botões de eventos mostram ordem, altura ou pausa, voz, posição e nome da duração.
Um ponto acrescenta “pontuada”; dois, “com dois pontos”. Durações não reconhecidas conservam
o texto explícito `numerador/denominador de semibreve`. A sequência tem rolagem horizontal
e paginação de até 48 eventos. As capturas fornecidas registram o
[inspetor lateral](../../.impeccable/review/rhythm-desktop.png), o
[editor compacto](../../.impeccable/review/rhythm-compact.png) com foco em Pontos de aumento
e aviso de sobreposição, e a
[fração compacta](../../.impeccable/review/rhythm-compact-fraction.png) com `1/12` preservado,
foco no Denominador e pontos desabilitados. São evidências das janelas capturadas; não
comprovam testes em dispositivos físicos. Testes, pacote e revisão final pertencem à seção
de validação da etapa, registrada separadamente.
