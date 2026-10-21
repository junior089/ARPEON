# Changelog

Mudanças significativas a partir da evolução do ecossistema. Versões anteriores
continuam documentadas em `docs/releases/`.

## Unreleased

### Added

- Seleção inicial Mutopia: Allemande BWV 1013, com licença/crédito, evidência
  revisada, hashes do pacote/MIDI, prévia musical e aceitação explícita.
- Original MIDI da seleção Mutopia preservado no projeto, com download privado
  e integridade; cache público limitado, backoff e cancelamento da espera.
- Identificação bibliográfica local com citação obrigatória e histórico de
  correções/retiradas, separada da origem inicial e sem inferir direitos.
- Capacidades executáveis das fontes, consultadas na importação: Commons MIDI
  por URL e alternativas manuais; ações ainda indisponíveis ficam explícitas.
- Origem inicial tipada na biblioteca, com obra/arranjo, edição desconhecida
  explícita, direitos não avaliados e hashes separados da revisão salva.
- Originais verificáveis de novas importações MusicXML/XML/MXL/MSCZ, preservados
  com o projeto e disponíveis para download autenticado no perfil proprietário.
- Conta local e perfis de estudo com bibliotecas, históricos, preferências e
  bancos de sons independentes, limite configurável com padrão oito, seleção na
  abertura, instrumentos, edição, arquivamento e retomada por adesão explícita.
- Migração por cópia verificada e sessões loopback separadas durante a troca de perfil.
- Central de configurações pesquisável para interface, partituras/estudo, áudio
  e acesso às atualizações existentes, com restauração por categoria.
- Auditoria do ecossistema, plano incremental, matriz de fontes e ADR de escopos.

### Changed

- Manifesto de projeto schema 5 com original MIDI da seleção revisada, origem inicial
  imutável e histórico bibliográfico; schemas 1–4 permanecem legíveis sem
  reconstruir originais ou identificações.
- Estúdio e seletor de perfis carregados sob demanda após resolver a identidade local.
- Leitura assistida compartilhada entre editor e central, preservando migração
  legada e preferências duráveis da instalação.

### Fixed

- Vite do builder Angular resolvido em 8.3.4 para corrigir os avisos de segurança
  do launch-editor, sem downgrade do Angular ou supressão do gate de auditoria.
- Salvamentos antigos em memória preservam o histórico bibliográfico mais
  recente; duplicação e salvar como novo conservam os vínculos de origem.
- Cooldown e backoff da Wikimedia permanecem ativos ao trocar de perfil.
- Atualização do renderer quando o papel é alterado fora do editor.
- Confirmação dos valores de preferências recebidos após uma gravação.
- Identificador da entrada de áudio da central separado das ferramentas abertas.
