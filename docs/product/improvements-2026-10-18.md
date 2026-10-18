# Melhorias de estudo e player — 18/10/2026

## Escopo

Revisão 0.1.2, preservando a identidade Ouro e Carvão e os serviços musicais locais.
A imagem fornecida pelo usuário orienta a organização das mãos, chaves e consulta
por nota. A flauta é um esquema vetorial criado no próprio projeto; a fotografia
não foi usada como ativo da interface. Não foram adicionadas dependências.

## Alterações

- Player: ícone de metrônomo com ativação direta, contagem inicial com “123” e
  Ajustes. O número de BPM abre a edição exata, com Enter ou Aplicar e botões
  imediatos de ±1/±5, respeitando 30–300 BPM. A–B permanece dentro de Ajustes.
- Digitação: mapa do instrumento, separação das mãos por cor e rótulos, estados
  preenchido/aberto, identificação dos dedos, alternativas e consulta por nota.
  Aprendizado apresenta 13 referências cromáticas Dó4–Dó5 com pauta e diagramas.
  A seleção contextual continua usando os dados verificados Dó4–Fá6 existentes,
  incluindo enarmonia. Nenhuma posição foi inferida da imagem fornecida.
- Extensão: quantidade e severidade resumidas, nota em destaque, parte, compasso
  escrito e voz. A ação de revisão seleciona o evento da voz e a cabeça do acorde,
  sem editar a partitura ou reposicionar automaticamente o transporte.
- Controles: seletores com seta vetorial, opções e estados por tema, ações de
  confirmação com acento dourado e foco de teclado preservado.
- Distribuição: aplicativo, frontend e MSI em 0.1.2 para distinção da versão
  anterior durante atualização. Os gates de publicação continuam reprovados.

## Validação funcional

- Frontend: 149 testes em 26 suítes aprovados; tipagem e lint aprovados.
- Build completo Gradle aprovado; 190 testes Java em 34 suítes, sem falhas, erros ou testes ignorados.
- Verificador de licenças: 586 entradas npm conferidas.
- Chromium com backend Java real e perfil isolado: metrônomo, contagem, valor
  numérico e Enter, incrementos de BPM, limites invertidos e ativação/remoção de
  A–B, digitação alternativa de Fá♯ e seleção de aviso em Dó7.
- Inspeção em 1440 × 1000 e 390 × 844, temas claro/escuro; sem overflow horizontal
  do documento na janela compacta e sem exceções JavaScript nas interações.
- O mapa completo pode ser rolado horizontalmente em painéis estreitos. O diagrama
  principal das mãos permanece inteiramente visível.
- Detector visual em modo regex por ausência dos parsers opcionais. A inspeção
  visual foi realizada separadamente; o detector não certifica contraste.
- Build frontend de aproximadamente 525,30 kB iniciais, com aviso de 25,30 kB
  acima do orçamento de aviso de 500 kB e abaixo do limite de erro de 750 kB.
  O renderizador de partitura continua carregado sob demanda.

As evidências locais desta revisão estão em `.tools/ui-review-20261018`.
Medição acústica física, máquina limpa e atualização assinada continuam fora
desta aceitação de interface. Pacotes locais não são uma publicação pública.
