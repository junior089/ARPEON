# Changelog

Mudanças significativas a partir da evolução do ecossistema. Versões anteriores
continuam documentadas em `docs/releases/`.

## Unreleased

### Added

- Central de configurações pesquisável para interface, partituras/estudo, áudio
  e acesso às atualizações existentes, com restauração por categoria.
- Auditoria do ecossistema, plano incremental, matriz de fontes e ADR de escopos.

### Changed

- Leitura assistida compartilhada entre editor e central, preservando migração
  legada e preferências duráveis da instalação.

### Fixed

- Atualização do renderer quando o papel é alterado fora do editor.
- Confirmação dos valores de preferências recebidos após uma gravação.
- Identificador da entrada de áudio da central separado das ferramentas abertas.
