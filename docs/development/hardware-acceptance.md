# Registro de aceitação em hardware

Preencha uma cópia por combinação de sistema operacional, dispositivo de entrada,
saída MIDI e navegador. Marque `N/A` quando o equipamento não existir; não substitua
ensaio real por fixture sintética. Não grave nem anexe áudio, URLs privadas, nomes de
usuário ou caminhos locais.

## Ambiente

| Campo | Resultado |
| --- | --- |
| Data/hora e fuso | |
| SO e versão | |
| JDK/runtime | |
| Navegador e versão | |
| Microfone/interface e taxa selecionada | |
| Sintetizador/dispositivo MIDI | |
| Audiveris e versão (se usado) | |
| Partitura/licença ou referência autorizada | |

## Captura e afinador

Para cada cenário, registre o valor de referência conhecido, desvio medido, latência
observada e resultado. `A4 = 440 Hz` pode ser usado como referência sintética acústica
de teste, desde que a origem do tom esteja documentada.

| Cenário | Referência | Resultado/erro em cents | Latência (ms) | Observação |
| --- | --- | ---: | ---: | --- |
| Permissão concedida/negada | | | | |
| Nenhum dispositivo | | | | |
| Tom sustentado em volume baixo/alto | | | | |
| Silêncio e ruído ambiente | | | | |
| Vibrato e harmônicos fortes | | | | |
| Ataques rápidos e notas repetidas | | | | |
| Desconectar/reconectar dispositivo | | | | |
| Iniciar/parar repetidamente | | | | |
| Fechar aba/launcher durante captura | | | | |

## MIDI, seguimento e OMR

| Cenário | Esperado | Observado | Falsos saltos/discordâncias | Resultado |
| --- | --- | --- | --- | --- |
| Timbre da parte e nota de referência | | | | |
| Seguimento em andamento inicial | | | | |
| Mudança de andamento | | | | |
| Motivo repetido/ambíguo | | | | |
| OMR: página/compasso comparado | | | | |

## Encerramento

- Captura e reprodução foram liberadas após parada/fechamento: `sim` / `não`.
- Foram observados erros reproduzíveis? Inclua passos sem dados pessoais:
- Limitações do equipamento ou teste:
- Regressão/fixture segura criada (se possível):
- Resultado geral: `aprovado` / `falhou` / `inconclusivo`.

Um relatório `aprovado` cobre somente os equipamentos e cenários informados. Não
generalize resultados entre plataformas nem publique precisão ou latência como garantia
sem um corpus maior e independente.
