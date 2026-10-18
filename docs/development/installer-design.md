# Instalador Arpeon — Ouro e Carvão

## Direção e autoridade visual

O instalador integra a identidade existente do Arpeon: carvão (#101214), painel
(#191C1F), dourado pastel (#E6C989), texto (#F3F1EC), símbolo vetorial original
e Segoe UI nativa. A composição da referência separa identidade à esquerda e
operação à direita. É uma expressão desta superfície; os tokens e a tipografia
do aplicativo continuam descritos em `DESIGN.md`. O compromisso de produto é
preservar o trabalho musical e mostrar somente capacidades e medições reais.

A janela lógica tem 960 × 660, com tamanho inicial limitado à área útil e
composição escalada para janelas menores; o conteúdo funcional tem rolagem.
Painel de marca com cantos de 9px, botões de 6px e controles primários com altura
mínima de 42px formam a linguagem própria do Setup. Dourado identifica a ação
principal, a etapa atual e o progresso; textos secundários usam cinza (#ACAFB3).
Foco de teclado é visível. Confirmações e falhas aparecem na própria janela.

O grafismo muda com a etapa: ondas Bézier deformam seus pontos de controle nas
telas gerais e durante a operação; barras harmônicas animam verificação e
validação; anéis pulsam na preparação e uma vez na conclusão bem-sucedida.
O indicador orbital acompanha percentuais medidos quando disponíveis. As ondas
e os pulsos são sinais de atividade, não uma medição de trabalho concluído.
Mensagens contextuais alternam a cada sete segundos nas etapas intermediárias,
com textos próprios para atualização, reparação e remoção. Estados finais de
cancelamento e remoção mantêm a mensagem de preservação dos projetos.

As animações respeitam `SystemParameters.ClientAreaAnimation`; a animação
decorativa contínua também é desativada em alto contraste. Sem movimento, o
grafismo permanece estático e estado, instruções e progresso continuam em
texto. Controles WPF têm nomes acessíveis, foco, tabulação, Enter e Escape;
mudanças de etapa e avisos usam regiões vivas. O seletor de pasta conserva as
convenções do Windows. O manifesto declara DPI PerMonitorV2. Estes mecanismos
não constituem homologação com leitor de tela, alto contraste ou monitores
físicos em todos os DPIs.

## Arquitetura de instalação e manutenção

`ARPEON-<versão>-Setup.exe` é uma aplicação WPF x64 que embarca o MSI completo,
metadados, inventário SHA-256 e um `maintenance.exe` pequeno. Não depende de
Node, navegador, WebView2 ou downloads. Usa o .NET Framework/WPF do Windows
10/11; o manifesto é `asInvoker` e a instalação é por usuário.

O MSI WiX continua sendo a autoridade transacional: ProductCode, UpgradeCode,
propriedade dos arquivos, downgrade, atualização, rollback e registro nativo
permanecem sob o Windows Installer. O MSI também é distribuído separadamente
para instalação silenciosa e atualização pelo mecanismo existente. Abrir esse
MSI diretamente usa sua interface nativa; Setup, manutenção e desinstalador
interativos usam a interface WPF própria.

`packaging/windows/bootstrapper/InstallerEngine.cs` contém verificações,
extração, hashes, integração MSI, cancelamento e validação posterior.
`InstallerWindow.cs` coordena etapas, modos, recuperação e grafismo animado.
`Installer.xaml` define shell, símbolo vetorial, composição, controles e foco.
O código de testes não entra nos executáveis distribuídos.

Após o retorno nativo bem-sucedido, a validação prepara o cache em
`%LOCALAPPDATA%/<RegistryRoot sem Software>/InstallerCache/<ProductCode>`.
Ele contém `ARPEON.msi` completo para reparação offline e
`ARPEON-Maintenance.exe`, que carrega a interface e o inventário sem duplicar o
MSI dentro do executável. Para a identidade normal, RegistryRoot é
`Software\ARPEON`; identidades isoladas de teste usam sua própria raiz.

O Setup registra a entrada própria em
`HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\ARPEON.Setup.<ProductCode>`
com `WindowsInstaller=0`, `ModifyPath`, `UninstallString` e
`QuietUninstallString`. Assim, Aplicativos do Windows abre a manutenção ou a
remoção própria. A propriedade `ARPSYSTEMCOMPONENT=1` oculta somente a entrada
nativa do MSI para evitar duplicação na lista; não elimina o produto MSI nem
substitui sua autoridade transacional.

A manutenção pequena relança a si mesma como
`%TEMP%\ArpeonMaintenance-<ProductCode sem chaves>.exe --host`, liberando o
executável do cache para remoção. Reparação lê o MSI do cache e confere seu hash
antes de instalar. Remoção chama `MsiConfigureProductEx` com o ProductCode
registrado e estado ausente; não exige o arquivo MSI de origem nem a extração
do payload do Setup. O Windows Installer continua responsável por seu pacote e
seus arquivos registrados.

Depois de confirmar que o produto está ausente, a limpeza exclui a entrada
própria, os dois arquivos conhecidos do cache e seu diretório apenas se vazio.
Não percorre o destino nem o perfil pessoal recursivamente. Projetos, histórico
e preferências permanecem. O host pequeno em TEMP pode permanecer até ser
reutilizado ou limpo pelo Windows; falhas de limpeza de arquivos próprios são
registradas, sem promessa de remoção absoluta de todos os temporários.

## Fluxo, modos e precisão

1. Inicialização: ler metadados e inventário, sem espera artificial.
2. Boas-vindas: apresentar instalação nova, atualização ou manutenção conforme
   o registro. Na mesma versão, oferecer abrir, reparar ou desinstalar. Uma
   versão anterior produz textos e ações de atualização. A entrada de remoção
   abre seu próprio acolhimento e exige confirmação dentro da janela.
3. Verificação: conferir Windows, x64, instalação anterior, espaço real,
   permissões no destino e componentes locais. Falhas bloqueiam a sequência e
   oferecem repetição.
4. Personalização: escolher pasta local e atalhos. Instalação existente recupera
   o destino registrado; reparação conserva seus atalhos. Português do Brasil
   é o idioma implementado. A remoção não apresenta escolhas de instalação.
5. Preparação: revalidar destino, espaço e registro; para instalação/reparação,
   copiar o MSI por bytes reais e verificar SHA-256. Para remoção, confirmar o
   produto registrado sem depender de um MSI de origem.
6. Operação: executar `MsiInstallProduct` para instalar/reparar ou
   `MsiConfigureProductEx` para remover, com UI nativa silenciosa e eventos
   externos. Títulos e botões distinguem instalação, atualização, reparação e
   desinstalação.
7. Validação: após retorno 0/3010 de instalação/reparação, registrar manutenção
   e verificar tamanho/hash de todos os arquivos do inventário, versão, destino
   e alvos dos atalhos. Remoção confirma a ausência do produto registrado antes
   de limpar sua manutenção.
8. Conclusão: liberar abertura somente após validação. Retorno 3010 exige
   reinício e bloqueia abertura imediata. Remoção e cancelamento têm conclusões
   e ações próprias.

Os registros de `MsiSetExternalUIRecord` informam operação, direção, total e
incrementos. O percentual corresponde à fase atual do MSI e pode reiniciar entre
fases. Totais estimados ou ausentes exibem “Em andamento”, sem percentual.
Extração usa bytes e validação usa o inventário real. Não há ETA, downloads ou
percentuais fabricados. Cópia, atalhos, registro e finalização têm mensagens
próprias; animação decorativa não determina sucesso.

Cancelar durante uma operação exige confirmação e devolve IDCANCEL ao MSI em
uma mensagem apropriada. A janela aguarda o retorno nativo, inclusive rollback;
não termina o processo para simular cancelamento. Trechos protegidos e validação
posterior impedem fechamento até terminar. Falha posterior ao commit informa
“Instalado, mas ainda não validado” e oferece nova verificação ou reparação.

## Logs e recuperação

Logs de sessão ficam em
`%LOCALAPPDATA%/<RegistryRoot sem Software>/InstallerLogs` (normalmente
`%LOCALAPPDATA%\ARPEON\InstallerLogs`). Cada sessão tem identificador próprio.
O log MSI é criado primeiro em TEMP, acessível ao serviço do Windows Installer,
e arquivado ao final de cada operação com o identificador da sessão. Se não
puder ser arquivado, seu caminho temporário é registrado. “Ver registro” abre
o log nativo disponível ou o log da sessão.

Só o MSI temporário e o diretório de extração pertencentes à sessão entram na
limpeza do Setup; cache de reparação e logs arquivados têm finalidades próprias.
Se o cache ou o executável de manutenção forem perdidos, o Setup completo é o
caminho explícito de recuperação. Reparação offline depende da integridade do
MSI conservado; remoção depende do registro nativo saudável do Windows Installer.

## Build e validação repetível

`scripts/package-windows.ps1` produz a imagem, MSI e Setup. Quando a assinatura
está configurada, assina o MSI antes de o shell calcular e embarcar seu hash,
assina e verifica o executável pequeno antes de embuti-lo e assina o Setup
completo por último. `scripts/build-windows-installer.ps1` também gera MSI e
shell por padrão; `-SkipBootstrapper` permite separá-los.
`scripts/build-installer-shell.ps1` aceita imagem e MSI previamente verificados.
A invocação isolada desse script não assina o Setup final: a assinatura final
fica no empacotamento. `ARPEON-Installer-Source.zip` acompanha o executável com
fontes do bootstrapper, scripts de reconstrução/teste, licença e este documento.

Exemplo a partir de uma imagem 0.1.2 verificada; usar caminhos de saída novos:

```powershell
./scripts/build-windows-installer.ps1 -Package C:/pacote/ARPEON -Version 0.1.2 -OutputDirectory C:/saida/arpeon-build
./scripts/test-installer-shell.ps1 -Setup C:/saida/arpeon-build/ARPEON-0.1.2-Setup.exe -OutputDirectory C:/DEV/ARPEON/apps/desktop/build/installer-visual-novo
```

Para reconstruir somente o shell:

```powershell
./scripts/build-installer-shell.ps1 -Installer C:/saida/arpeon-build/ARPEON-0.1.2.msi -Package C:/pacote/ARPEON -Output C:/saida/arpeon-shell/ARPEON-0.1.2-Setup.exe
```

O ciclo de instalação/remoção deve usar identidade de teste isolada:

```powershell
./scripts/build-windows-installer.ps1 -Package C:/pacote/ARPEON -Version 0.1.2 -OutputDirectory C:/saida/arpeon-lifecycle -TestIdentity -TestRunId documentado-01
./scripts/test-installer-shell.ps1 -Setup C:/saida/arpeon-lifecycle/ARPEON-0.1.2-Setup.exe -OutputDirectory C:/DEV/ARPEON/apps/desktop/build/installer-lifecycle-novo -Lifecycle
```

O harness exige uma saída ainda inexistente dentro de `apps/desktop/build` e,
para `-Lifecycle`, identidade MSI isolada. Ele captura a interface e verifica
layout compacto, foco/avisos, execução MSI, validação de arquivos, classificação
dos modos, cache offline e entrada própria de manutenção. O ciclo corrompe um
arquivo próprio, repara, remove pela interface própria preservando um arquivo
alheio e solicita cancelamento em eventos reais de reinstalação. Asserções e
capturas ficam no diretório de resultado; somente `result.txt` emitido ao final
registra conclusão do harness. `assertions.txt` parcial não comprova aprovação.
O roteiro `test-msi-lifecycle.ps1` cobre o ciclo nativo entre versões isoladas.

O ciclo local em `apps/desktop/build/installer-motion-acceptance-r4` concluiu com
`44 assertions passed; lifecycle=True` em `result.txt`, incluindo instalação,
reparação, remoção pela interface própria, limpeza do cache dedicado e
preservação de arquivo alheio. O diretório contém capturas e `brand-motion.gif`;
`r3` conserva artefatos anteriores. O resultado se aplica ao executável testado
nessa revisão; mudanças posteriores exigem nova execução. Não se presume
aprovação a partir de capturas ou asserções parciais.

Máquina limpa, leitor de tela, monitores físicos, todos os DPIs, interrupção de
energia e cenários de falha permanecem limitados aos gates existentes.
Authenticode de produção, redistribuição, feed assinado e recuperação de
registro após falha de major upgrade continuam abertos até suas evidências
específicas. Esta documentação não os declara aprovados.

Referências da API:
[UI externa por registros](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msisetexternaluirecord),
[progresso nativo](https://learn.microsoft.com/en-us/windows/win32/msi/handling-progress-messages-using-msisetexternalui),
[remoção por produto registrado](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msiconfigureproductexw),
[logs](https://learn.microsoft.com/en-us/windows/win32/api/msi/nf-msi-msienablelogw).
