# Preparação para produção — 11 de outubro de 2026

## Diagnóstico anterior às alterações

O checkout contém alterações locais extensas anteriores a esta auditoria. Elas são a base de trabalho e devem ser preservadas. A análise inventariou código, testes, configurações, documentação, fixtures e workflows; arquivos gerados e ferramentas locais não são código do produto. Revisão estática não equivale à validação de todos os comportamentos.

Stack: Java 25, Gradle 9.8.1 com checksum do wrapper, Angular 22.2.1, TypeScript 6.0.3, Node 24, Gson 2.14.0, PDFBox 3.0.8, OSMD 2.2.0, JUnit 5.14.1 e Vitest 5.0.3. Versão do produto: 0.1.0; licença AGPL-3.0-or-later. Dependências Java e npm possuem lockfiles.

Há onze módulos Java: domínio, MusicXML, áudio, DSP, afinador, transporte, reprodução, importação, OMR, análise e avaliação ML offline. A composição está em `apps/desktop`; Angular utiliza uma ponte HTTP autenticada em loopback. Não há justificativa para trocar a stack ou reescrever os módulos musicais.

| Área | Evidência e risco | Decisão |
| --- | --- | --- |
| OMR | Processo Audiveris real, validação de PDF/imagem, exportação MusicXML, revisão explícita e cancelamento já existem. `AudiverisInstaller` baixa/extrai MSI no uso; o pacote não contém OMR. Descoberta em Program Files cria dependência acidental do ambiente. | Embarcar distribuição upstream e runtime no build; resolução exclusiva do pacote ou override de desenvolvimento explícito; remover instalador em runtime. |
| Desktop | `DesktopApplication` abre navegador padrão e mantém servidor aguardando encerramento. Não há janela com ciclo de vida próprio. | Janela Swing com Chromium embarcado, mantendo Angular e a ponte existente. Custo: tamanho do pacote e manutenção de segurança do Chromium. |
| Distribuição | jpackage gera app-image/ZIP com Java; faltam instalador, ícone nativo e teste de instalação/desinstalação. | Instalador Windows por usuário, identificador estável, atalhos, desinstalação e preservação de dados. |
| Atualizações | Nenhum mecanismo existente. | Biblioteca madura WinSparkle no Windows, assinatura Ed25519 e HTTPS; configuração inativa sem endpoint/chave de produção. Sem publicar releases. |
| Persistência | Projetos têm staging, fsync, backup, recuperação e hashes. Preferências são atômicas. Históricos fazem rename, mas não fsync. Caminhos `~/.arpeon` são repetidos. Não há exclusão de duas instâncias. | Centralizar diretórios, preservar perfis antigos, bloquear segunda instância e consolidar escrita durável sem alterar schemas. |
| Segurança | Host/Origin/token/CSP, XML sem entidades e limites de ZIP já existem. Pool HTTP tem fila ilimitada; algumas falhas de I/O encerram conexão sem resposta útil. | Limitar concorrência/fila, centralizar erro de I/O, logs persistentes e verificação de dependências/artefatos. |
| Manutenção | Importador MusicXML e DesktopServer têm ~1240 linhas; ScoreWorkspace ~1030. ImportPanel, ScoreSession e ScoreWorkspace concentram fluxos. | Extrações apenas para novas responsabilidades; evitar refatoração ampla de lógica musical testada. |
| Interface | Tokens de cor e tipografia, navegação, teclado, layout redimensionável e estados de áudio já existem. Persistem textos técnicos longos, status local redundante e fluxo de instalação OMR. | Correções direcionadas mantendo dourado pastel, ferramentas existentes e hierarquia. |
| Testes | Build Java passou em 11/10; parte das tarefas foi reutilizada do cache. Frontend executou 136 testes em 24 arquivos, todos passaram. OMR unitário injeta um runner, portanto não prova runtime distribuído. | Forçar execução após mudanças e acrescentar aceitação real de OMR/instalador/atualização. |
| Plataformas | CI contém matriz de três sistemas, mas não há evidência local de instalação em Linux/macOS. Microfone/MIDI dependem de hardware. | Windows x64 primeiro; demais plataformas explicitamente pendentes. |
| Licenças | Avisos dizem que Audiveris não é embarcado. Seu pacote contém dependências e runtime próprios, com obrigações adicionais. | Incluir source upstream, inventário e textos redistribuídos; não modificar Audiveris. Release público depende de revisar avisos e vulnerabilidades dos transitivos upstream. |

Não foi encontrada razão para remover módulos funcionais como “código morto”. A avaliação ML é experimental documentada e fica fora do runtime. Importações aproximadas e ajustes rítmicos de OMR são declarados como rascunho; não devem ser apresentados como transcrição fiel.

## Plano e critérios de conclusão

1. **Diagnóstico:** este documento, baseline executado e riscos registrados. Concluído.
2. **Fundação:** diretórios centralizados e compatíveis, escrita durável, exclusão de instâncias, erros/logs consistentes; testes de regressão e persistência passam.
3. **Audiveris:** runtime e fonte upstream com hashes fixados, nenhum download/instalador em runtime; PDF e PNG reais passam pelo processo embarcado e geram uma partitura revisável; cancelamento e limpeza verificados.
4. **Desktop:** janela própria, encerramento com proteção de alterações, abrir/salvar/salvar como/recentes, drag-and-drop e atalhos funcionando; regressões e aceitação da janela executadas.
5. **Interface:** remover instalação OMR, redundâncias e estados enganosos; inspecionar telas e teclado em resoluções distintas; paleta preservada.
6. **Distribuição:** build produz instalador e pacote íntegros, inclui runtimes/licenças/fontes e ícone; instalar, iniciar e desinstalar em perfil isolado preservando dados. Uma VM realmente limpa é um gate separado.
7. **Atualizações:** WinSparkle integrado e desligado por padrão, configuração validada, geração de appcast assinado e pipeline manual de artefatos sem publicação; testar atualização em ambiente isolado com preservação dos dados. Não inventar um feed operacional.
8. **Qualidade:** suites Java/frontend, checks de formato/tipos/licenças, auditoria de vulnerabilidades, build e documentação reproduzíveis; registrar exatamente execuções, falhas e gates ainda pendentes.

Nenhuma fase é concluída apenas por compilar. Assinatura Authenticode, publicação, atualização contra serviço real, VM limpa, hardware e sistemas não disponíveis permanecem gates explícitos; não são simulados nem declarados testados.

## Resultado da execução

O diagnóstico acima foi mantido como baseline. Fundação, montagem do Audiveris,
janela própria, simplificação de interface, MSI e infraestrutura inativa de
atualização foram implementados progressivamente. Testes executados e critérios
ainda pendentes estão em [produção: validação](production-validation.md).
Não se declara encerrada a preparação para release público: licenciamento
nativo, VM limpa, assinatura confiável, atualização HTTPS completa e hardware
continuam bloqueando publicação. Nenhuma alteração remota foi enviada.
