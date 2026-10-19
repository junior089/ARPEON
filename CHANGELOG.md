# Changelog

Mudanças significativas a partir da evolução do ecossistema. Versões anteriores
continuam documentadas em `docs/releases/`.

## Unreleased

### Added

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

- Manifesto de projeto schema 3 com original transacional e origem inicial
  imutável; schemas 1/2 permanecem legíveis sem reconstruir originais.
- Estúdio e seletor de perfis carregados sob demanda após resolver a identidade local.
- Leitura assistida compartilhada entre editor e central, preservando migração
  legada e preferências duráveis da instalação.

### Fixed

- Atualização do renderer quando o papel é alterado fora do editor.
- Confirmação dos valores de preferências recebidos após uma gravação.
- Identificador da entrada de áudio da central separado das ferramentas abertas.
