# Melhorias implementadas — etapa 4

Data: 11 de outubro de 2026. Continuação da [etapa 3](melhorias-etapa-3-2026-10-11.md), com a primeira entrega de R07 do [relatório de melhorias](relatorio-melhorias-2026-10-10.md).

Os resultados da análise agora ajudam a escolher o próximo estudo: cobertura junto dos percentuais, alturas com nomes musicais, indicação dos compassos com desvios e preparação de trechos A–B com andamento editável.

## Entregas

- Resultados atuais e relatórios salvos usam a mesma apresentação. Cobertura mostra notas correspondidas sobre esperadas; notas não identificadas e extras permanecem explícitas. Métricas indisponíveis mostram um traço.
- As alturas esperadas e tocadas aparecem em som real, com nome e oitava. MIDI fica em detalhes técnicos. A tabela mostra até 48 correspondências por página e usa índices a partir de 1 na leitura musical.
- Até três compassos são sugeridos pela proporção de notas com algum desvio ou sem correspondência. Passagens repetidas ficam separadas pela posição na execução; cada nota conta uma vez no total, mesmo com vários desvios. Altura, entradas e duração continuam independentes.
- Sugestões de erro musical exigem confiança de pelo menos 0,80. Confiança média menor que 0,80 e cobertura menor que 70% recebem orientação para conferir a captura. Essas regras de cautela são visíveis e não alteram o analisador nem constituem avaliação de proficiência.
- **Preparar trecho A–B** abre o modo Estudar, seleciona a parte e o compasso e deixa a reprodução parada. O andamento começa em 80% do andamento escrito analisado, limitado a 30–300 BPM, e pode ser alterado. O intervalo inclui continuações de notas ligadas.
- **Ver na pauta** seleciona a nota correspondente e sua digitação, desativa um A–B anterior para permitir navegar e preserva o andamento atual. Nenhuma dessas ações liga áudio ou microfone.
- O histórico guarda os critérios, transposição sonora, andamento escrito, locais musicais e hash do MusicXML canônico. Relatórios antigos permanecem legíveis, com aviso de que uma nova tentativa é necessária para preparar trechos.

## Proteção e limites

A preparação verifica no servidor o hash de origem, revisão, parte, transposição e conteúdo do MusicXML antes de mudar o transporte. O conteúdo também é conferido ao reabrir um projeto cujo contador de revisão voltou a zero. Parâmetros inválidos não mudam o transporte. Uma tentativa ativa impede a preparação; a autenticação local continua obrigatória. A partitura e o histórico não são alterados ao preparar estudo.

O A–B afeta a reprodução para estudo. Uma nova análise ainda avalia a parte inteira no andamento escrito; não avalia apenas o trecho ou o andamento escolhido para reprodução. Comparação entre tentativas, tendências e avaliação de trechos continuam pendentes. A análise permanece monofônica, com entradas estimadas e sem gravação de áudio. A orientação é uma interpretação transparente dos dados, sem nota geral ou promessa de diagnóstico musical.

## Validação

- Frontend: 68 testes, lint, tipos, formatação e build de produção. O andamento editado permanece na mesma tentativa e reinicia ao escolher outro relatório, inclusive depois de um campo vazio.
- Java: 152 testes; build completo com avisos tratados como erros.
- Testes novos verificam passagens repetidas de Badinerie, pausas antes da nota, continuações ligadas, seleção depois de carregamento assíncrono, rascunho de andamento, cautela de captura, cobertura, paginação e relatórios antigos.
- Testes de persistência preservam critérios e localizações após recarregar o histórico e rejeitam metadados inválidos. Testes HTTP verificam autenticação, tentativa ativa, parâmetros inválidos e preparação sem iniciar captura ou reprodução.
- Conferência com servidor Java real e relatório gerado por execução sintética em perfil isolado. Não foi realizado ensaio de microfone físico nem validada a eficácia pedagógica das sugestões.
- Conferência visual e de fluxo em 1280 × 800 e 390 × 844: sem transbordamento horizontal da página; preparação A–B com áudio parado; abertura da nota Ré♯ 4 e sua digitação; leitura de relatório antigo. Capturas ficam em `.tools/study-review`.
- Inventário de licenças: 586 dependências verificadas.
- Revisão independente da funcionalidade: **PASS**, após corrigir o vazamento do rascunho de BPM entre relatórios. O detector estático não apontou ocorrências, mas usou fallback regex por indisponibilidade dos módulos de parser; não verificou contraste computado. Evidências detalhadas ficam em `.tools/study-review/finish-review.md`.

O executável atualizado desta etapa fica em `apps/desktop/build/native/study-20261011-final`. O ZIP inclui Java, interface offline, código-fonte e licenças. O `build-verification.json` ao lado do ZIP registra checksum, conteúdo e inicialização real do novo EXE. É necessário manter a pasta extraída completa.

## Apresentação e interação

- O relatório herda a estação musical em carvão e dourado: texto claro, apoio em cinza e ação principal dourada. Inter mantém a leitura; JetBrains Mono destaca os valores das métricas.
- A leitura segue cobertura → cautelas, quando aplicáveis → métricas → sugestões em **Próximo estudo** → **Desvios por nota**, recolhidos inicialmente. Os dados técnicos ficam em uma expansão dentro dos detalhes por nota.
- As métricas passam de quatro para duas colunas em larguras de até 900 px. Os compassos sugeridos usam separadores planos, sem cartões individuais; textos e botões quebram linha. A rolagem horizontal fica confinada à tabela.
- O rascunho de BPM pertence à identidade do relatório: `saved.id` no histórico e `startsAtEpochMs` na tentativa atual. Atualizações da mesma identidade preservam a edição; uma nova identidade restaura a sugestão inicial. Campo vazio ou inválido bloqueia A–B, sem bloquear por si só **Ver na pauta**.
- O BPM editado só é aplicado ao acionar **Preparar trecho A–B**, seguindo a preparação parada descrita em **Entregas**; iniciar a reprodução exige uma ação posterior no transporte.
