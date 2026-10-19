# Entrega — fundação da central de configurações

Execução local: Windows x64, 18–19/10/2026. Versão mantida em 0.1.2; changelog em
Unreleased. Escopo: primeira entrega das fases 0/1 do ecossistema. Contas,
multiperfis, pesquisa federada e comunidade ainda não estão implementados.

## Implementado

- Central pesquisável, sem distinção de maiúsculas ou acentos, com categorias
  funcionais de interface, partituras/estudo, áudio/dispositivos e atualizações.
- Tema, painéis e zoom usam os serviços existentes. Leitura tem estado único
  compartilhado com o editor; nomes, idioma, oitavas, seleção e acompanhamento
  podem ser alterados nas duas superfícies sem divergência.
- Mudanças do papel atualizam o renderer da partitura aberta. A central e as
  ferramentas de áudio têm identificadores distintos para a entrada.
- Restauração de interface/layout ou leitura/papel sem alterar outros escopos.
  Entradas de áudio, consentimentos e projetos não são apagados.
- Escritas de preferências validam os valores recebidos antes de confirmar
  sucesso; respostas divergentes mantêm erro e permitem nova tentativa.
- Central carregada sob demanda em chunk próprio. Mesmos tokens, fontes,
  controles compactos e diálogo nativo do aplicativo.
- Auditoria, plano, modelo/migração futura, matriz de fontes e ADR 0009.

## Arquivos e módulos

- `frontend/src/app/features/settings/`: componente, template, estilos e testes.
- `frontend/src/app/core/score-reading-preferences.ts`: estado compartilhado.
- `frontend/src/app/core/desktop-preferences.ts`: validação/confirmação de escrita.
- `frontend/src/app/features/score/score-workspace.ts`: consumo do mesmo estado
  e invalidação do renderer quando muda o papel.
- Shell e `AudioPreparation`: integração e identificador contextual.
- Stores Java, formato dos projetos e schema 1 de preferências preservados.

## Validação local

Ferramentas: Node 24.21.0, JDK 25 e Gradle 9.8.1 disponíveis no projeto. O Node
20 do PATH não satisfaz Angular e foi substituído somente no ambiente dos comandos.

| Comando/verificação | Resultado |
| --- | --- |
| `npm run format:check` | Passou |
| `npm run lint` | Passou; sem suprimir a regra de non-null assertions |
| `npm run typecheck` | Passou para app e specs |
| `npm test` | 165 testes em 30 arquivos passaram |
| `npm run build` | Passou; chunk da central 12,02 kB; inicial 565,46 kB, aviso de 500 kB mantido, abaixo do erro de 750 kB |
| `node scripts/check-licenses.mjs` | 586 entradas npm fixadas conferidas; sem nova dependência de produto |
| `./gradlew.bat build --warning-mode=fail` | Passou; 69 tarefas, 19 executadas/50 atualizadas; 85 testes desktop sem falhas, erros ou skips; suites de módulos reutilizadas do cache |
| `./gradlew.bat :apps:desktop:installDist --warning-mode=fail` | Passou com os assets Angular finais; distribuição de desenvolvimento, não instalador homologado |
| Aceitação com Chromium e `DesktopServer` real | Passou; perfil isolado, MusicXML real, gravação em disco, processo novo e contexto de navegador novo; sem erros de página |
| Detector visual | Executado uma vez; zero achados no modo regex degradado. Sem parsers HTML/CSS, não verificou contraste calculado nem seletores |
| Revisão visual independente | PASS, zero achados materiais abertos após corrigir a pesquisa por rótulos/plurais e acrescentar teste de regressão |

Os testes novos verificam pesquisa/estado vazio, controles de interface,
persistência autenticada, reabertura sem cache do navegador, compartilhamento de
leitura, migração dos campos legados, restauração por categoria, bloqueio do áudio
durante captura e consentimento explícito. Uma resposta de gravação divergente é
rejeitada e uma nova tentativa preserva os últimos valores. O teste do editor
verifica a atualização do renderer a partir do estado compartilhado.

## Roteiro de aceitação executado

1. Abrir servidor Java com `arpeon.dataDir` isolado e distribuição atual.
2. Abrir a URL de sessão no Chromium; importar `fixtures/musicxml/first-notes.musicxml`.
3. Abrir Preferências, pesquisar `PORTUGUES`, habilitar nomes/oitavas, selecionar
   português e papel escuro; conferir persistência e nomes Dó na partitura aberta.
4. Fechar pelo botão acessível, reabrir, limpar a pesquisa preservada e escolher
   tema claro e zoom 125%; conferir o arquivo durável.
5. Inspecionar 1280 × 840, 800 × 600 e 390 × 844, temas claro/escuro.
   Capturas locais em `.impeccable/review/{desktop,compact,light,mobile}.png`.
6. Fechar navegador e processo; iniciar outro servidor no mesmo diretório.
   Usar novo contexto sem localStorage e bloquear rede externa, preservando loopback.
7. Conferir tema, zoom, leitura, idioma e papel recuperados; restaurar leitura e
   papel, conferindo que tema claro e zoom 125% permanecem.

O teste usa o frontend de produção e o bridge real em Chromium headless, não uma
nova homologação da janela JCEF/instalador. Não valida dispositivos físicos,
qualidade acústica, leitor de tela ou todos os fluxos do ecossistema. Uma tentativa
inicial da ferramenta de navegador não alcançou o loopback da sandbox; a aceitação
usou navegador e servidor no mesmo processo de orquestração fora dessa restrição.

## Git e distribuição

Branch específica: `codex/settings-foundation`. Auditoria iniciada em `c54c590`;
o incremento concorrente de CI `8b80854` foi preservado e já estava em `main` e
`master` antes do commit desta entrega. Commits/push/PR/merge são reportados com
identificadores confirmados no fechamento da entrega, sem antecipar sucesso.

Nenhuma tag/release pública ou instalador foi produzido nesta fatia. Permanecem
os gates reais de redistribuição, Windows limpo, atualização assinada e áudio
físico registrados em `packaging/release-readiness.json`. Não alterar esses gates
nem declarar a plataforma completa com base nesta central.

Próxima entrega coerente: identidade local e perfis, com migração recuperável e
isolamento de biblioteca/histórico/preferências antes de implementar busca.
