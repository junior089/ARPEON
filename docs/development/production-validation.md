# Evidências de preparação para produção

Execução local: Windows x64, 11/10/2026. Nenhum commit, push ou release remoto
foi criado. As alterações locais anteriores foram preservadas. Este relatório
registra engenharia e aceitação local; não certifica um release público.

## Implementação

Mantidos Java/Angular, módulos musicais, parser seguro, transporte e armazenamento
de projetos. Acrescentados diretórios de dados por sistema com preservação do
perfil legado, escrita durável dos históricos, lock de instância, logs rotativos,
fila HTTP limitada e erro de I/O consistente. A janela Swing/Chromium embarcada
substitui o navegador externo. Há proteção de alterações no encerramento,
“Salvar como” em novo projeto local e verificação manual de atualização no menu.

Audiveris é montado no build com Java, dependências corrigidas e OCR inglês/
português. O usuário não executa instalador do Audiveris nem configura runtimes.
O reconhecimento usa o processo real, exportação MusicXML, revisão e editor
existentes. Há fases assíncronas, timeout, cancelamento de árvore de processos,
logs limitados e remoção de arquivos temporários. A interface conserva dourado
pastel e remove explicações/títulos repetidos e o fluxo antigo de instalação OMR.

O build Windows produz MSI por usuário com atalhos, ícone original, UpgradeCode
estável, Java/Chromium/OMR, fontes, avisos, checksums e inventário por arquivo.
Aplicação e dados têm diretórios distintos. WinSparkle/Ed25519 está integrado,
mas o produto continua sem feed, chave ou verificações automáticas. O gerador
local de appcast verifica a assinatura e não publica nada. Gates bloqueiam
`-ForRelease` e appcast estável até existirem evidências adicionais.

## Execuções realizadas

| Verificação | Resultado e alcance |
| --- | --- |
| Java | 184 testes, zero falhas/skips; compilação com warnings como erros e formato passaram. Suites foram executadas com `--rerun-tasks`; após a correção do guard de atualização, a suite desktop foi executada novamente. |
| Angular | 136 testes em 24 arquivos passaram. Build de produção, tipagem app/spec, ESLint e Prettier passaram. |
| Licenças npm | 586 entradas fixadas verificadas pelo inventário existente. Não cobre termos nativos. |
| Vulnerabilidades | npm: zero avisos. OSV: 80 coordenadas Maven, incluindo os JARs do pacote Audiveris corrigido, sem avisos conhecidos. `audiveris.jar` é rastreado pelo MSI/source ZIP, não por coordenada Maven. Não é prova de ausência de vulnerabilidades nativas. |
| Janela nativa | Chromium carregou Angular, importou MusicXML, renderizou SVG, salvou automaticamente, criou cópia por Ctrl+Shift+S preservando o original e persistiu zoom 125%. O fluxo PDF pela interface executou Audiveris, exibiu o original renderizado, abriu C4/D♯4/A4 no editor e salvou a partitura reconhecida como terceiro projeto. Partitura inspecionada em 1280 × 840 e 800 × 600; Importar, Prática, Afinador e Análise em 1280 × 840. Capturas do renderer CEF. Não substitui aceitação integral de teclado/acessibilidade nem teste do diálogo nativo de download. |
| Runtime do launcher | App-image e instalação MSI abriram/fecharam janela própria com `JAVA_HOME` vazio, PATH apenas do Windows e perfil isolado. O host possui bibliotecas nativas prévias; não é VM limpa. |
| OMR real | PDF e PNG originais produziram C4, D♯4 e A4, com duas medidas e durações 1/4, 1/4, 1/2 e 1, e entraram no ScoreWorkspace. PDF corrompido rejeitado, processo nativo cancelado e jobs temporários removidos. A fixture não representa partituras complexas/degradadas nem valida metadados ausentes na imagem, como seu tempo original. |
| WinSparkle | DLL real inicializou e foi liberada, com verificações automáticas desligadas. Nenhum feed foi solicitado. |
| Ed25519 | Instalador real assinado com chave isolada de teste: verificador aceitou o original e rejeitou cópia adulterada. Appcast de testing gerado apenas em `.tools`. Não é assinatura Authenticode de produção. |
| MSI | Roteiro automatizado executou instalação, upgrade e desinstalação: códigos 0. Upgrade isolado de MSI 0.1.0 para 0.1.1 preservou 7 arquivos de três projetos reais (incluindo OMR) e preferências; desinstalação removeu o launcher e preservou os mesmos hashes. 0.1.1 foi apenas metadado do instalador de teste, não versão publicada. A execução manual anterior preservou 5 arquivos. O host não é VM limpa. |
| Artefatos | MSI/ZIP tiveram SHA-256 conferidos. ZIP foi extraído e seus 3.219 arquivos passaram pelo verificador de manifesto. O ZIP de fontes foi inspecionado sem entradas dotenv/chaves/certificados privados; o build agora também exclui esses tipos, preservando `.env.example`. Checksums não são assinatura de editor. |

Comandos repetíveis: `test-native-package.ps1` usa os JARs do pacote e fixtures
originais; `test-msi-lifecycle.ps1` exige perfil Windows descartável sem uma
instalação ARPEON existente e produz logs/resultados em diretório novo sob
`apps/desktop/build`. Não remove os dados do perfil de teste.

Evidências locais ignoradas pelo Git: `.tools/production/package-acceptance-ready-20261011-r2`
(pacote/UI/OMR), `.tools/production/ui-acceptance-complete-results` (fluxo OMR e autosave),
`apps/desktop/build/msi-lifecycle-final-20261011-r2` (roteiro MSI, logs e resultado),
`.tools/production/msi-lifecycle` (execução MSI manual anterior),
`.tools/production/zip-roundtrip-ready-20261011` (extração verificada), e
`.tools/production/dependency-audit-final.json` (OSV/inventário).
Não contêm credenciais de produção. As chaves de assinatura de teste permanecem
em `.tools` e não entram no source ZIP.

A primeira aceitação final disparou o atalho durante a conclusão do autosave.
O roteiro passou a aguardar o controle ficar habilitado, mantendo a comparação
da quantidade de projetos e a preservação do original. A primeira execução do
roteiro MSI revelou retornos COM vazios concatenados ao ProductCode; os retornos
foram suprimidos, a instalação de teste foi removida e o ciclo foi repetido com
sucesso. Também foram incluídas as duas visões do registro no guard de instalação
existente. Esses erros dos roteiros não foram considerados testes aprovados.

## Gates pendentes

- Revisão do JAI proprietário, avisos de todas as dependências embarcadas e
  fontes correspondentes das duas distribuições Java. O source ZIP do Audiveris
  não substitui fontes/licenças de todos os transitivos. Ver [revisão](../legal/native-distribution.md).
- Instalar/atualizar/desinstalar em VM Windows limpa sem runtimes ou redistribuíveis
  previamente instalados; conferir atalhos, escala de tela e preservação dos dados.
- Certificado Authenticode confiável e atualização completa entre dois releases
  em testing por HTTPS, incluindo download interrompido, assinatura inválida,
  falha de instalação, reinício e preservação. Ver [atualizações](updates.md).
- Microfone, sintetizador/MIDI, latência e uso prolongado com hardware real.
- Corpus OMR autorizado mais amplo; arquivos grandes e limites de memória;
  teclado/acessibilidade e escalas de tela além da inspeção local registrada.
- Empacotamento e aceitação reais em macOS/Linux. CI de módulos nessas plataformas
  não equivale a suportar seus instaladores.

Esses gates permanecem falsos em `packaging/release-readiness.json`. Não há
endpoint operacional fictício, release estável anunciado ou assinatura de editor
inventada. As fases 6–8 não estão concluídas para publicação pública.

## Revisão do ciclo de instalação — 12/10/2026, versão 0.1.1

As evidências de 11/10 acima pertencem ao empacotamento anterior. Esta revisão
mantém a stack e o trabalho local preexistente, substitui a autoria/UI MSI por
WiX explícito e acrescenta manutenção, preferências de atualização e publicação
assinada com gates. A versão do aplicativo, frontend e MSI passou a 0.1.1;
fixtures que variam apenas a versão do MSI continuam sendo testes de mecanismo.

- Java: 190 testes, zero falhas/skips; `build :apps:desktop:installDist
  --warning-mode=fail` aprovado. Log: `apps/desktop/build/final-java-validation-20261012-r2.log`.
- Angular: 147 testes, tipagem, ESLint, Prettier e build de produção aprovados.
  O bundle inicial de 504,87 kB excede o orçamento de aviso de 500 kB; o aviso
  continua visível e o limite não foi aumentado para ocultá-lo.
- WinSparkle real inicializou/encerrou sem consultar feed. Capturas nativas de
  Preferências/Atualizações em 1280 × 840 e 800 × 600; revisão visual corrigiu
  erro antigo de consulta que mascarava um resultado posterior bem-sucedido.
- WiX compilou com validação ICE; somente ICE91 é suprimido para o escopo por
  usuário. A tela de configuração expôs pasta, atalhos e ações via UIAutomation,
  foi capturada e cancelada sem instalação. Evidência:
  `apps/desktop/build/installer-ui-final-20261012`.
- Ciclo MSI normal com namespace `normal`: instalação limpa, mesma versão,
  reparo de arquivo ausente, upgrade sem passar INSTALLDIR, downgrade bloqueado,
  desinstalação e reinstalação aprovados. Foram preservados os hashes de 7
  arquivos de três projetos MusicXML/OMR e preferências, a opção de não criar
  atalho na área de trabalho e um arquivo não pertencente ao MSI dentro da pasta
  instalada. Uma única entrada em Aplicativos após upgrade. Launcher instalado
  abriu/fechou com Java/Node fora do PATH. As duas fixtures contêm o aplicativo
  0.1.1 e variam metadados MSI 0.1.0/0.1.1; não são dois releases publicados.
  Resultado/logs: `apps/desktop/build/lifecycle-normal-result-20261012`.
  `rollback=false` indica que essa execução não incluiu a falha induzida; ela
  não anula o rollback reprovado abaixo. Os produtos desse ciclo foram removidos.

**Rollback não aprovado.** Uma falha induzida após `RemoveExistingProducts`
restaurou os arquivos antigos e o marcador próprio, mas o Windows Installer
negou operações de rollback em seu registro interno. O log contém 81 erros
ignorados de registro e o ProductState não voltou a instalado (5). Presença do
executável não constitui sucesso. O roteiro agora verifica a transação realmente
exercitada, ProductState e ausência de erros ignorados. Evidências:
`apps/desktop/build/lifecycle-result-20261012-r3/rollback.log` e
`rollback-failure.json` no mesmo diretório. O gate `installerRecovery` está falso.

O comportamento é compatível com o relato aberto
[WiX #8574](https://github.com/wixtoolset/issues/issues/8574), mas essa relação
é uma hipótese diagnóstica, não prova de causa para este host. Não foram alteradas
ACLs do Installer, AlwaysInstallElevated ou políticas de rollback. A instalação
ARPEON real foi preservada; todas as mutações usaram identidades de teste.

Uma instalação isolada do teste de falha permanece incompleta em
`apps/desktop/build/lifecycle-result-20261012-r3/instalação com espaços — ARPEON`,
ProductCode `{72F9DC3A-B92A-4F32-0074-453DFDAC2451}`. Sua fonte foi reconstruída
durante o diagnóstico e o Windows SecureRepair rejeitou a reparação sem elevação
(1602); log `apps/desktop/build/repair-stranded-test-20261012.log`.
Não se apagaram registros internos nem arquivos gerenciados para fingir limpeza.
Os builders agora impedem sobrescrita de MSI e permitem um namespace novo com
`-TestIdentity -TestRunId` em cada execução local independente. A recuperação
por marcador exige produto/versão exatos; sua execução integral ainda é pendente.

Nenhuma credencial de produção, certificado confiável, VM limpa ou endpoint de
atualização foi fornecido. A configuração continua inativa e os gates impedem
publicação. Logs de teste não certificam assinatura pública ou robustez contra
queda de energia, reinício, bloqueio por antivírus e todas as escalas DPI.

### Artefatos finais desta revisão

Gerados em `apps/desktop/build/native/delivery-0.1.1-20261012`:
MSI 318.842.310 bytes, ZIP 382.969.681 bytes e app-image 778.017.260 bytes.
Nome, descrição e versões ProductVersion/FileVersion do launcher são ARPEON
0.1.1. Os arquivos SHA-256 foram conferidos. SHA-256 do MSI:
`129fef621afd2ca38c06a26a86d3a70cc574d13de4e5b173c17cbb3e4d2378f2`;
do ZIP: `04c7a5a7b555c31d4b33d8fcd0b282536df1778c785aa6097d80f99234714e7b`.

O ZIP foi extraído em `apps/desktop/build/zip-final-0.1.1-20261012`; os 3.219
arquivos inventariados passaram na verificação e o launcher extraído abriu/fechou
com Java/Node fora do PATH. O source ZIP tem 1.621 entradas, inclui o instalador
atual e não contém nomes de arquivos dotenv/chaves/certificados privados.
Seu conteúdo de código corresponde ao build; esta seção registra a aceitação
posterior à geração do artefato e não foi retroativamente inserida no MSI.

WinSparkle real inicializou/encerrou com consentimento desligado. A interface
final 0.1.1 foi conferida nas duas resoluções em
`apps/desktop/build/maintenance-final-0.1.1/captures`. O MSI canônico detectou
a instalação real 0.1.0 por usuário, mostrou “Atualizar instalação” e a pasta
registrada; a tela foi capturada e cancelada **antes** de qualquer atualização
em `apps/desktop/build/legacy-detection-final-0.1.1`. Isso comprova detecção/UI,
não execução de uma atualização sobre a instalação real, que foi preservada.

A assinatura Ed25519 offline do MSI final, com chave isolada de teste, aceitou
o original e rejeitou cópia adulterada; resultado em
`apps/desktop/build/offline-signature-final-0.1.1.json`. Não é Authenticode de
produção, appcast publicado ou aceitação de download por HTTPS. O verificador
também rejeitou checksum incorreto, traversal, caminho absoluto e entradas
duplicadas em `apps/desktop/build/inventory-negative-20261012/result.json`.

Para a limpeza do teste incompleto foi preparado e conferido, sem mutações,
`apps/desktop/build/recover-isolated-test-20261012.ps1 -CheckOnly`. O roteiro
exige marcador, pasta, nome, código e versão correspondentes, e usa apenas
reparação/remoção pelo MSI; a execução com elevação depende do UAC do Windows.
O pedido de elevação foi cancelado pelo Windows antes de iniciar o processo
elevado. A instalação isolada continua pendente de recuperação/remoção; não
houve alteração administrativa nem nova tentativa após o cancelamento.
A remoção do aplicativo preserva dados por padrão; ainda não oferece apagar
todo o perfil pessoal a partir do desinstalador. Essa ação destrutiva não foi
improvisada em uma custom action. A validação de primeiro upgrade do MSI legado,
recovery sem elevação, atalhos executados pelo shell, DPI/teclado/leitor de tela
integrais, rede interrompida e VM limpa continua necessária para publicação.

### Preparação da pré-release de código-fonte

A publicação do código 0.1.1 é separada da distribuição dos runtimes Windows.
README, captura real, notas de versão e scripts acompanham as fontes; MSI/ZIP
de aplicação e feeds permanecem sujeitos aos gates acima. A verificação de
arquivos para publicação não encontrou os padrões de chave privada, token GitHub
ou access key AWS examinados. Essa varredura não é uma garantia universal de
ausência de segredos.

A primeira CI em ambiente limpo detectou metadados BOM do JUnit ausentes no
inventário. Os pares POM/module foram completados com SHA-256 conferidos contra
os checksums oficiais do Maven Central, sem desativar a verificação. O build
Java foi repetido com GRADLE_USER_HOME vazio: aprovado em 46 segundos, 190 testes
sem falhas/skips. Log local: `apps/desktop/build/publication-cold-gradle.log`.
A permissão executável do wrapper Unix foi corrigida. O workflow de publicação
usa a branch padrão real do repositório para o feed; não assume `main`.
