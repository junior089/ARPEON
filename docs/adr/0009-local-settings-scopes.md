# ADR 0009 — Central local e escopos de preferências

Status: aceito para a primeira entrega do ecossistema.

Evolução: o [ADR 0010](0010-local-account-profile-sessions.md) implementa os
escopos de conta/perfil descritos como futuros neste registro. Interface/leitura
agora pertencem ao perfil nas sessões de produção; dispositivos continuam locais.

## Decisão

Reutilizar `PreferencesStore`, `AudioPreferencesStore`, `UpdatePreferences` e o
bridge autenticado. Manter schema 1 e migração de `arpeon.theme.v1` e
`arpeon.layout.v1`: um arquivo durável válido prevalece sobre o cache do navegador.
O cache não é a persistência principal porque a porta local muda por execução.
Não migrar para SQLite antes de definir identidade e migração de perfis.

A central Angular é um componente próprio com pesquisa e categorias funcionais.
`Appearance`, `WorkspaceLayout`, `ScoreReadingPreferences` e `AudioSettings`
compartilham o estado com as ferramentas contextuais. A assistência de leitura
deixa de pertencer ao ciclo de vida do editor. Mudanças de papel invalidam a
renderização da partitura aberta. Escritas de interface são serializadas e a
resposta deve confirmar os valores enviados antes de remover o aviso de erro.

## Escopos e precedência

1. Padrões do aplicativo preenchem campos opcionais legados ausentes.
2. Preferências duráveis da instalação local definem interface/leitura; seleção
   de entrada e referência pertencem ao dispositivo, consentimento de atualização
   pertence à instalação.
3. O manifesto do projeto define instrumento, mixer e opções de ensaio; restaurar
   a interface ou leitura não altera o projeto, áudio ou consentimento.
4. Estado temporário (janela compacta, posição, captura e transporte em execução)
   não se torna preferência por si só. Ajustes explícitos são persistidos pelos
   comandos existentes.

Conta e perfil são escopos futuros, ainda não implementados. A migração de perfis
deverá atribuir leitura/estudo ao perfil e deixar dispositivos na instalação,
sempre com IDs estáveis e sem herdar conexões privadas. Não fingir isolamento
enquanto os dados ainda pertencem à instalação.

## Consequências

A primeira central oferece Interface e acessibilidade, Partituras e estudo,
Áudio e dispositivos, e acesso ao gerenciamento real de Atualizações. Categorias
de conta, comunidade, fontes e sincronização serão adicionadas com seus domínios.
Não há endpoints vazios, permissões restauradas ou consentimentos implícitos.
Restaurar padrões é por categoria, reversível e não apaga projetos.
