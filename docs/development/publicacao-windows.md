# Como habilitar a publicação Windows

Estado em 18/10/2026: a versão 0.1.2 pode ser compilada e testada como candidata
interna. Publicação assinada e atualização de clientes continuam bloqueadas.
O rascunho no GitHub permite ao proprietário revisar os arquivos antes de
disponibilizá-los ao público; não equivale a uma versão estável aprovada.

## 1. Resolver a redistribuição antes de divulgar os binários

O Audiveris embarcado inclui JAI 1.1.3 proprietário. A licença está em
`docs/legal/licenses/jai-1.1.3.txt`; a análise técnica está em
`docs/legal/native-distribution.md`. É necessário obter uma revisão dos termos
de redistribuição e sua compatibilidade com a distribuição do ARPEON, ou
substituir essa dependência e validar novamente o OMR. Copiar os avisos não
resolve esse ponto. Também faltam o inventário nativo completo e as fontes
correspondentes dos dois runtimes Java. Não marque o gate como aprovado sem
registrar a evidência da solução adotada.

## 2. Contratar a assinatura Authenticode

Esse certificado identifica o editor dos EXEs/MSI no Windows. Procure um
provedor de assinatura de código que aceite sua identidade e país e ofereça
integração com CI. Confirme isso e os custos antes de contratar. Um certificado
de site HTTPS ou um certificado autoassinado não substitui esse serviço.

Certificados públicos atuais normalmente mantêm a chave em token/HSM ou serviço
de assinatura em nuvem. Não planeje exportar a chave privada em um PFX comum.
Por exemplo, o DigiCert oferece [provisionamento com token/HSM e KeyLocker](https://docs.digicert.com/pt/certcentral/order-and-manage-certificates/request-certificates/request-a-code-signing-or-ev-code-signing-certificate/code-signing-provisioning-methods.html).
A [lista atual do Microsoft Artifact Signing](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart)
não inclui organizações brasileiras para Public Trust; confirme a elegibilidade
da sua identidade antes de escolher esse serviço.

O job `release-windows.yml` existente importa PFX. Esse caminho não deve ser
considerado adequado automaticamente a um certificado novo: após escolher o
provedor, adapte a autenticação e a chamada de assinatura ao seu HSM/SDK.
`package-windows.ps1` usa SignTool e o thumbprint do certificado; um provedor
que exponha a chave pelo Windows KSP pode atender esse contrato, mas requer
configuração e teste reais no runner. A assinatura deve cobrir launcher, MSI e
Setup final, com timestamp e verificação `signtool verify /pa`. A assinatura
não promete eliminar imediatamente todos os avisos de reputação do Windows.

## 3. Criar a chave gratuita das atualizações

Ed25519 verifica que uma atualização veio do projeto. É uma chave diferente do
certificado acima. O build já baixa e verifica o `winsparkle-tool.exe` fixado.
Em um local privado fora do checkout, execute uma vez por canal:

```powershell
$tool = 'C:\DEV\ARPEON\apps\desktop\build\native-resources\build-tools\winsparkle-tool.exe'
& $tool generate-key --file .\arpeon-testing.key
& $tool public-key --private-key-file .\arpeon-testing.key
```

Guarde a chave privada em um cofre com backup e acesso restrito. Não a envie ao
chat, não a versione e não a gere novamente a cada build. A chave pública pode
ser distribuída no aplicativo; a privada assina as atualizações. Use pares
diferentes para testing/beta/stable. Consulte o [procedimento do WinSparkle](https://github.com/vslavik/winsparkle#eddsa-signatures).

## 4. Configurar o GitHub

No repositório, abra **Settings → Environments → New environment** e crie
`windows-publication`. Restrinja as branches de publicação e, se desejar,
configure revisão para liberar os segredos apenas ao job autorizado.
Veja a [documentação de environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).

- Secret `ARPEON_UPDATE_PRIVATE_KEY`: conteúdo privado gerado pelo WinSparkle,
  apenas do canal que será validado primeiro (testing).
- Variable `ARPEON_TIMESTAMP_URL`: endpoint RFC 3161 indicado pelo provedor.
- Credenciais do serviço de assinatura: nomes e formato dependem do provedor
  escolhido e da adaptação do workflow. A implementação atual usa
  `ARPEON_SIGNING_PFX_BASE64` e `ARPEON_SIGNING_PASSWORD`, mas não é uma
  recomendação para exportar uma chave de certificado público moderno.

Nenhum desses segredos foi criado automaticamente. O workflow atual não isola
segredos Ed25519 por canal: antes de liberar beta/stable, separe os environments
ou selecione explicitamente os segredos correspondentes a cada canal.

## 5. Validar e publicar em ordem

1. Faça o CI passar em Linux, macOS e Windows e execute **Prepare internal
   Windows candidate**, que testa o ciclo MSI e a recuperação pelo Setup.
2. Valide instalação/upgrade/desinstalação em VM Windows limpa e áudio em
   hardware real. Registre resultados da versão exata.
3. Em testing, valide duas versões realmente distintas com assinatura confiável,
   download HTTPS, assinatura adulterada, interrupção, recuperação e preservação
   de projetos. Os testes locais de MSI não certificam esse fluxo de rede.
4. Atualize `packaging/release-readiness.json` somente com as evidências obtidas.
   O workflow assinado atual exige todos os gates inclusive em testing; a
   qualificação inicial de testing deve ser organizada como ensaio controlado
   antes da publicação pública. Não simule aprovação para fazê-lo executar.
5. Rode **Publish signed Windows release**, versão numérica igual à do código,
   primeiro em testing. Ele publica os artefatos e depois o feed. Stable só deve
   ser liberado após essa qualificação. Não sobrescreva releases existentes.

Se for necessário reconstruir um binário após divulgá-lo, use uma nova versão.
Um rascunho sem publicação pode ser substituído para revisão, mantendo claro
qual commit produziu cada conjunto de arquivos.
