# Plano técnico de melhorias do ARPEON

Este plano cobre as sete melhorias levantadas a partir do estado do repositório em
9 de outubro de 2026. É um plano de implementação, não uma afirmação de que as
funcionalidades já existem. O desenvolvimento deve preservar processamento local,
rejeição explícita de semântica musical não suportada, autorização do servidor em
`127.0.0.1` e ausência de gravação de áudio bruto.

## Ordem proposta

| Etapa | Entrega | Dependência | Porte estimado* |
| --- | --- | --- | --- |
| 0 | Linha de base e corpus de validação | Nenhuma | P |
| 1 | Projetos locais e recuperação | 0 | G |
| 2 | Reconexão da interface | 1 para restaurar o estado após reinício | M |
| 3 | Ensaios em hardware e arquivos reais | 0; repetir após 4–7 | M contínuo |
| 4 | Ataques, notas repetidas e acompanhamento | 3 | G |
| 5 | Importação grande e uso de memória | 0 | G |
| 6 | Ampliação gradual de MusicXML | 5 para elevar limites com segurança | G por subconjunto |
| 7 | Distribuição Windows | 1–3 e testes de aceitação | M |

\* P/M/G indicam porte relativo, não promessa de prazo. O suporte a acordes, vozes
simultâneas ou repetições é uma mudança de modelo e deve ser estimado à parte.

### Etapa 0 — linha de base

1. Registrar os resultados do bloco de validação do `README.md` e da matriz de CI.
2. Criar corpus pequeno, médio e próximo dos limites publicados, com origem/licença
   identificadas, para MusicXML, MXL, MIDI, WAV e OMR. Incluir entradas malformadas.
3. Medir tempo, pico de heap Java, memória do navegador, tamanho das respostas e
   tempo de renderização. Guardar máquina, JDK, navegador e método de medição.
4. **ADRs registrados:** formato de projeto em
   [`0005-local-score-projects.md`](../adr/0005-local-score-projects.md) e expansão
   de polifonia/ordem de execução proposta em
   [`0007-polyphony-and-execution-order.md`](../adr/0007-polyphony-and-execution-order.md).

## 1. Projetos locais, salvamento automático e recuperação

**Estado atual.** `ScoreDocument` mantém revisões imutáveis e até 100 estados de
desfazer/refazer em memória. `ScoreWorkspace` guarda partitura, proveniência e
reprodução; o relatório em `AnalysisWorkspace` também é temporário. Já existe um
exemplo de escrita atômica em `PracticeHistory`.

**Desenho.** Criar um repositório local `ProjectStore` em `apps/desktop`, separado
do domínio musical. Um projeto terá ID aleatório, versão de esquema, timestamps,
MusicXML canônico da revisão atual, seleção de instrumentos/tonalidade, referência
ao nome e SHA-256 da origem, tipo de importação e avisos de revisão. O manifesto
deve identificar campos derivados ou assumidos. Não embutir áudio, PDF, imagem,
credenciais nem URL com segredos. Armazenar em diretório de dados do usuário,
com nome de arquivo derivado apenas do ID; nunca aceitar caminhos enviados pelo
navegador. Uma extensão `.arpeon` exportável pode ser adicionada depois, com
manifesto e MusicXML dentro de contêiner versionado.

**Implementação.**

1. Definir esquema v1, limites de tamanho e contagem, checksum e migração de
   versões. Persistir o estado musical atual e metadados; a pilha de desfazer/refazer
   pode entrar no esquema v2, desde que a UI deixe claro que ela reinicia no v1.
2. Criar `ProjectStore` com escrita em arquivo temporário, sincronização do
   conteúdo, substituição atômica quando suportada e cópia recuperável da última
   versão válida. **Implementado:** arquivos de partitura e manifesto são forçados
   ao disco antes da troca; um backup só é restaurado após validar o manifesto e o
   checksum da partitura. Adaptar o padrão de `PracticeHistory`.
3. Expor `GET /api/projects`, `POST /api/projects`, `GET /api/projects/{id}` e
   `DELETE /api/projects/{id}` no `DesktopServer`, com o mesmo token e controles
   de origem das APIs atuais. Usar corpo limitado e IDs estritamente validados.
4. Fazer `ScoreWorkspace` carregar um candidato completo, validar importação,
   timeline, exportação e MIDI, e só então substituir a partitura aberta. Evitar
   que projeto corrompido destrua a sessão atual.
5. **Autosave implementado na UI:** edições e importações confirmadas criam/atualizam
   projeto local após debounce de 900 ms; a interface informa salvamento e mantém
   erros visíveis. Autosaves agora usam executor dedicado de uma thread com fila
   limitada a duas gravações pendentes; salvamentos manuais continuam no executor
   HTTP. A resposta só confirma depois da persistência durável, e saturação retorna
   erro explícito para a UI manter a alteração como não salva. Testes cobrem execução
   no worker dedicado, caminho manual, concorrência da mesma revisão e falha ao
   escrever manifesto sem perder o projeto anterior.
6. **Implementado:** persistir relatórios concluídos em registro separado, ligado
   ao ID do projeto quando salvo, SHA-256 e revisão. O histórico tem retenção de
   até 100 entradas/16 MiB e oferece consulta e exclusão. Nunca salva áudio bruto.

**Aceite.** Edição e relatório sobrevivem ao reinício; falha de energia ou gravação
interrompida conserva a última versão íntegra; projeto malformado é recusado sem
alterar o projeto aberto; exportar MusicXML continua independente; os testes cobrem
migração, limites, concorrência entre revisões e falha de escrita. Uma sessão ativa
de microfone ou reprodução não é retomada automaticamente.

## 2. Reconexão da interface após atualizar a aba

**Estado atual.** O launcher entrega um token aleatório no fragmento da URL.
`SESSION_TOKEN` o remove do endereço e o mantém em `sessionStorage` até o fechamento
da aba. O servidor exige token, Host exato e mesma origem.

**Desenho adotado.** O segredo não fica em `localStorage`, cookies, arquivos de
projeto ou logs. Como `sessionStorage` pode ser lido pelo JavaScript da mesma
origem, o bundle e as dependências locais fazem parte da fronteira de confiança;
a aplicação mantém CSP restritiva e não carrega scripts remotos. Ver
[ADR 0006](../adr/0006-refreshable-local-session.md).

**Implementação.** No bootstrap, a UI chama `GET /api/session` e, se houver
partitura aberta, `GET /api/score`. O servidor informa o ID do projeto ativo; a UI
restaura o estado da revisão. Captura e reprodução não são retomadas e continuam
sujeitas ao watchdog. Após reinício do servidor, o token antigo falha; o usuário
abre a janela do novo processo e escolhe um projeto salvo na lista local.

**Aceite.** Refresh na mesma instância recupera partitura e projeto ativo. O
segredo sai da URL após o bootstrap e desaparece ao fechar a aba. Requisições de
outra origem ou sem credencial são recusadas. Fechar o launcher invalida a sessão.
Testar refresh, duas abas, expiração, navegação e watchdog.

## 3. Validação real de áudio, OMR e arquivos grandes

**Método.** Construir matriz reproduzível em Windows, macOS e Linux, com modelo
de microfone, interface MIDI, sintetizador, JDK, navegador e versão do Audiveris.
Para cada plataforma, testar permissão negada, ausência/troca/desconexão de
dispositivo, início/parada repetidos, silêncio, ruído, notas sustentadas, ataques
rápidos, notas repetidas, instrumentos reais e escalas. Medir desvio em cents
frente a referência conhecida, latência de captura à UI, perdas de janela,
posição do cursor, falso salto e liberação de dispositivo. Comparar partitura
original com OMR por página e compasso; registrar taxa de aceitação e rejeição.

**Instrumentação.** Adicionar diagnósticos locais optativos com timestamps de
etapas e contadores, sem áudio nem caminhos privados. Usar scripts de fixtures
e o [registro de aceitação](../development/hardware-acceptance.md); não confundir teste sintético
com validação acústica. Separar critérios de aceite por dispositivo e cenário.
Uma falha reproduzida gera fixture/teste de regressão quando possível.

**Aceite.** Relatório de testes com resultados, configurações, limitações e
decisões para cada plataforma. Nenhuma promessa de precisão, latência ou
compatibilidade entra na documentação antes da medição correspondente.

## 4. Ataques, notas repetidas e acompanhamento

**Estado atual.** `OnsetSegmenter` abre evento quando a altura muda ou há uma
lacuna; `ScoreFollower` exige duas janelas estáveis e procura próximas quatro
notas. Dois ataques da mesma altura sem lacuna podem virar um só evento.

**Implementação em camadas.**

1. Montar corpus rotulado com onsets e posições, incluindo notas repetidas,
   legato, staccato, variação de andamento, silêncio, vibrato, ruído e motivos
   repetidos. Medir baseline com métricas separadas: precisão do onset, posição,
   recuperação, falsos saltos e latência.
2. **Candidato de ataque RMS (implementado):** calcular subida de energia no
   processamento de cada janela PCM, com piso adaptativo, razão mínima e período
   refratário; manter timestamps em fila limitada no afinador. O navegador e o
   polling HTTP não são o relógio musical.
3. **Consumo do candidato (implementado):** o segmentador pode separar uma nota
   repetida quando há ataque após duração mínima; o seguidor reinicia a confirmação
   de altura para a próxima nota igual. A heurística ainda exige corpus para calibrar
   histerese e evitar falsos ataques por vibrato ou ruído.
4. **Hipótese local de andamento implementada no modo FOLLOW:** calcula uma razão
   limitada entre intervalos observados e esperados após correspondências consecutivas;
   intervalos extremos deixam o estado `UNCERTAIN`. A UI mostra BPM aproximado após
   três intervalos e informa que não é calibração. A hipótese não controla o cursor;
   alinhamento variável ainda precisa de corpus independente antes de avançar posição.
   Não habilitar o modelo de `arpeon-ml` só pelo resultado sintético existente, pois
   ele aumenta falsos saltos.
5. A UI já expõe a hipótese e mantém o seguimento por altura estável; ainda falta
   calibrar a latência e os limites com corpus e hardware. A análise de andamento fixo
   permanece um modo distinto.

**Aceite.** Melhorar a separação de notas iguais e a recuperação no corpus real
sem regressão de falsos saltos, latência ou liberação do microfone. Congelar
parâmetros em conjunto de desenvolvimento e avaliar em gravações independentes
com direitos documentados.

## 5. Memória e desempenho de importação grande

**Estado atual.** O navegador valida UTF-8 em blocos, envia o próprio `File` e
permite cancelar a transferência. O endpoint MusicXML sem compressão lê o corpo
HTTP diretamente pelo StAX, calcula SHA-256 durante a leitura e aplica o limite
de 128 MiB sem criar um `byte[]` intermediário. O parser converte um fragmento
DOM por vez. MXL, OMR e importações genéricas ainda usam buffers em memória;
`ScoreWorkspace` ainda materializa XML e metadados, mas os eventos ficam fora do
snapshot inicial e são carregados por compasso. `sendJson` serializa direto no
corpo HTTP, sem manter uma segunda cópia JSON em `byte[]`. O navegador recebe XML
e prévia e renderiza SVG completo. O limite de
entrada não representa um limite de memória total.

**Implementação.**

1. **Limites estruturais iniciais implementados:** 16 partes, 2048 compassos por
   parte e 16384 eventos por partitura, além do limite de bytes. O `MusicXmlImporter`
   usa StAX seguro e conta cumulativamente 250000 elementos, 32 níveis e 16 atributos;
   converte apenas o elemento de alto nível atual para DOM e o descarta após processar,
   sem manter uma árvore da partitura inteira. A leitura MXL drena entradas descartadas
   em blocos. Permanecem pendentes medições de heap, saída JSON/SVG e tempo; a
   normalização OMR ainda usa DOM após pré-validação SAX; validação UTF-8 e remoção
   de DTD operam em bytes, sem duplicar o XML inteiro em uma `String`.
2. **Upload direto de MusicXML implementado:** o servidor analisa o stream HTTP
   sem materializar o arquivo em `byte[]`, calcula o hash no mesmo fluxo e rejeita
   a entrada acima do limite. Não há cópia temporária persistente. A resposta e a
   renderização ainda são síncronas; MXL/OMR/importações genéricas continuam com
   buffers em memória e precisam de fluxo cancelável próprio.
3. **`MusicXmlImporter` migrado para StAX:** fixtures existentes executam o novo
   caminho e cobrem equivalência funcional, rejeições e MXL; limites de profundidade,
   elementos e atributos são contados no fluxo, com DTD e entidades bloqueados.
   A conversão limitada a fragmentos DOM preserva a lógica sem manter a árvore
   completa. Migrar também a normalização OMR, que ainda usa DOM.
4. **Eventos são carregados por compasso sob demanda:** snapshot HTTP envia metadados
   de compasso sem os eventos; `GET /api/score/measure-events` valida parte e índice,
   devolve apenas um compasso e a revisão. A UI mantém o compasso ativo em cache por
   revisão, evita requisições duplicadas e pagina 48 eventos por vez no DOM. O tamanho
   de um compasso ainda pode chegar ao limite estrutural; paginação dentro do compasso,
   paginação de transporte e renderização por faixas dependem de medir o renderer. O
   OpenSheetMusicDisplay continua renderizando a partitura inteira.
5. **Medição Java sintética próxima aos limites registrada** em
   [`source-import-benchmark.md`](../development/source-import-benchmark.md): 2.048
   compassos/16.384 notas, 73,73 ms e 54.260.459 bytes alocados por importação
   (média de três execuções, Windows/JDK 25; CPU indisponível). Ainda faltam arquivos
   reais licenciados, pico de heap, memória do navegador, tamanho/tempo da resposta,
   prévia SVG e cancelamento durante e após a transferência.

**Aceite.** A UI permanece responsiva ou permite cancelar; arquivos acima do
orçamento falham com motivo claro; importação não bloqueia o afinador; temporários
são removidos; consumo de memória e tempo ficam dentro do orçamento publicado.

## 6. MusicXML em subconjuntos verificáveis

**Sequência.**

1. **MXL (implementado):** leitura de ZIP com proteção por limite de tamanho
   expandido, número de entradas, caminhos e arquivo raiz validado; entradas
   descartadas são drenadas em blocos. O MusicXML selecionado segue ao importador
   existente. Este passo não muda o domínio musical.
2. **Localização de erros (implementada):** falhas de semântica identificam o
   nome/ID da parte, o número do compasso e, para notas, sua posição no compasso.
   Isso orienta a correção no arquivo original sem descartar eventos silenciosamente.
3. **Metadados simples:** título de obra/movimento implementado como título
   canônico da partitura; conflitos são rejeitados. Escolher campos como letra,
   clave ou dinâmica apenas após definir se serão preservados na exportação e
   mostrados na UI. Adicionar ao domínio somente semântica que o editor mantém.
4. **Ligaduras de duração (implementadas):** o domínio valida continuidade e altura
   entre segmentos; importação/exportação preserva os marcadores; timeline, MIDI e
   análise tratam a sequência como um som sustentado. A UI permite desfazer a
   ligadura inteira e bloqueia exclusão parcial. Cobrir edição, desfazer/refazer e
   round-trip com exemplos licenciados adicionais.
5. **Acordes e vozes independentes:** implementados `Chord`, `Voice`, `TimedEvent` e
   offsets racionais; import/export MusicXML preserva `<chord/>`, IDs de voz,
   `<backup/>`/`<forward/>`; timeline, MIDI, OMR review, edição por voz e lista de eventos
   mantêm os onsets. Ligaduras, exclusão e transforms preservam cabeças e vozes. A análise
   ao vivo recusa notas sobrepostas. Próxima extensão do modelo: múltiplas pautas por parte;
   manter análise monofônica explícita.
6. **Repetições e finais (implementados no subconjunto limitado):** `RepeatRegion` e
   `EndingRegion` preservam marcas sem expandir compassos escritos. O mapa de execução
   limita repetições a 2–8 passagens, pula finais contíguos que não se aplicam à passagem
   atual e alimenta timeline, MIDI e metrônomo. MusicXML e a normalização OMR preservam as
   marcas; o round-trip tem teste com primeiro e segundo final. Repetições/finais aninhados,
   finais descontínuos e mais de oito passagens continuam rejeitados explicitamente.

Para cada subconjunto: registrar exemplos reais licenciados, especificar semântica,
criar importação/exportação com round-trip, testar o renderer e manter rejeição
clara para elementos ainda não suportados. Atualizar limites e documentação só
após medições do item 5.

**Aceite.** Nenhuma nota, duração, voz, repetição ou metadado prometido desaparece
silenciosamente no ciclo abrir–editar–exportar–reabrir. Recursos não suportados
continuam gerando erro com localização útil.

## 7. Distribuição Windows e ciclo de vida

**Estado atual.** `scripts/package-windows.ps1` gera uma `app-image` via
`jpackage`, ZIP e SHA-256. O launcher abre o navegador e espera com console
visível; o executável ainda não é assinado.

**Implementação.**

1. **ZIP portátil construído e inspecionado localmente:** app-image contém runtime
   Java 25, launcher, `source.zip`, licença, avisos e instruções; o SHA-256 do ZIP
   confere com o sidecar. Ainda falta iniciar em máquina limpa, testar instalação,
   atualização/desinstalação e confirmar preservação dos dados do usuário.
2. **Implementado:** comando autenticado `/api/shutdown`; a UI pede confirmação,
   avisa sobre alterações de partitura não salvas, encerra captura, MIDI, servidor
   e threads, e informa quando a aba já pode ser fechada.
3. **Implementado:** fechar somente a aba preserva o servidor e o token da sessão
   continua restrito ao armazenamento da aba; reabrir após reinício usa a nova URL
   do launcher e os projetos locais.
4. **Preparação para assinatura implementada:** `package-windows.ps1` pode exigir
   certificado Authenticode no armazenamento do usuário, assina o executável e
   verifica a cadeia antes de compactar; certificado e endpoint entram somente por
   variáveis de ambiente. Ainda falta executar em ambiente de release com
   certificado real. Publicar checksum, proveniência de build, `source.zip`,
   licenças e instruções. A CI deve verificar conteúdo, instalação, inicialização,
   encerramento e atualização; a assinatura precisa ser validada no artefato final.
5. Manter uma opção portátil para quem não quer instalar. Registrar tamanho,
   inicialização, antivírus/SmartScreen observado e falhas em máquinas limpas.

**Aceite.** Instalar ou extrair, iniciar, abrir/fechar a UI, encerrar e atualizar
funcionam sem JDK/Node instalados e sem perder projetos. O pacote final contém
licença, avisos, fonte correspondente e assinatura verificável quando publicada
como versão assinada.

## Critérios comuns de conclusão

- Testes Java e frontend existentes continuam passando na matriz de CI.
- Novos contratos HTTP têm testes de autorização, origem, tamanho e falha.
- Alterações musicais têm fixtures de importação/exportação e regressão de
  timeline/MIDI; testes sintéticos não substituem ensaio acústico.
- Limites, dados salvos e mensagens de erro são descritos em português na UI e
  na documentação.
- Cada etapa entrega uma funcionalidade utilizável e uma medição dos riscos que
  ela pretende reduzir.
