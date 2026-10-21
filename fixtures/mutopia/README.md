# Mutopia — seleção revisada para importação MIDI

Arquivo real do Mutopia Project, revisado em 20/10/2026. Não é áudio gravado nem
fixture inventada. Nenhum arquivo LilyPond é executado pelos testes ou aplicativo.

- Obra: Partita em lá menor para flauta solo, BWV 1013, J. S. Bach.
- Seleção habilitada: Allemande, membro `bwv1013.mid` do pacote original.
- Página: <https://www.mutopiaproject.org/cgibin/piece-info.cgi?id=1528>.
- Crédito: Mutopia Project; edição de Jacob Shapiro, Mutopia-2017/10/31-1528.
- Declaração da edição: `Public Domain`, no cabeçalho/rodapé de `bwv1013.ly` e
  na página oficial; <https://creativecommons.org/licenses/publicdomain/>.
- Política: <https://www.mutopiaproject.org/legal.html>.
- Fonte LilyPond: <https://github.com/MutopiaProject/MutopiaProject/blob/2144afd6f52d56c5b6995b8b589ef1268b3139f0/ftp/BachJS/BWV1013/bwv1013/bwv1013.ly>.
- Download oficial: <https://www.mutopiaproject.org/ftp/BachJS/BWV1013/bwv1013/bwv1013-mids.zip>.

| Arquivo | SHA-256 |
| --- | --- |
| `bwv1013.ly` | `03dcdeab3ff17cb0ef331e0e96277c2c48450d68f0aad5c603870dfbf512c008` |
| `bwv1013-mids.zip` | `87923df54ebb8fad2782fba4146ee3c731f9cfe2a522af26e1ed07ab074f022e` |
| Membro `bwv1013.mid` (6.011 bytes) | `43016ce88c25e691143d392bf31e6efd12b478ebe03ff378396a4eed97af8702` |

O ZIP foi mantido exato, incluindo os três movimentos não habilitados. Estes
falham na conversão atual por notas atravessando barras do rascunho 4/4 e não
são oferecidos como importações concluídas. O aplicativo só extrai o MIDI da
seleção revisada, preserva seus bytes e pede revisão antes de criar o projeto.
A revisão Git fixa a evidência LilyPond; o MIDI compilado é fixado separadamente
pelos hashes do pacote e do membro, sem afirmar que foi reproduzido a partir
daquela revisão por uma compilação local.

A declaração da fonte não promove os direitos do projeto: permanecem
`NOT_REVIEWED`/`UNKNOWN`. Nenhuma licença é inferida apenas da acessibilidade.
