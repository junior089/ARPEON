# Melhorias implementadas — etapa 3

Data: 11 de outubro de 2026. Continuação da [etapa 2](melhorias-etapa-2-2026-10-10.md), atendendo à biblioteca R08 do [relatório de melhorias](relatorio-melhorias-2026-10-10.md).

A biblioteca permite encontrar e organizar projetos salvos sem trocar a partitura durante uma renomeação ou criação de cópia. O executável Windows é regenerado ao concluir a implementação, conforme solicitado.

## Entregas

- Busca por nome e arquivo de origem, ignorando maiúsculas e acentos; ordenação por atualização recente ou nome.
- Oito projetos por página, contagem de resultados, indicação do projeto aberto e abertura direta pela linha do projeto.
- Menu junto ao projeto selecionado para renomear, duplicar a versão salva ou excluir. A confirmação de exclusão identifica o projeto e informa a remoção de seu histórico de análise.
- Formulário de nome com limite de 200 caracteres e rejeição de nomes vazios ou caracteres de controle. Uma falha mantém o rascunho para nova tentativa.
- Nome da biblioteca independente do título musical: renomear não altera o MusicXML. Salvamentos posteriores preservam o nome escolhido.
- Cópias com identificador próprio, revisão e proveniência preservadas. Editar e salvar uma cópia não modifica o original.

## Persistência e limites

O manifesto permanece na versão 1, com um campo opcional de nome. Projetos antigos continuam usando o título musical até receberem um nome específico. As operações reutilizam a gravação durável e a verificação de integridade do projeto; falhas de escrita preservam o original.

Duplicar utiliza a versão já salva, conforme explicado no formulário. Alterações ainda não salvas e histórico de análise não entram na cópia. Renomear e duplicar não mudam o projeto ativo, a revisão aberta, o transporte ou a captura de áudio. A biblioteca mantém o limite existente de 500 projetos.

Favoritos, último acesso, filtros por instrumento, projeto de demonstração e orientação de estudo a partir da análise continuam pendentes. Não foram reavaliados microfone físico, latência acústica ou reconhecimento OMR nesta etapa. As correções anteriores de paginação e cursor estão incluídas no pacote atualizado.

## Verificação

- Frontend: 54 testes, lint, tipos, formatação e build de produção.
- Java: 147 testes e build completo com avisos tratados como erros.
- Testes cobrem busca, ordenação, paginação, abertura, validação de nomes, falhas com preservação do rascunho, autenticação HTTP, compatibilidade de projetos antigos, nomes após reinício, proveniência, integridade e independência das cópias.
- Conferência com servidor Java real e perfil de teste isolado: busca pelo arquivo e sem acento, abertura, renomeação, salvamento posterior e duplicação mantendo a partitura original aberta. Uma nota da cópia foi alterada e salva; os arquivos confirmaram XML distinto e original preservado.
- Layout conferido em 1280 × 800 e 390 × 844. O formulário aparece junto ao projeto selecionado; na janela compacta, o explorador abre pelo menu Exibir. A página manteve 390 pixels de largura sem transbordamento horizontal, e o console não apresentou avisos ou erros.
- Licenças: 586 entradas verificadas. Formatação e `git diff --check` aprovados.

O pacote desta etapa fica em `apps/desktop/build/native/library-20261011`. O arquivo `build-verification.json` ao lado do ZIP registra a conferência do conteúdo, checksum e inicialização do executável. Extraia o ZIP completo e mantenha o executável junto de suas pastas `app` e `runtime`.
