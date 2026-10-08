# Compatibilidade de teclado entre sistemas

Esta revisão define um contrato que funciona quando a máquina do operador e a máquina remota têm
sistemas operacionais, layouts e capacidades diferentes. A situação prioritária é macOS com teclado
US controlando Windows (US, US-International ou ABNT2), mas o desenho não depende dessa combinação.

## Princípio: texto não é tecla física

Há dois tipos de entrada e eles não podem ser tratados da mesma forma:

| Intenção | Exemplo | Transporte e injeção |
|---|---|---|
| Texto resultante | `,`, `.`, `ç`, `€`, emoji, texto de autocorreção | O navegador envia o texto Unicode que ele já compôs; no Windows, o agente usa `SendInput` com `KEYEVENTF_UNICODE`. O layout ativo da máquina remota não altera o resultado. |
| Tecla/atalho físico | Ctrl+C, Cmd+V, Tab, F5, setas, Shift, Option/Alt | O navegador envia `KeyboardEvent.code`; no Windows, o agente prefere scan code Set 1 com `SendInput`. Se esse recurso não puder ser usado, conserva o fallback `Robot`/VK existente. |

Isso evita a falha comum de transformar o caractere `,` do operador em uma tecla virtual que produz
outro caractere no Windows remoto. Também elimina a necessidade de forçar ou trocar o layout do
Windows remoto — uma ação que seria surpreendente e poderia afetar o usuário local.

## Compatibilidade por transporte e por versão

- WebRTC: texto, clipboard e atalhos compartilham o canal de controle confiável e ordenado.
- Modo compatível: os mesmos objetos de controle seguem pelo hub; não há um segundo conjunto de
  regras de teclado.
- O envelope legado `kt` foi mantido. Agentes anteriores ainda recebem texto BMP normalmente; o
  agente atualizado interpreta a string inteira, inclusive pares substitutos usados por emoji.
- Se a injeção nativa por scan code ou Unicode estiver indisponível (Windows restrito, outro sistema
  operacional, PowerShell bloqueado), o agente volta ao `Robot`/VK e registra somente o estado
  técnico do fallback no log central — nunca o conteúdo digitado.

## Convenções de plataforma

- No macOS, Command é convertido para Control para atalhos Windows. Option/Alt continua Alt, sem
  inventar AltGr (que equivale a Ctrl+Alt e quebra atalhos em layouts US).
- `KeyboardEvent.key` só é usado quando o navegador já entregou texto final. `Dead`, `Process`,
  composição em andamento e `Unidentified` não são reproduzidos como estado físico no Windows;
  espera-se o texto composto final. Isso evita deixar uma dead key pendente no layout remoto.
- `KeyboardEvent.code` é usado para atalhos e navegação porque descreve posição física. A tabela
  cobre letras, dígitos, pontuação US, setas, navegação, F1–F24, teclado numérico e modificadores,
  incluindo variantes estendidas direita/esquerda.

## Diagnóstico seguro

Os logs centralizados podem registrar: transporte (WebRTC/compatível), uso de Unicode, conversão
Command→Control, composição/dead-key, código desconhecido e fallback de injeção. Eles nunca guardam
texto digitado, caracteres, conteúdo do clipboard, nomes de arquivo ou credenciais.

O layout ativo do Windows pode mudar por janela ou por atalho do próprio sistema. A regra é
observá-lo em diagnóstico futuro, jamais alterá-lo automaticamente. Como texto e atalhos já usam
contratos independentes de layout, uma máquina sem APIs de consulta de layout continua funcional.

## Matriz de regressão

Os testes automatizados, sem abrir janelas nem capturar tela real, cobrem:

- texto Unicode, vírgula, ponto e emoji;
- dead keys sem contaminar o estado remoto;
- Command do Mac convertido para Control;
- Ctrl+V e clipboard no mesmo transporte ordenado;
- modo compatível usando o mesmo protocolo;
- scan codes de pontuação US, modificadores, setas e F12/F24;
- fallback de versões pelo envelope `kt`.

Validação manual posterior deve testar Mac US para Windows US, US-International e ABNT2, com atalhos
Ctrl/Cmd, Option/Alt, Shift+pontuação, caracteres compostos e uma troca de layout no Windows durante
uma sessão. Esse teste exige uma sessão real e não faz parte da suíte automática por segurança.

## Correção posterior: acentos e teclados de origem diferentes (05/10/2026)

A verificação do helper do Windows revelou que sua estrutura `INPUT` ocupava 32 bytes em 64 bits.
O `SendInput` exige o tamanho completo da união (40 bytes nessa arquitetura), mesmo quando o evento
é de teclado. O helper agora inclui a estrutura de mouse na união para que o marshalling produza o
tamanho correto em 64 e 32 bits. Um teste inicia o helper, faz `PING` e confere o tamanho sem enviar
nenhuma tecla nem abrir janela. A ponte é iniciada em segundo plano ao abrir a sessão, reduzindo a
espera da primeira digitação.

No lado do operador, a área da sessão agora foca um campo de captura invisível. Eventos `input` e
`compositionend` entregam o texto já composto pelo macOS ou Windows, incluindo Option + dead key no
Mac, acentuação do ABNT2 e AltGr. Atalhos seguem como teclas físicas. Quando o navegador não usa o
campo, o tratamento antigo de `KeyboardEvent.key` permanece como fallback. O conteúdo da captura é
limpo após o envio e não entra nos logs. Testes sintéticos cobrem Mac US, Windows ABNT2, composição
sem duplicação, AltGr e Ctrl+C.

O hub identifica no início de cada sessão apenas a **família** do navegador (Safari, Chrome, Edge,
Firefox ou outro), a plataforma e a disponibilidade de eventos `input`/composição e da API de mapa
de layout. Esses metadados técnicos ficam no histórico central; o user-agent completo e o texto
digitado não são guardados. A identificação é informativa: o caminho principal usa eventos padrão
da Web e mantém o fallback de `KeyboardEvent.key`, sem exigir uma API exclusiva do Chromium.
Navegadores podem ocultar ou imitar o user-agent, então a família é uma estimativa, não uma prova.
A fonte de entrada macOS selecionada (ABC/U.S., etc.) não é pressuposta como disponível à página;
o texto Unicode já composto é a fonte confiável para acentos, independentemente dessa configuração.
