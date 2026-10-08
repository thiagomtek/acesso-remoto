# Regras de negócio preservadas na migração para o hub

Inventário do sistema atual (Servidor + Client Java) e onde cada regra passa a viver.
Toda regra daqui precisa ter um item correspondente no sistema novo antes de a migração ser dada como concluída.

Legenda: **Client** = Java na máquina remota · **Hub** = serviço em Node · **Painel** = página web do operador.

## Qualidade da imagem (prioridade máxima)
| Regra atual | Onde vive agora |
|---|---|
| Captura na **resolução física real** do monitor, não a reduzida pela escala do Windows (125%/150%) | Client (captura nativa do WebRTC deve entregar resolução física; validar em tela com escala) |
| Imagem **nítida, sem artefatos** (PNG sem perdas por tiles) | Vídeo WebRTC com bitrate máximo e `contentHint = detail`; modo "lossless" para tela parada |
| Taxa **adaptativa**: ~30 fps com a tela em movimento, ~4 fps parada (economiza CPU/rede) | Controle de congestionamento do WebRTC + `maxFramerate` por qualidade |
| Adaptar a resolução ao **viewport** de quem vê (ex.: client 4K visto em notebook 1080p), nunca ampliar acima da nativa | Painel informa o tamanho da tela; `scaleResolutionDownBy` no Client |
| **Tela cheia** e imagem ocupando todo o espaço | Painel (Fullscreen API) |
| Estabilidade antes de tudo, qualidade adaptável | Qualidade "Automática" (padrão) + opções Máxima/Equilibrada/Economia por máquina |

## Entrada (mouse e teclado)
| Regra atual | Onde vive agora |
|---|---|
| Coordenadas mapeadas para o **tamanho real** da tela remota | Painel |
| Move de mouse **coalescido** (só a posição mais recente, taxa máxima); clique/soltar enviam a posição na hora | Painel |
| **Tab/Shift+Tab/Ctrl+Tab** chegam à máquina remota (não movem o foco local) | Painel (captura de teclado no visualizador) |
| Caractere Unicode digitado 1:1 além de key press/release | Painel + Client (`InputInjector`) |
| Após um comando remoto, **acorda a captura na hora** (resposta rápida ao clique) | Client |
| Cliente pode **recusar** controle remoto ("Permitir controle remoto") | Configuração por máquina no Painel |
| Funciona só com sessão desbloqueada (limitação do `Robot`) | Mantida e documentada |

## Área de transferência
| Regra atual | Onde vive agora |
|---|---|
| Sincroniza **texto e arquivos** nos dois sentidos | Canal de dados WebRTC |
| Pode ser **habilitada/desabilitada** para os clients | Configuração por máquina (`clipboardSync`) |

## Transferência de arquivos
| Regra atual | Onde vive agora |
|---|---|
| `.zip` em modo binário idêntico; pasta recriada com a estrutura; outros arquivos avulsos exigem zip/pasta | Canal de dados WebRTC (mesmo contrato) |
| Mão dupla (operador↔client) | Idem |
| Destino de arquivos recebidos | Pasta fixa `recebimentos` dentro da pasta do usuário da máquina. Ex.: `C:\Users\ThiagoMaglioniMagalh\recebimentos`. O agente cria a pasta quando necessário; não depende de Área de Trabalho, OneDrive ou da janela ativa. |
| Pasta compartilhada | `recebimentos` é reconciliada nos dois sentidos com a pasta persistente e autenticada do Hub. A bolinha da sessão abre um único modal para adicionar arquivos ou uma pasta completa preservando subpastas. Ao clicar num arquivo aparecem Baixar/Excluir; ao clicar numa pasta aparecem Baixar ZIP/Excluir, incluindo toda a árvore. Não exige acesso permanente do navegador ao disco do Mac/Windows. Em conflito simultâneo, prevalece a alteração mais recente. |

## Áudio e chamadas
| Regra atual | Onde vive agora |
|---|---|
| Áudio do sistema do client → operador | Client (loopback WASAPI, sem admin) → faixa de áudio WebRTC |
| Microfone do operador → máquina remota (Teams, Zoom) | Faixa de áudio do navegador → Client → dispositivo virtual (VB-Cable; exige instalação única) |
| **Webcam do operador** → máquina remota | Faixa de vídeo do navegador → câmera virtual (Windows 11 sem admin via Media Foundation; demais, instalação única) |

## Alerta de atividade no Teams
| Regra atual | Onde vive agora |
|---|---|
| Client observa o ícone do Teams na bandeja e avisa **só que houve atividade** (não lê conteúdo) | Client (`teamsWatcher`) → evento `client-event` ao Hub |
| Toca **sirene** em vez de beep, com janela para pausar | Painel: sirene (Web Audio) + modal sempre visível + notificação do navegador |
| "Verificar inatividade": só toca se o operador estiver **ausente** (sem mouse/teclado há N min, padrão 5) | Painel: Idle Detection API, com fallback por eventos de input e visibilidade da aba |
| Se o operador está presente, só **registra no log** | Painel |
| Sirene para: operador pausa/fecha, **volta a mexer**, ou a atividade é **lida** no client (evento *cleared*) | Painel |
| **Silenciar por 30 min** | Painel |
| Volume do sistema forçado ao **máximo** ao tocar | Limitação do navegador (não controla volume do SO): sirene em volume 100% do elemento de áudio + aviso na tela |
| Opção de ativar/desativar o alerta e de testar a sirene | Painel |

## Ciclo de vida do Client
| Regra atual | Onde vive agora |
|---|---|
| **Reconexão automática** ao perder o servidor, com respiro (3 s) antes de tentar de novo | Client: o client sempre disca para o Hub; backoff exponencial 1–60 s com jitter, watchdog de ping/pong, reconecta ao voltar da suspensão/troca de rede |
| **Iniciar com o Windows** (por usuário, sem admin) | Configuração por máquina (`startWithSystem`) |
| **Manter computador ativo** (anti-suspensão) | Configuração por máquina (`keepAwake`) |
| **Senha na primeira execução** (pedida uma vez por perfil do Windows) | Mantida no Client (`FirstRunGate`) |
| **Auto-atualização** do Client por hash do jar (envia nova versão; certificados/scripts antes) | Hub guarda a versão publicada; Client compara o hash no `hello` e baixa; mesmo fluxo de troca-e-reinicia |
| Persistência das preferências do usuário entre reinícios | Client guarda só a identidade (ID/segredo); configurações vêm do Hub |
| Funciona sem admin | Mantido: nada do Client exige elevação (exceto drivers virtuais opcionais) |

## Várias máquinas
| Regra atual | Onde vive agora |
|---|---|
| Vários clients simultâneos; escolher o "Client ativo" | Painel: lista de máquinas, com mais de uma sessão aberta |

## Segurança (novas, por mudar o modelo de acesso)
- Operadores autenticados por login/senha no próprio Hub, acessível só pela Tailnet.
- Clients entram pelo host Tailscale configurado + segredo próprio gerado no primeiro uso (registro automático).
- Máquina nova só é acessível após aprovação no Painel (`AUTO_APPROVE=1` dispensa).
- O Hub nunca conecta no Client; tudo é conexão de saída do Client.

## Status da migração (atualizado em 2026-10-03)

**Implementado e testado**
- Registro automático + aprovação; reconexão resiliente (backoff, suspensão, troca de rede, conexão "meio morta"); várias máquinas.
- Visualização por WebRTC (H.264) **e** modo compatível em Java puro (fallback automático); tela cheia; adaptação ao tamanho da tela de quem vê; qualidade por máquina (aplicada ao vivo).
- Mouse (posição normalizada, coalescido), teclado (inclusive Tab; Unicode 1:1; Cmd→Ctrl no Mac), "permitir controle remoto" por máquina.
- Configurações por máquina no painel **e dentro da tela de visualização**, refletidas em tempo real no agente: controle remoto, clipboard, anti-suspensão, Teams, iniciar com o sistema, qualidade.
- Alerta do Teams no painel: sirene, "só se ausente" (padrão 5 min, IdleDetector opcional), pausar, silenciar 30 min, parar ao voltar ou ao ler, log, testar, notificação do navegador.
- Área de transferência: **texto** nos dois sentidos, só com sessão aberta e se a máquina permitir.
- Senha da primeira execução (mantida; compartilhada com o client antigo).
- Auto-atualização assinada (Ed25519), aplicada quando ocioso; troca-e-reinicia como no sistema original.

**Ainda não migrado**
- Área de transferência de **arquivos** (zip).
- **Transferência de arquivos/pastas** (hoje só existe no sistema legado).
- **Áudio** do sistema da máquina remota → operador (WASAPI loopback) e **microfone/webcam do operador** → máquina remota (exige driver virtual; câmera sem admin só no Windows 11 via Media Foundation; áudio exige VB-Cable ou similar). Drivers sem assinatura serão bloqueados como a DLL.
- WebRTC nas máquinas com Smart App Control/WDAC (depende de assinar as DLLs).
- Volume máximo do sistema ao tocar a sirene (limitação do navegador: toca no volume máximo do próprio navegador).
