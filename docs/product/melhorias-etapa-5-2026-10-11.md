# Melhorias implementadas — etapa 5

Data: 11 de outubro de 2026. Continuação da etapa 4, com prioridade para a falha de PDF informada pelo usuário e a primeira entrega de R10.

## Correção do PDF

Foi reproduzida uma falha com Audiveris 5.11.0 já encontrado: o processo reconhecia e exportava o PDF, mas o importador do ARPEON recusava compassos incompletos. O resultado multipágina também repetia armaduras iguais, continha um compasso vazio e quiálteras. Encontrar o executável não comprova a qualidade do reconhecimento; a interface agora diz “Audiveris encontrado”.

A preparação do rascunho OMR preserva alturas, durações reconhecidas, posições, vozes, acordes e ligaduras. O importador de MusicXML comum e as regras do domínio continuam estritos.

- Armaduras repetidas e idênticas são consolidadas. Mudança real de armadura continua recusada com indicação do compasso.
- Compassos incompletos preservam o espaço final sem inventar alturas ou notas reconhecidas.
- Um compasso vazio recebe pausa **provisória**, identificada nos avisos como hipótese do rascunho.
- Quando as durações excedem o compasso, a fórmula **provisória** acomoda os eventos sem truncar ou quantizar notas; a fórmula original é restaurada no compasso seguinte. O aviso identifica a parte, o compasso, a fórmula reconhecida e a provisória. Essas medidas exigem correção antes do estudo.
- As partes recebem uma fórmula compartilhada quando seus compassos divergem; anacruses incompatíveis recebem alinhamento **provisório**, sem inventar notas. Isso mantém o rascunho aberto, exportável e com uma linha de tempo comum. Os avisos identificam as partes e os compassos a corrigir. Quantidade ou numeração diferente de compassos continua recusada.
- Quiálteras preservam durações em frações. A representação visual do agrupamento não é preservada e isso aparece nos avisos.
- Nomes de arquivo com acentos e caracteres como travessão são aceitos, mantendo os limites de tamanho e a recusa de caminhos e caracteres de controle.

Isso permite revisar resultados imperfeitos; não confirma que o OMR acertou a música. A abertura exige aceite explícito dos avisos e das hipóteses do rascunho. Alterações de armadura, múltiplas pautas na mesma parte, glissandos e outras estruturas não representáveis continuam exigindo correção no Audiveris.

## Controle da importação

O fluxo mostra leitura/validação, reconhecimento e preparação da revisão, sem porcentagem inventada. Cancelar interrompe o trabalho no servidor e encerra somente a árvore do processo iniciado para aquele trabalho. Cancelamento durante o upload impede o início posterior; o trabalho cancelado não publica uma prévia tardia. Descartar invalida a prévia correspondente. A partitura atual só muda após a revisão aceita.

O PDF original é exibido junto da lista de compassos reconhecidos em janelas amplas; em janelas estreitas, as áreas se empilham. Botões permitem percorrer todas as páginas. O PDFBox já presente no aplicativo gera uma imagem PNG da página solicitada, com borda máxima de 1600 pixels, preservando sua rotação. Isso elimina a dependência do visualizador PDF do navegador, que ficou em branco na primeira inspeção. A API autenticada limita o arquivo, valida as páginas e prepara apenas uma por vez; nenhuma cópia persistente é criada. O original permanece em um blob local com MIME controlado, com link para abrir separadamente. Falhas de página permitem tentar novamente; respostas tardias são descartadas ao sair, cancelar ou trocar o arquivo. Não há sincronização automática entre página e compasso nesta entrega.

Falhas nativas mostram uma explicação curta e detalhes recolhidos, com cauda de saída limitada a 16 KiB. Os arquivos temporários são removidos ao concluir, falhar ou cancelar. Os limites permanecem: 32 páginas/64 MiB para PDF, 32 MiB para imagens, até dez minutos de reconhecimento.

## Validação

- 75 testes frontend e 168 testes Java passaram, sem falhas; lint, tipos, formatação e builds de produção passaram. A verificação de licenças conferiu 586 dependências bloqueadas.
- Audiveris 5.11.0 configurado reconheceu um PDF real de cinco páginas, duas partes e 83 compassos (166 parte-compassos na lista de revisão). A primeira e a quinta página foram renderizadas localmente pela API; sem token, ela retornou 401.
- A interface percorreu as cinco páginas e a segunda janela de compassos reconhecidos. O cancelamento do processo nativo terminou e liberou a seleção de arquivo; a partitura atual foi preservada.
- O MusicXML real exportado pelo Audiveris atravessou `ScoreWorkspace.openOmr`, a construção da linha de tempo e exportação/reimportação estrita: duas partes, 83 compassos e 160080 ticks preservados.
- O aceite pela interface real também abriu o rascunho no editor sem erro. A nova diagramação editável gerou 11 páginas; a última foi selecionada e exibiu os compassos finais. Isso não altera as cinco páginas do PDF original. Captura de aceite: `.impeccable/review/pdf-opened-score.png`.
- Inspeção ampla (1280×900) e compacta (390×844), sem transbordamento horizontal. Imagem original carregada, colunas/empilhamento confirmados e console sem avisos ou erros. Capturas em `.impeccable/review/pdf-desktop.png`, `pdf-compact.png` e `pdf-compact-detail.png`.
- O detector foi executado uma vez com análise reduzida por ausência dos módulos de parser. Seu alerta sobre imagem sem `src` literal é um falso positivo no binding Angular condicional. Ele não avaliou contraste computado.

O executável atualizado fica em `apps/desktop/build/native/pdf-20261011`, com ZIP, checksum e `build-verification.json` ao lado. O registro externo contém a conferência do conteúdo e o teste de inicialização do EXE. O pacote portátil permanece sem assinatura e deve ser usado com sua pasta completa.

### Revisão independente — ship

| Achado material | Veredito final |
| --- | --- |
| Aceite multiparte incompatível | resolved — abertura real na Partitura confirmada |
| Fórmula explícita em excessos consecutivos | resolved — substituição local e regressão conferidas |
| Prévia republicada após leitura do JSON cancelada | resolved — guarda após leitura e teste específico |
| Token preservado ao cancelar trabalho ativo | resolved — prévia correspondente invalidada |
| Tipografia e superfícies | Compatíveis com a workstation existente |

Nenhum achado material pendente no escopo revisado. O veredito da interface não substitui o teste separado do pacote nativo. Som físico e microfone não foram exercitados nesta etapa.

## Referências técnicas

Integração conferida contra a [CLI oficial do Audiveris](https://audiveris.github.io/audiveris/_pages/guides/advanced/cli/) e o [código de BookManager da versão 5.11.0](https://github.com/Audiveris/audiveris/blob/5.11.0/app/src/main/java/org/audiveris/omr/sheet/BookManager.java). A chamada permanece em batch, com exportação MusicXML e compressão desativada.

## Registro da apresentação

A apresentação da importação estende a workstation existente em modo **Operate**: selecionar a origem, revisar o reconhecimento e abrir no editor. A autoridade de identidade é [workstation-design.md](workstation-design.md), com valores e estados definidos em [styles.scss](../../frontend/src/styles/styles.scss). Permanecem as superfícies carvão e o destaque dourado do tema escuro, os neutros quentes e dourado do tema claro, Inter e JetBrains Mono locais, separadores discretos e controles consistentes. O painel consome os tokens e aliases existentes; este registro descreve a superfície de importação, sem estabelecer uma nova identidade global.

O [template de importação](../../frontend/src/app/features/import/import-panel.html) apresenta “1 · Selecionar origem”, “2 · Revisar reconhecimento” e “3 · Abrir no editor”. O reconhecimento nativo usa um elemento `progress` indeterminado, sem porcentagem, e textos para leitura/validação, reconhecimento no Audiveris e preparação do rascunho. “Cancelar reconhecimento” permanece disponível durante o processamento e fica desabilitado quando o cancelamento é confirmado; a mensagem seguinte informa o encerramento e a liberação dos arquivos. “Audiveris encontrado” indica disponibilidade do executável, sem confirmar a qualidade musical do resultado.

A prévia explicita os avisos de ritmo e informa que fórmulas ou pausas provisórias não confirmam o original. A lista de avisos e os símbolos não preservados aparecem antes do aceite. A caixa de confirmação exige declarar a comparação com o original e a revisão dos avisos, aceitando abrir o rascunho para corrigir notas, compassos e eventuais fórmulas ou pausas provisórias. A abertura fica desabilitada até esse aceite e durante operações ocupadas. A confiança por nota no OMR é declarada indisponível.

A comparação mantém dois fluxos independentes: o original e a lista de eventos reconhecidos por compasso. O [estilo do painel](../../frontend/src/app/features/import/import-panel.scss) usa duas colunas acima de 900px e empilha as áreas em larguras de até 900px. A lista mostra parte, número do compasso e eventos, em grupos de 25 compassos por página, com anterior/próximos e indicação do intervalo. Essa paginação não sincroniza a página do PDF original com os compassos.

O PDF original tem anterior/próxima e indicação própria de página. A [lógica do painel](../../frontend/src/app/features/import/import-panel.ts) solicita uma página por vez à API local, recebe PNG e o apresenta por URL de blob; limpa a imagem anterior e descarta respostas de gerações já invalidadas. [OmrPdfPreview.java](../../modules/arpeon-omr/src/main/java/org/arpeon/omr/OmrPdfPreview.java) usa o PDFBox existente para gerar o PNG com maior dimensão limitada a 1600px. A comparação não usa iframe nem exige nova dependência. O link “Abrir arquivo original para comparar” permanece disponível para o PDF local, além da imagem da página; a imagem selecionada como origem também é exibida no painel.

Os estados e o suporte descritos abaixo estão presentes no template, na lógica e nos estilos citados:

| Situação | Apresentação e suporte implementados |
| --- | --- |
| Carregamento | Reconhecimento com `role="status"` e `aria-live="polite"`; carregamento do PNG informa “Preparando a página original…”. Anterior/próxima do PDF ficam desabilitados enquanto a página é preparada. |
| Erro e diagnóstico | Explicação curta com `role="alert"`. Quando a falha nativa contém detalhes após a primeira linha, eles ficam em `details` inicialmente recolhido, sob “Detalhe do Audiveris”, com texto quebrável e rolagem limitada. |
| Nova tentativa | Falha na página oferece “Tentar exibir página novamente” para a mesma página e orienta abrir o original. A nova solicitação limpa o erro anterior; o botão fica desabilitado durante o carregamento. |
| Cancelamento e descarte | Mensagens de estado acompanham o cancelamento; descartar limpa a prévia e o original local. A partitura atual só é substituída após revisão aceita. |
| Controles desabilitados | Seleção de arquivo e ações de análise usam o estado ocupado; abertura também depende do aceite e da sessão. As duas paginações desabilitam os respectivos controles nos limites. O estilo global usa opacidade reduzida e cursor de indisponibilidade. |
| Teclado e paleta | Botões, links, checkbox e `details`/`summary` são elementos nativos. A origem selecionada expõe `aria-pressed`; regiões têm rótulos e a imagem do PDF identifica página e total no texto alternativo. O foco visível herda contorno dourado de 2px com afastamento de 2px. Avisos, erros e disponibilidade usam as cores semânticas incumbentes, acompanhadas de texto. |
