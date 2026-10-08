# Acesso remoto centralizado: arquitetura e operação

```
Agente (client, sem admin) ──WSS saindo──► Tailscale + Caddy (:8787, TLS) ──► Hub (Node, homelab) ◄── login por conta ── Navegador
        ▲                                                                                                      │
        └──────────────── mídia WebRTC (UDP, P2P ou via coturn)  ──────────────────────────────────────────────┘
        └──────── modo compatível (Java puro): blocos de imagem pelo hub, quando o WebRTC não funciona ─────────┘
```

O **agente sempre disca** para o hub; o hub nunca conecta no agente.

### Rede (Tailscale)

O Cloudflare foi desligado. Agentes e operadores alcançam o hub pela malha Tailscale em `https://servidor.tail074692.ts.net:8787` (agentes: `wss://.../agent`; navegador: `wss://.../operator`). O Caddy termina o TLS (certificado da Tailnet) e encaminha para o hub em `172.17.0.1:8788` (bridge do Docker). Máquinas precisam estar na Tailnet. WebRTC fecha P2P direto entre nós da Tailscale; o coturn não está em uso.

## Componentes
| Pasta | O que é |
|---|---|
| `hub/` | Serviço Node (`remote-hub`): autenticação, registro automático das máquinas, sinalização, painel web, relay do modo compatível, servidor de atualizações. |
| `agent/` | Client em Java 17+ (sem Maven): conexão resiliente, captura/entrada, WebRTC (`webrtc-java`), modo compatível, auto-atualização. Reaproveita classes de `src/`. |
| `src/` | Sistema legado (Servidor+Client por TLS direto). Continua compilando; não é mais o caminho principal. |
| `docs/REGRAS-DE-NEGOCIO.md` | Inventário das regras do sistema original e onde cada uma vive agora. |

## Segurança (em camadas)
1. **Operadores**: login/senha por conta do hub (`accounts.json`, cookie `hub_session`), dentro da Tailnet.
2. **Agentes**: só entram pelo host Tailscale configurado (`EDGE_AUTH=tailscale`, `TAILSCALE_HOSTS`) **+** ID/segredo próprio gerado no primeiro uso (TOFU). O hub guarda só o hash.
3. **Máquina nova só é acessível após "Aprovar"** no painel (`AUTO_APPROVE=1` dispensa).
4. **Atualizações assinadas**: o agente só aplica pacote com assinatura Ed25519 válida (chave pública embutida em `AgentUpdater`).
5. O hub não é publicado na Internet: só a Tailnet alcança o Caddy em :8787; a porta do hub no host fica na bridge do Docker.

## Modos de transporte (um só funciona se o outro falhar)
- **WebRTC** (preferido): H.264/VP9/AV1, controle de congestionamento nativo, P2P ou via coturn. Requer carregar uma DLL nativa.
- **Modo compatível** (alternativa automática): Java puro, estilo VNC — CopyRect (rolagem), JPEG só em conteúdo fotográfico com refinamento sem perdas ao parar, controle de fluxo por ack e teto de banda. Entra sozinho se a DLL for bloqueada (Smart App Control/WDAC), se a rede não fechar o WebRTC em 12 s, ou pelo botão **Modo compatível**.

## Operação do dia a dia
| Tarefa | Como |
|---|---|
| Instalar o agente | Copiar `dist\Agent` (ou o zip) para a máquina, rodar `iniciar-agent.bat`, aprovar no painel. Precisa de Java 17+. |
| Gerar o pacote | `agent\package.ps1` (gera `agent\dist` e o zip) e copiar para `dist\`. |
| **Publicar atualização** | `agent\publish-update.ps1` — compila, assina e grava em `D:\Servidores\Servicos\remote-hub\data\updates`. Agentes conectados e ociosos atualizam sozinhos em ~1 min. `-IncludeLibs` só se as bibliotecas nativas mudarem. Para o pacote de instalação e o canal coincidirem: `package.ps1` e depois `publish-update.ps1 -SkipBuild`. |
| Subir o hub | Copiar `hub\` para `D:\Servidores\Servicos\remote-hub` e chamar `D:\Servidores\Automacao\Scripts\Rebuild-RemoteHub.ps1`. Para configurar/atualizar também a rota LAN, usar `-ConfigureLan`. Segredos ficam no servidor, em `/etc/hub-env/remote-hub.env`. |
| Testes | `cd hub && npm test` (Node) · Java: ver abaixo. |

### Testes Java (sem tela, sem rede)
```powershell
cd agent
javac -d test-out -encoding UTF-8 -cp "out;lib\*" test\com\transacao\agent\*.java
java -cp "out;test-out;lib\*" com.transacao.agent.TileStreamerTest   # CopyRect, classificacao foto x texto
java -cp "out;test-out;lib\*" com.transacao.agent.AgentUpdaterTest   # assinatura, zip-slip, URL
java -cp "out;test-out;lib\*" com.transacao.agent.SystemStatsTest    # uso de CPU/memoria (sanidade dos valores)
```

## Onde ficam os segredos (nunca no repositório)
| Arquivo | Conteúdo |
|---|---|
| `~/.remote-hub/remote-hub.env` | Cópia do `.env` do hub (instalado na VM em `/etc/hub-env/`). |
| `~/.remote-hub/update-signing.key` | **Chave privada de assinatura das atualizações.** Se perder, é preciso reinstalar todos os agentes com uma chave pública nova. Faça backup. |
| `~/.transacao-agent/identity.properties` (por máquina) | ID e segredo do agente. |

## Instalação e auto-atualização (sem scripts)
- **Sem LEIA-ME na pasta**: o agente apaga sozinho qualquer `LEIA-ME*` que encontrar ao lado do jar (de pacotes antigos).
- **Senha da primeira execução**: guardada só como hash PBKDF2-HMAC-SHA256 (sal próprio, 200 mil iterações) em `FirstRunGate` — a senha em si não existe em nenhum arquivo do repositório.
- **Autoinicialização só para o usuário atual**, sem admin e sem scripts: Windows grava direto na chave `HKCU\...\Run` (via um `.reg` temporário, importado e apagado — `reg add` quebra com aspas internas); macOS usa um LaunchAgent do usuário; Linux, `~/.config/autostart`. Reaplicado a cada início e logo após cada atualização, e some se "Iniciar com o sistema" for desligado no painel.
- **Atualização sem `.bat`/`.vbs`**: um pequeno processo Java (`UpdateSwapper`, rodando a partir de uma cópia do jar) troca os arquivos, sobe a versão nova, espera o sinal de saúde e desfaz se não vier. Nenhum script entra no caminho, então não há risco de bloqueio por Smart App Control/WDAC nessa etapa.

## Gestão de máquinas (painel)
- **Renomear**: lápis ao lado do nome no cartão (só admin) — campo de texto inline, Enter confirma,
  Esc cancela. Manda `{type:'update-client', clientId, name}`; o hub já aceitava isso de antes
  (`store.update`), só faltava a UI. `hub/public/machines-ui.js` (`buildMachineCard`).
- **Estatísticas de CPU/memória**: o agente lê `com.sun.management.OperatingSystemMXBean` (API padrão
  do JDK 14+, sem shell-out nem dependência nova) a cada 10s e manda `{type:'stats', data:{cpuPct,
  memUsedMb, memTotalMb, uptimeSec}}` pro hub (`SystemStats.java`, `AgentApp.sendStats`). O hub guarda
  só o valor mais recente **em memória** (não persiste em disco — não tem por que guardar histórico de
  CPU) e manda no broadcast de `clients` (`server.js`, caso `'stats'`). Aparece no cartão como
  "CPU 12% · RAM 3.2/16.0 GB" quando disponível; se a JVM/plataforma não expuser esses valores, só não
  aparece (o `uptimeSec`/heap do próprio agente sempre vêm, via `Runtime`/`RuntimeMXBean`).

## Problemas conhecidos / diagnóstico
- **"Uma política de Controle de Aplicativo bloqueou este arquivo"** no `agent.log`: Smart App Control/WDAC barrando a DLL do WebRTC. O agente cai para o modo compatível sozinho. Para ter o WebRTC nessas máquinas é preciso **assinar as DLLs** com certificado de uma CA pública (Azure Artifact Signing não atende o Brasil) — ver conversa de projeto.
- `agent.log` fica em `%USERPROFILE%\.transacao-agent\` (ou `TRANSACAO_AGENT_HOME`).
- Painel não abre a tela: ver a barra de estado (estatísticas); usar **Modo compatível**; checar o log do agente.
- Variáveis úteis do agente: `HUB_URL`, `LAN_HUB_URL`, `TRANSACAO_AGENT_HOME`, `TRANSACAO_COMPAT_V1=1` (streamer antigo, para comparação). `LAN_HUB_URL` é opcional e, se ausente, usa a mesma URL do hub (Tailscale).

## Limites atuais
Ver `docs/REGRAS-DE-NEGOCIO.md` (seção *Status da migração*): faltam transferência de arquivos, áudio e webcam/microfone; clipboard é só texto.
