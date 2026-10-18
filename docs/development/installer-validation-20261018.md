# Instalador Arpeon — validação local de 18/10/2026

Build de avaliação Windows x64, versão 0.1.2. A instalação normal existente e
as alterações anteriores do aplicativo foram preservadas. Nenhum release foi
publicado; os gates de produção continuam abertos.

## Resultado entregue

`apps/desktop/build/native/installer-premium-final-20261018/ARPEON-0.1.2-Setup.exe`
tem 320.585.728 bytes e embarca o MSI 0.1.2 previamente verificado, inventário,
interface WPF e executável pequeno de manutenção. O SHA-256 foi conferido:

```text
95efb6391c47cfe7f6c453192a1216f40e02ae2ffdb732a6cbb5da3c0cf9ac83
```

O mesmo diretório contém checksum e `ARPEON-Installer-Source.zip`. O Setup
final está **sem assinatura Authenticode**. O arquivo de fontes complementa
as fontes do aplicativo já incluídas no MSI; não contém certificados ou chaves.

Ondas Bézier se deformam continuamente; barras harmônicas e anéis mudam com as
etapas. Mensagens acompanham o contexto. Atualizar, reparar e desinstalar têm
textos próprios. Cancelamento mantém uma mensagem de preservação, sem sucesso
antecipado. O desinstalador registra sua entrada por usuário em Aplicativos do
Windows, confirma a remoção e usa o produto registrado pelo Windows Installer.

## Evidências

| Verificação | Resultado e alcance |
| --- | --- |
| Ciclo completo com o aplicativo real | **45 asserções aprovadas**, `apps/desktop/build/installer-motion-final-acceptance/result.txt`. Instalação, 3.219 hashes, registro, destinos dos atalhos, corrupção detectada, reparação, remoção pela interface própria, cache e cancelamento por eventos reais do MSI. Identidade de teste separada da instalação normal. |
| Movimento | GIF de 36 frames renderizados pela WPF; comparação de pixels confirmou mudança da geometria. Respeito à preferência de movimento está implementado no código; a preferência do sistema não foi alterada para este teste. |
| Interface | Capturas normal e compacta, foco e regiões vivas, capacidade recalculada ao trocar o destino e avisos de unidade indisponível. RenderTargetBitmap valida a árvore renderizada; não equivale a leitor de tela ou monitores físicos. |
| Espaço e permissões | Espaço insuficiente exercitado com metadado de tamanho contra espaço livre real; diretório de teste com escrita explicitamente negada e ACL restaurada. Não foi preenchida uma unidade física. |
| Manutenção | Entrada de remoção própria, cache offline, proteção contra exclusão da entrada atual por host obsoleto e remoção do cache após desinstalar. O executável pequeno real também iniciou em modo silencioso após a remoção e retornou 0. |
| Atualização normal e downgrade | Aprovados em `apps/desktop/build/installer-transactions-normal/result.json`, com dois pacotes mínimos de teste construídos pelo mesmo WiX. Arquivo alheio preservado e produtos de teste removidos. Essa fixture valida transações; não substitui atualização do pacote completo em VM limpa. |
| Falha de major upgrade | Em `apps/desktop/build/installer-transactions-acceptance/result.json`, os arquivos anteriores foram restaurados, mas o registro da versão anterior não voltou. A tentativa de recuperação nativa retornou 1602. Essa limitação existente foi reproduzida, **não corrigida nem aprovada**. As fixtures e logs isolados permanecem como diagnóstico. |
| Scripts e pacote | Compilação C# e sintaxe dos scripts aprovadas; inventário original de 3.219 arquivos verificado. SHA-256 do Setup final corresponde ao sidecar. |

Os testes do harness recompilaram as mesmas fontes contra o MSI de identidade
isolada. O Setup final usa o MSI normal imutável, com a mesma imagem 0.1.2.
O relatório foi registrado após o build; não foi inserido retroativamente no
MSI nem no ZIP de fontes que já havia sido gerado.

## Revisão visual independente

| Achado | Disposição final |
| --- | --- |
| Foco e anúncio dos avisos | Resolvido |
| Espaço livre desatualizado ao trocar pasta | Resolvido |
| Modo incorreto ao cancelar verificações | Resolvido |
| Mensagem de sucesso após cancelamento | Resolvido |
| Entrada e conclusão da desinstalação | Sem regressão visual observada |

A identidade, a composição e os estados foram aprovados na revisão visual.
Os limites das capturas continuam válidos: testes com leitor de tela, diferentes
monitores/DPIs, máquina limpa e interrupção de energia ainda são necessários.
Assinatura confiável, redistribuição dos runtimes, atualização assinada e
recuperação do registro após falha de major upgrade permanecem pendentes para
distribuição pública. Ver `packaging/release-readiness.json`.
