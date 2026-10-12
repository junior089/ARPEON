# ARPEON — etapa 8: preparação de áudio compartilhada

Afinador, Prática e Análise passam a usar a mesma entrada e referência de Lá 4. As escolhas
são guardadas localmente em `.arpeon/audio-preferences.json`, no perfil do usuário, e são
restauradas ao reabrir o aplicativo, independentemente da porta local. A permissão de captura
não é armazenada: o microfone continua dependendo da ação explícita de iniciar a escuta.

A preparação aparece junto aos controles de escuta, inclusive antes de gerar um exercício
ou abrir uma partitura. Há seleção de entrada, atualização da lista, referência de 400–480 Hz,
instruções de preparação e teste de Lá com dois segundos de seno suave na saída do navegador.
O teste pode ser interrompido e para ao sair da área. A captura fica indisponível durante o
teste para evitar que a própria referência seja medida pelo microfone.

## Disponibilidade e recuperação

Java Sound antes criava UUIDs aleatórios a cada consulta. Agora a identidade usa nome,
fornecedor, descrição e versão do mixer, com ordinal apenas entre descritores idênticos.
Adicionar uma entrada diferente não muda a identidade existente. Java Sound não fornece
serial físico: dispositivos com descritores idênticos podem trocar de ordinal se a enumeração
mudar; alterações do driver também podem exigir nova seleção.

Uma entrada salva ausente não é substituída silenciosamente. A interface mostra seu nome,
explica como reconectar e atualizar ou selecionar outra entrada, e bloqueia o início da captura.
Falha na descoberta invalida a disponibilidade anterior. A referência inválida bloqueia a
escuta e não substitui o último valor válido no arquivo.

A leitura e a gravação dos ajustes têm erros próprios. Ajustes atuais podem continuar sendo
usados na janela quando o disco falha; a interface informa a falha e oferece tentar guardar
novamente. Arquivo corrompido gera erro sem ser apagado. Uma gravação explícita válida recupera
o arquivo. Escritas são serializadas, com substituição atômica quando suportada.

## Contrato e limites

`GET/PUT /api/audio/preferences` exige a sessão local e a proteção de origem já existente.
O formato contém apenas `schemaVersion: 1`, `deviceId`, `deviceName` e `referenceHz`.
Campos de consentimento ou ativação são rejeitados. Nenhuma amostra é guardada. As preferências
de aparência mantêm seu arquivo e esquema próprios. Nenhuma dependência foi adicionada.

O Lá compartilhado calibra a medição do Afinador, da Prática e da Análise, além do teste de
referência. Ele não altera a afinação do sintetizador MIDI do sistema. O teste usa a saída do
navegador e não calibra a latência física de áudio nem garante que o MIDI use essa mesma saída.

## Validação

105 testes frontend e 179 Java passaram, incluindo restauração, ausência de entrada,
referência inválida, erros de disco/consulta, edição durante gravação, resposta atrasada,
cancelamento do teste e autenticação HTTP. Lint, tipos, formatação, build de produção,
build Gradle com warnings como erros e 586 licenças/integridades npm foram verificados.

As conferências de navegador e do executável usam perfis isolados dentro do projeto.
Não representam gravação física nem medição acústica. O pacote Windows contém runtime,
código correspondente, licença e avisos; o executável permanece sem assinatura digital.

Na inspeção 1280×900 e 390×844, os ajustes 442,1 Hz e microfone fifine foram restaurados
após reinício e conservados nas três áreas. A janela compacta usa uma entrada fictícia,
identificada como teste desconectado, para mostrar recuperação sem ativar a captura.
O teste de som bloqueou o botão de ligar, parou ao navegar e encerrou após dois segundos
com foco preservado no mesmo botão. Os IDs das sete entradas descobertas permaneceram iguais
em consultas consecutivas; o estado de captura continuou `IDLE`.

Não houve overflow horizontal no documento ou no conteúdo principal nas duas janelas.
Texto secundário e aviso sobre a superfície do painel mediram, respectivamente, 8,068:1 e
8,051:1 de contraste. O detector rodou uma vez nos HTML/CSS alterados, sem achados por regex,
em modo degradado por ausência de parsers. Não é uma auditoria completa; contraste e dimensões
foram conferidos separadamente no DOM. A captura desktop do navegador integrado tem leve
suavização de texto; a compacta permite leitura mais nítida.

Distribuição desta etapa: `apps/desktop/build/native/audio-setup-20261011/ARPEON/ARPEON.exe`
e `ARPEON-0.1.0-windows-x64.zip` na pasta imediatamente acima. A conferência do ZIP valida
runtime, fonte correspondente por hash, bundle de produção idêntico ao workspace e checksum.
`build-verification.json` junto ao ZIP registra resultados e o teste do launcher real.

## Interface registrada

Extensão local em modo Operate da workstation Charcoal + Muted Pastel Gold, com Inter local,
conforme `workstation-design.md` e `frontend/src/styles/styles.scss`. Superfícies planas,
separadores discretos, campos nativos rotulados e dourado para ação/seleção mantêm a identidade
existente. No Afinador desktop, o instrumento ocupa a área principal e a preparação fica na
lateral; na janela compacta, a preparação precede o instrumento. Prática e Análise oferecem os
mesmos ajustes junto à escuta, inclusive antes de gerar exercício ou abrir partitura.

O componente `audio-preparation.{ts,html,scss}`, com `core/audio-settings.ts` e
`core/reference-tone.ts`, permite escolher uma vez a entrada e o Lá 4 (400–480 Hz), guardar
localmente e compartilhar entre as três áreas. Mostra consulta, gravação, confirmação e falhas
com recuperação direta. A entrada salva ausente conserva seu nome e o aviso de indisponibilidade:
reconectar e atualizar, ou selecionar outra. Ausência de entrada e referência inválida bloqueiam
a captura, que sempre exige ação explícita.

“Atualizar entradas” consulta os dispositivos; “Ouvir Lá 4” toca dois segundos na saída do
navegador e muda para “Parar referência” no mesmo botão. Durante o teste, ajustes e início da
captura ficam bloqueados; navegar encerra o som. Campos e botões usam teclado nativo e foco
visível dourado; ao terminar o tom, o foco permanece no botão “Ouvir Lá 4”.

As evidências de [navegador](../../.tools/audio-review/browser-qa.json) e
[API](../../.tools/audio-review/api-qa.json) registram restauração e compartilhamento de 442,1 Hz,
captura `IDLE` e ausência de overflow horizontal em 1280×900 e 390×844. Texto secundário e aviso
mediram 8,068:1 e 8,051:1. A entrada USB desconectada da captura compacta é uma fixture
explicitamente fictícia. Este registro da interface não valida hardware, acústica ou latência.
A abertura do EXE e a integridade do pacote são registradas separadamente em
`build-verification.json`, junto ao ZIP.

Capturas: [Afinador desktop](../../.impeccable/review/audio-desktop.png),
[Afinador compacto](../../.impeccable/review/audio-compact.png),
[Prática](../../.impeccable/review/audio-practice.png) e
[Análise](../../.impeccable/review/audio-analysis.png).
