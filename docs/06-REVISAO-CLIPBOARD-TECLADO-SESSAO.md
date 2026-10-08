# Módulo 06: Revisão de bugs (clipboard, teclado, Ctrl travado, RDP/sessão headless)

Iniciado em 05/10/2026, a pedido do usuário ("precisamos fazer uma revisao geral em tudo, pq ta dando
alguns bugs"). Este documento é atualizado incrementalmente — se uma seção existe, aquilo já foi feito
e testado (compilação + testes automatizados), não é um plano escrito e esquecido. Ver também
`docs/ARQUITETURA.md` (visão geral do sistema) e `docs/REGRAS-DE-NEGOCIO.md`.

**Regra seguida**: nada de testes que abram janela/capturem a tela real do usuário (ver memória
`feedback-testes-sem-tocar-na-tela`); compatibilidade com máquinas client restritas (sem admin, Smart
App Control) deve ser mantida — qualquer coisa que precise de admin/serviço é **opt-in por máquina**,
nunca o padrão.

---

## 1. Bugs relatados pelo usuário (texto original, 05/10/2026)

> CTRL+C e CTRL+V não funciona quando o conteúdo copiado vem de fora do navegador (outro app aberto na
> máquina local). Codificação do teclado péssima (usuário usa Mac acessando Windows). Tecla Ctrl ficou
> travada na máquina remota, precisou apertar Esc pra destravar.

> Precisamos que o acesso remoto funcione mesmo que o usuário tenha se ausentado e a máquina entrado em
> modo de bloquear sessão — isso está acontecendo com o próprio servidor homelab: enquanto acessa via
> RDP (Windows App) o client funciona, mas para de funcionar quando fecha o RDP.

## 2. Bugs corrigidos e testados (05/10/2026)

Compilação verificada (`javac` de `src/com/transacao/common` e `agent/src` — zero erros) e
`cd hub && npm test` — **40/40 testes passando** (incluindo os testes de regressão abaixo).

### 2.1 Clipboard: falha silenciosa + perda de permissão no Safari

**Onde**: `hub/public/viewer.js` (`sendLocalClipboard`, handler de teclado).

**Causa raiz**: duas coisas, não uma só:
- `sendLocalClipboard()` era chamada de dentro de `queueKey` (uma Promise encadeada), não
  diretamente na pilha síncrona do evento de teclado — navegadores mais restritivos (Safari) podem
  recusar `clipboard.readText()` por perda de "user activation" quando a chamada não está mais
  "perto" do gesto original do usuário.
- O `catch` da leitura era totalmente mudo (`catch { /* sem permissao/foco */ }`) — ao contrário do
  lado inverso (`onClip`, que mostra toast), uma falha aqui não avisava nada: o Ctrl+V era enviado
  mesmo assim e simplesmente não colava o texto certo, sem nenhum sinal de erro.

**Fix**: `sendLocalClipboard()` agora é chamada diretamente no handler de `keydown` (antes do
`queueKey`), preservando a user activation; e o `catch` agora mostra um toast
("Não foi possível ler a área de transferência local...").

### 2.2 Teclado: caracteres acima de code point 255 não digitavam nada

**Onde**: `src/com/transacao/common/remote/InputInjector.java` (`typeChar`/`typeAltNumpad`).

**Causa raiz**: o fallback para caracteres não-alfanuméricos usava o truque "Alt + Numpad" do
Windows. Para code point ≤ 255 isso funciona (Alt+0+decimal = codepage ANSI/Windows-1252 — cobre
`ç ã é ô ñ` etc). Para code point > 255, o código antigo mandava "Alt+decimal sem zero à frente", que
**não é um método válido de entrada Unicode no Windows** (precisaria do registro `EnableHexNumpad`,
desligado por padrão). Isso inclui exatamente o que o usuário relatou: aspas curvas “ ” ‘ ’
(U+201C/201D/2018/2019), travessão — (U+2014), reticências … (U+2026) — tudo que o autocorretor do
macOS troca automaticamente ao digitar normalmente.

**Fix**: novo método `typeUnicodeWindows(char)` usa `SendInput` do Win32 com `KEYEVENTF_UNICODE`, que
injeta o code point exato independente de layout/codepage ativo na máquina remota. Implementado via um
processo PowerShell auxiliar persistente com C# inline (`Add-Type`) — mesmo padrão já usado em
`src/com/transacao/server/UserPresenceMonitor.java` para `GetLastInputInfo` — mantido vivo (não gera
um processo novo por caractere) e só ativo no Windows; em outros SOs cai no Alt+Numpad antigo
(comportamento inalterado lá, não é o caso relatado).

Isso também torna a digitação **independente de codepage** mesmo para 0-255 agora (o SendInput é usado
para TUDO que não é a-z/A-Z/0-9/espaço/enter/tab/backspace), então é estritamente mais robusto que
antes — não só os caracteres > 255.

### 2.3 Ctrl (ou qualquer modificador) "travado" na máquina remota

**Onde**: `hub/public/viewer.js` (`keyHandler`, novo `releaseAllKeys`).

**Causa raiz** (duas, a primeira é a mais provável no relato do usuário):
1. A guarda de foco (`if (!(document.activeElement === this.stage || ...)) return;`) se aplicava
   IGUALMENTE a `keydown` e `keyup`. Cenário: Ctrl pressionado com foco no stage → `kd ControlLeft`
   enviado (Ctrl fica fisicamente pressionado na máquina remota via `Robot`) → foco sai do stage
   (clique no menu/configurações) ANTES de soltar Ctrl → o `keyup` chega mas a guarda descarta porque
   o foco não é mais o stage → o `ku` nunca é mandado → Ctrl fica travado lá. O Esc "destrava" por
   acaso (fecha menus do Windows), não porque libera a tecla.
2. Sem nenhuma rede de segurança para perda de FOCO DA JANELA (Alt+Tab/Cmd+Tab saindo do navegador
   de verdade) — o SO entrega o evento de keyup pro app que ganhou o foco, não pra página.

**Fix**:
- Agora existe `this.pressedCodes` (Set) com os códigos físicos que já mandamos como `kd` e ainda não
  soltamos. O `keyup` de qualquer código desse Set é SEMPRE repassado, independente de onde o foco
  está agora — só o `keydown` (nova tecla) respeita a guarda de foco.
- `window.addEventListener('blur', releaseAllKeys)` e `visibilitychange` soltam tudo que ficou
  pendente em `pressedCodes` se o usuário sair da aba/navegador sem soltar a tecla primeiro.
- `pressedCodes` é limpo em `close()` também, pra não vazar estado de uma sessão pra outra.
- **Teste de regressão**: `hub/test/ui.test.js`, teste `'tecla modificadora nao fica "travada"...'`
  cobre os dois cenários (keyup depois de perder foco, e blur soltando tudo).

### 2.4 Clipboard: Ctrl+V podia chegar antes do texto no WebRTC

**Relato posterior**: mesmo após a correção de permissão/ativação do navegador, `Ctrl+C`/`Ctrl+V`
continuava inconsistente entre o Mac do operador e o Windows remoto.

**Causa raiz**: havia dois transportes concorrentes no modo WebRTC. O texto lido do clipboard local
saía pelo WebSocket/hub (`cmd`), enquanto as teclas `ControlLeft` + `KeyV` saíam pelo canal de dados
WebRTC. O `queueKey` garantia a ordem de *envio no navegador*, mas não pode ordenar a chegada em dois
caminhos de rede independentes: o Windows podia receber e executar Ctrl+V antes de o agente aplicar o
texto ao clipboard. No modo compatível ambos já iam pelo hub, por isso o defeito era mais fácil de
reproduzir/parecer uma confusão entre sistemas operacionais ao usar o Mac.

**Fix**: nos dois sentidos, o clipboard acompanha o transporte da sessão. `sendLocalClipboard()` usa
`sendCtl({t:'clip'})`; no WebRTC, texto e teclas usam o mesmo data channel confiável e ordenado, e no
modo compatível `sendCtl` preserva o caminho pelo hub. Na volta, `AgentApp` tenta primeiro o data
channel WebRTC aberto e só usa o relay do hub quando a sessão está no modo compatível (ou ainda está
negociando o WebRTC). `RemoteSession` reconhece `clip` antes de encaminhar os demais comandos ao
`InputHandler`; o visualizador também recebe `clip` pelo canal WebRTC. Não foi adicionado nenhum log
de conteúdo digitado ou de clipboard.

**Teste de regressão**: `hub/test/ui.test.js`, teste `'area de transferencia: no WebRTC, texto e
Ctrl+V usam o mesmo canal ordenado'`, verifica que `Ctrl+V` no Mac produz no canal de controle, nesta
ordem: Ctrl down → clipboard → V down/up → Ctrl up, sem um `clip` paralelo pelo hub, e que o
clipboard de volta recebido pelo mesmo canal chega à área local.

---

## 3. Pendente: acesso quando a sessão RDP desconecta (bug mais sério, NÃO resolvido ainda)

### 3.1 Diagnóstico (05/10/2026, testado DIRETO na máquina física do homelab — este mesmo Claude Code
roda nela, então o PowerShell usado aqui agiu no host real, não na VM)

Hipótese inicial do usuário/IA: "tela de bloqueio do Winlogon" (desktop seguro isolado). **Essa
hipótese foi DESCARTADA** depois de testar de verdade — o problema real é outro, mais simples de
entender e mais barato de resolver:

```
query session
 SESSIONNAME      USERNAME   ID  STATE   
 console                      1  Conn     <- sessao do CONSOLE FISICO, SEM NINGUEM LOGADO
>rdp-tcp#0        thiago      2  Ativo    <- sessao RDP, essa sim com usuario logado

Get-CimInstance Win32_VideoController:
 Intel(R) UHD Graphics 630         1024x768   <- GPU FISICA, resolucao de FALLBACK (sem monitor)
 Microsoft Remote Display Adapter  1470x825   <- display VIRTUAL criado pelo proprio RDP
```

**Conclusão**: o Dell OptiPlex (servidor homelab) é **headless, sem nenhum monitor físico conectado**
(confirmado pelo usuário). Enquanto o RDP está conectado, tudo que se vê (inclusive o que o nosso
agente capturaria) vem do "Microsoft Remote Display Adapter" — um display **virtual que só existe
enquanto a sessão RDP está conectada**. Ao fechar o Windows App (RDP), esse display virtual
desaparece, e sobra só a saída da GPU física, que cai pro fallback 1024×768 por não ter nenhum monitor
real plugado — não há display renderizável para o `Robot`/captura WebRTC do agente capturarem.

**NÃO é** o problema de "desktop seguro do Winlogon" que foi cogitado inicialmente (esse seria o caso
se houvesse um monitor físico conectado e o problema fosse só a TELA DE BLOQUEIO; aqui o problema
acontece mesmo sem nenhum bloqueio, só pela ausência de um display de verdade).

### 3.2 Opções apresentadas ao usuário (05/10/2026)

| Opção | Risco/custo | Resolve o quê |
|---|---|---|
| **A. Plugue HDMI/DisplayPort "dummy"** (emulador de EDID, ~R$30-80, hardware, zero código) | Baixíssimo risco, só precisa comprar e plugar na saída de vídeo da GPU | Resolve a causa raiz: Windows passa a sempre enxergar "um monitor conectado" → GPU nunca mais cai pro fallback 1024x768 → captura funciona com ou sem RDP conectado. Combinar com desativar bloqueio por inatividade (ou login automático) nessa conta. |
| **B. Rodar o agente como Serviço do Windows (LocalSystem) + WinSW + troca de "input desktop" (OpenInputDesktop/SetThreadDesktop) + opcionalmente auto-desbloqueio com senha guardada** | Alto: muda a arquitetura (hoje é HKCU\Run sem admin), precisa de WinSW (binário de terceiro, aprovado pelo usuário para baixar mas AINDA NÃO baixado), código novo sensível (captura/input em desktop seguro), só testável na máquina física real, e **nem resolve o problema real** (que é falta de display, não desktop seguro) | Resolveria SE o problema fosse mesmo a tela de bloqueio/Winlogon (não é, pela seção 3.1) — ficaria útil só como complemento, não como solução principal |

**Decisão do usuário (05/10/2026, mensagem seguinte)**: optou pela **Opção B (serviço do
Windows/WinSW)**, mesmo sabendo que a Opção A seria mais simples/barata. Implementação em andamento —
ver seção 3.4 para o plano técnico, que passa a ser seguido a partir daqui.

### 3.3 Se/quando o usuário decidir pela Opção A (plugue dummy)
- Comprar um "plugue HDMI dummy" ou "DisplayPort dummy" (dependendo da saída livre da GPU Intel UHD
  630 — conferir se é HDMI ou DP; pelos `Get-PnpDevice` rodados nesta sessão, há histórico de monitores
  conectados via **DisplayPort** `Dell P2222H (DP)`/`DELL P2319H(DisplayPort)` — a saída provavelmente
  é DisplayPort, não HDMI; checar fisicamente antes de comprar).
- Desativar o bloqueio automático por inatividade dessa conta do Windows (ou configurar login
  automático), senão mesmo com o plugue o Windows ainda bloqueia por timeout e o `Robot`/captura ficam
  sujeitos ao desktop seguro do Winlogon de verdade (esse aí sim é o cenário onde a hipótese B faria
  sentido, mas só para esse caso específico de lock por timeout, não para o RDP-disconnect).
- Depois de instalado, testar: desconectar o RDP (fechar o Windows App) e confirmar pelo painel web do
  hub que a máquina continua respondendo a mouse/teclado e mostrando vídeo normalmente.

### 3.4 Decisão final do usuário (05/10/2026): serviço + driver de display virtual por software

Avisado que o serviço sozinho NÃO bastava (sem nenhum monitor real/virtual não há nada pra capturar
quando o RDP desconecta — ver seção 3.1), o usuário escolheu, nessa ordem de perguntas:
1. Serviço do Windows (WinSW) — confirmado.
2. Entre "plugue dummy físico" (recomendado, mais simples) e "driver de display virtual por
   software" — usuário escolheu **driver por software**, mesmo sabendo que é o caminho mais
   complexo/arriscado.
3. Avisado que drivers de display virtual open-source usam certificado autoassinado (exige Test
   Signing do Windows, que historicamente desativaria o Smart App Control permanentemente) —
   **verificado nesta mesma máquina que o Smart App Control já estava DESLIGADO aqui** (registro
   `HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy\VerifiedAndReputablePolicyState = 0`, e
   confirmado rodando o `WinSW.exe` não assinado sem nenhum bloqueio) — ou seja, esse risco específico
   não se aplicava a ESTA máquina (o bloqueio documentado em `project-acesso-remoto-hub.md` sobre SAC
   bloqueando o webrtc-java foi observado em outra máquina client, não neste servidor).
4. Avisado que ativar o driver exige **reiniciar o Windows host** (derruba temporariamente toda a VM
   e os serviços do homelab: Nextcloud, Caddy, ERP, OpenVPN, AdGuard DNS) — usuário pediu pra deixar
   tudo PRONTO mas **não reiniciar ainda** (reinicia quando quiser, fora de horário de uso).

#### 3.4.1 WinSW — validado (05/10/2026)

Baixado `WinSW.NET461.exe` v2.12.0 (655KB, MIT,
`github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe`, SHA256
`B5066B7BBDFBA1293E5D15CDA3CAAEA88FBEAB35BD5B38C41C913D492AADFC4F`) para
`agent/service/WinSW.exe`. **Ciclo de vida completo testado e confirmado nesta máquina** (serviço
descartável de teste, já removido): install → start → `Get-Service` mostrou `Status: Running` → stop →
uninstall → confirmado removido. Não foi bloqueado pelo Smart App Control (que já estava desligado
aqui, ver acima). **Ainda não foi integrado ao agente de verdade** — só a ferramenta em si foi
validada.

#### 3.4.2 Driver de display virtual — preparado, falta reiniciar (05/10/2026)

Escolhido `MolotovCherry/virtual-display-rs` v0.3.1 (open-source, MIT,
`github.com/MolotovCherry/virtual-display-rs`) em vez do que eu tinha em mente originalmente
(`ParsecVDD` — **não existe como repositório público standalone**, a busca no GitHub não achou nada
com esse nome; descartado). Baixado o instalador
(`virtual-desktop-driver-installer-x64.zip`, SHA256
`B3E3A5AB9B49BD56A7E753120CDDB1D913479BC5C0DFA781C3D43392DDE2FB75`) para
`agent/service/vdd/` (pasta **não versionada no git ainda** — são binários de terceiro, considerar se
entram no repo ou ficam só localmente nessa máquina; por ora só existem em
`C:\Users\thiago\git\transacao\agent\service\vdd\` e `agent\service\WinSW.exe`).

Executado (elevado via UAC, aprovado pelo usuário na hora):
1. `certutil -addstore -f root DriverCertificate.cer` + `-addstore -f TrustedPublisher` — certificado
   autoassinado do driver instalado nos repositórios de confiança do Windows. **Sucesso.**
2. `bcdedit /set testsigning on` — **sucesso**, confirmado via `bcdedit /enum` (`testsigning: Yes`).
   **Só faz efeito depois de reiniciar.**
3. `msiexec /i virtual-display-driver-0.3.1-x86_64.msi /qn /norestart` — **sucesso** (exit code
   `3010` = `ERROR_SUCCESS_REBOOT_REQUIRED`, código MSI padrão de "instalou certo, precisa reiniciar
   pra concluir"). `Get-PnpDevice -FriendlyName "*Virtual Display*"` já mostra o dispositivo
   `Virtual Display` com `Status: OK` mesmo antes do reboot (nó do dispositivo existe, mas o driver só
   carrega de fato depois de reiniciar com o test-signing já ativo).

**PENDENTE — próxima ação, só quando o usuário autorizar o horário**:
- **Reiniciar o Windows host.** Depois do reboot, verificar:
  - `Get-CimInstance Win32_VideoController` deve mostrar um 3º adaptador (o virtual-display-rs) além
    do Intel UHD Graphics 630 e do Microsoft Remote Display Adapter (esse último só aparece com RDP
    conectado).
  - Testar captura (`CopyFromScreen` ou o próprio agente) **com o RDP desconectado** — esse é o teste
    que realmente prova que o problema original foi resolvido.
  - O Windows mostrará uma marca d'água "Test Mode" no desktop (efeito colateral esperado e
    documentado do `bcdedit testsigning on` — cosmético, não afeta funcionamento).

#### 3.4.3 IMPORTANTE — instrução do usuário sobre como isso deve ser entregue (05/10/2026)

> "essa instalacao deve ser feita tbm pelo agent, caso habilitamos essa configuracao na maquina agent.
> Tudo deve ser retrocompatibilidade e em maquinas mais restritas"

Ou seja: **tudo que foi feito manualmente nas seções 3.4.1/3.4.2 (baixar WinSW, baixar o driver,
instalar certificado, ativar test-signing, instalar o MSI) foi só uma validação/prova de conceito
nesta máquina específica.** O entregável de verdade é código no **próprio agente Java** que faz essa
instalação sozinho, automaticamente, quando (e só quando) a configuração `serviceMode` dessa máquina
for ligada pelo painel do hub — nunca por padrão, nunca em máquina que não ligou essa opção.

**Retrocompatibilidade é obrigatória**: uma máquina com `serviceMode` desligado (o padrão, e o único
modo que existe hoje) tem que continuar funcionando EXATAMENTE como hoje — sem baixar nada, sem pedir
elevação, sem instalar certificado nenhum, sem tocar em `bcdedit`. Isso vale especialmente para
"máquinas mais restritas" (clients de terceiros sem admin, com Smart App Control/WDAC ativos de
verdade) — o caminho novo de serviço é estritamente opt-in e aditivo, nunca substitui o caminho atual
(HKCU\Run) como padrão.

Próxima IA/sessão: ao implementar a seção 3.4.4 abaixo, todo esse fluxo (download do WinSW e do
driver, instalação do certificado, `bcdedit`, instalação do MSI, instalação do serviço) precisa virar
um método Java (ex.: `ServiceModeInstaller.java`, novo arquivo em `agent/src/com/transacao/agent/`)
chamado quando o agente recebe/detecta `serviceMode=true` pela primeira vez — não um script PowerShell
que uma IA roda manualmente. Os binários de terceiro (WinSW, driver) devem ser baixados pelo PRÓPRIO
AGENTE em tempo de execução (ou empacotados no instalador, a decidir) — não ficar dependendo de uma
pasta preenchida manualmente como `agent/service/` está agora (essa pasta atual é só o resultado do
teste manual desta sessão, não é o mecanismo de entrega final).

#### 3.4.4 Implementado no agente/hub (05/10/2026) — código de verdade, não mais manual

Compilação limpa (`javac` de `src/com/transacao/common` + `agent/src`, zero erros) e
`cd hub && npm test` **37/37 passando** (2 testes novos: contagem de checkboxes ajustada +1, e um
teste dedicado ao fluxo de confirmação do toggle de modo serviço — ver abaixo).

- **`agent/src/com/transacao/agent/ServiceModeInstaller.java`** (novo arquivo): classe que faz, em
  código Java, exatamente os passos que tinham sido validados manualmente na seção 3.4.1/3.4.2 (baixar
  WinSW e o driver `virtual-display-rs` de URLs fixas do GitHub **conferindo SHA-256** contra os hashes
  gravados no código-fonte, instalar o certificado, `bcdedit testsigning on`, instalar o MSI do driver,
  registrar o próprio agente como Serviço do Windows via WinSW). Roda em background
  (`applyAsync`), só no Windows, só se `serviceMode=true`, idempotente (`alreadyInstalled()` via
  `sc query`), um único prompt de UAC (um script `.ps1` só, não vários `-Command` inline — evita os
  problemas de escape de aspas já documentados neste projeto). Baixa para
  `%LOCALAPPDATA%\TransacaoAgent\service-mode\` (fora do repo) — **não reinicia o Windows sozinho** e
  **não desfaz nada** se `serviceMode` for desligado depois (reverter test-signing/driver complexa
  decisão manual, não automática).
  **Instrução do usuário aplicada (05/10/2026)**: "a instalação dos recursos necessários devem ser
  feitas de forma individual, se um falhar, o outro que funcionou deve continuar, a não ser que sejam
  dependentes" — `install()` trata **WinSW** (vira Serviço) e o **driver de display virtual** (cert +
  test-signing + MSI) como duas trilhas independentes, cada uma em seu próprio `try/catch`; se o
  download de uma falhar (ex.: GitHub fora do ar, hash não bate), a outra continua normalmente e só
  entra no script elevado a seção correspondente à trilha que deu certo. Dentro de cada trilha os
  passos continuam sequenciais porque são de fato dependentes (não dá pra instalar o MSI sem ter
  baixado e extraído o zip antes, por exemplo). Só desiste de tudo (sem pedir UAC) se as DUAS trilhas
  falharem.
- **`agent/src/com/transacao/agent/AgentSettings.java`**: novo campo `serviceMode` (default `false`),
  persistido localmente (`local-settings.properties`, mesmo padrão de `startWithSystem`) e recebido do
  hub (`update(Map)`).
- **`agent/src/com/transacao/agent/AgentApp.java`**: na partida, se `serviceMode` já estava ligado
  numa sessão anterior E o serviço já existe, só confirma (não reinstala). Na troca ao vivo
  (`applySettingsLocked`), a transição desligado→ligado dispara `ServiceModeInstaller.applyAsync` uma
  única vez por processo; **desligar depois NÃO desfaz nada automaticamente** (só para de tentar
  reinstalar).
- **`hub/public/settings.js`**: novo toggle "Modo serviço (sobrevive a sessão bloqueada/RDP
  desconectado)", **fora da lista genérica `SETTINGS`** porque pede uma confirmação (`confirm()`)
  explicando a consequência (admin + reinício) ANTES de ligar — cancelar desmarca de volta e não manda
  nada ao hub.
- **`hub/src/store.js`**: `serviceMode` adicionado a `DEFAULT_SETTINGS` (`false`) e `VALIDATORS` —
  máquinas existentes continuam com o valor padrão `false`, comportamento inalterado.
- **Retrocompatibilidade confirmada**: nada disso roda a menos que `serviceMode=true` seja setado
  para aquela máquina especificamente; o padrão de toda máquina (inclusive as já existentes, que nunca
  tiveram essa chave) é `false` — comportamento idêntico a antes desta sessão.
- A pasta `agent/service/` (resultado do teste manual das seções 3.4.1/3.4.2, com os binários de
  terceiro baixados à mão) foi **removida do working tree** — não é o mecanismo de entrega final
  (o `ServiceModeInstaller` baixa suas próprias cópias em tempo de execução para
  `%LOCALAPPDATA%\TransacaoAgent\service-mode\`, fora do repo). As mudanças já feitas NO SISTEMA
  operacional desta máquina (certificado instalado, `testsigning=on`, driver MSI instalado) continuam
  valendo — só os arquivos de scratch dentro do repo foram apagados.

#### 3.4.5 Ainda não feito

1. **Testar `ServiceModeInstaller` de ponta a ponta de verdade** (ligar `serviceMode=true` pelo
   painel nesta máquina e confirmar que o serviço sobe envolvendo o agente de verdade, não só o
   serviço de PowerShell descartável testado manualmente). Ainda não foi exercitado - o código
   compila e os testes JS cobrem só a UI do toggle, não o fluxo Java completo rodando de verdade.
2. **Reiniciar o Windows host** (pendente, a critério do usuário, fora de horário de uso) para o
   driver de display virtual e o test-signing realmente ativarem. Depois do reboot, verificar
   `Get-CimInstance Win32_VideoController` (deve aparecer um 3º adaptador) e testar captura com RDP
   desconectado.
3. Configurar login automático nessa conta do Windows na sessão do CONSOLE físico (não a RDP) — sem
   isso, mesmo com o driver de display virtual dando uma tela sempre disponível, ninguém está logado
   ali pra o agente ter uma sessão de usuário pra rodar. Decidir: `netplwiz`/registro
   `AutoAdminLogon`, ou o serviço LocalSystem fazer `CreateProcessAsUser` com um token de sessão
   específico (evita guardar a senha do Windows em texto reversível só pra autologon, mas é mais
   código) — **não implementado ainda, nem decidido**.
4. Auto-desbloqueio da tela de login com senha guardada — **reconfirmar com o usuário se ainda é
   necessário**: com login automático no console + display virtual sempre presente, a sessão pode
   nunca precisar bloquear de verdade (sem RDP pra desconectar, ninguém aperta Win+L numa sessão sem
   monitor físico/teclado real), tornando isso desnecessário. Se ainda for preciso, implica em UI de
   senha por máquina (criptografada, nunca devolvida em texto puro) e trocar pro desktop seguro do
   Winlogon via `OpenInputDesktop`/`SetThreadDesktop` do Win32 antes de cada operação de
   captura/input — código novo, zero precedente no projeto hoje.

---

## 4. Arquivos tocados nesta sessão (até agora)

> **Atualização posterior — modo serviço descontinuado (05/10/2026):** esta linha de trabalho foi
> removida do produto por ser complexa e pouco efetiva. O hub agora apaga a preferência legada
> `serviceMode` de todas as máquinas ao carregar sua base; ela não pode mais ser enviada pelo painel.
> O agente novo não instala, baixa ou ativa WinSW, driver virtual, certificado ou Test Signing. Ao
> iniciar, se detectar componentes deixados pela experiência anterior, solicita UAC uma única vez para
> remover o serviço, desinstalar o driver, retirar o certificado e executar `bcdedit /set testsigning off`.
> A reinicialização continua sendo decisão do usuário — ela é necessária apenas para desaparecer a
> marca d'água já criada pelo Windows. Máquinas com a versão legada rodando como serviço podem exigir
> essa remoção administrativa única antes de conseguirem receber o agente novo, pois o próprio serviço
> mantém o JAR antigo bloqueado; não há atualização automática segura que consiga substituir um arquivo
> em execução nessas condições.

| Arquivo | O que mudou |
|---|---|
| `hub/public/viewer.js` | `sendLocalClipboard` (toast de erro, chamada fora do queueKey), `keyHandler` (Set `pressedCodes`, keyup sempre repassado, `releaseAllKeys` em blur/visibilitychange), `close()` limpa `pressedCodes` |
| `agent/src/com/transacao/agent/RemoteSession.java` + `AgentApp.java` | Clipboard recebido pelo canal de controle WebRTC é aplicado antes das teclas posteriores desse mesmo canal, preservando a ordem com Ctrl+V |
| `agent/src/com/transacao/agent/LegacyServiceModeCleanup.java` | Migração de remoção: elimina o serviço/driver/certificado/Test Signing legados depois de confirmação UAC; não contém caminho de instalação |
| `src/com/transacao/common/remote/InputInjector.java` | Novo `typeUnicodeWindows`/`ensureUnicodeHelper`/`closeUnicodeHelper` (SendInput+KEYEVENTF_UNICODE via helper PowerShell persistente); `typeAltNumpad` mantido só como fallback não-Windows |
| `hub/test/ui.test.js` | Regressões para modificador não travar, fechamento dos painéis da bolinha e ordem de clipboard+Ctrl+V em WebRTC |

Na altura do registro histórico acima, nenhum arquivo de `agent/src` tinha sido alterado para a parte
de serviço/WinSW. Essa conclusão foi substituída pela atualização posterior de descontinuação.

## 5. Próximos passos (em ordem)

1. **Não implementar serviço/WinSW nem driver virtual.** A funcionalidade foi descontinuada; o único
   caminho restante é a migração de remoção documentada na atualização da seção 4.
2. Depois de resolvida a sessão 3, considerar também dar suporte a caracteres fora do Plano Básico
   Multilíngue (emoji verdadeiro, surrogate pairs) — hoje `InputHandler.java:106` só lê
   `ch.charAt(0)` de mensagens `kt`, então um emoji (2 code units) perde a segunda metade. Não
   reportado pelo usuário ainda, achado incidental durante a investigação do item 2.2 — baixa
   prioridade.

## 6. Auditoria central correlacionada do acesso remoto (06/10/2026)

- O navegador atribui `sessionId + sequência + trace` a rolagem, clique, comandos físicos de tecla,
  texto Unicode e clipboard. Antes do envio, grava uma versão sanitizada numa outbox do navegador;
  uma reconexão/F5 reenvia ao hub apenas os metadados ainda não confirmados.
- O hub persiste em `data/access-logs/<clientId>.jsonl`: abertura/fechamento da sessão, evento observado,
  encaminhamento no modo compatível e aplicação/rejeição confirmada pelo agente. WebRTC não ganha uma
  fase artificial de encaminhamento pelo hub, pois o controle segue direto pelo data channel.
- O agente mantém resultados pendentes até receber `input-result-ack`; os logs técnicos gerais usam
  `agent-log-ack`. O hub elimina reenvios duplicados por ID/trace, inclusive depois de recarregar os
  arquivos existentes.
- Conteúdo digitado, texto do clipboard, nomes/caminhos de arquivos e coordenadas nunca entram nessa
  auditoria. Ficam apenas categoria do evento, código físico quando necessário para diagnóstico de
  layout, modificadores implícitos no próprio fluxo, contagem de caracteres, transporte e resultado.
- Há regressões automatizadas que correlacionam `observed -> forwarded -> applied`, simulam reenvio com
  confirmação perdida e procuram deliberadamente um texto secreto no JSONL para garantir que ele não
  foi persistido.

## 7. Imagem copiada pelo operador para o clipboard remoto (06/10/2026)

- Ao colar dentro do visualizador, Safari e navegadores Chromium são consultados por
  `ClipboardEvent.clipboardData.files` e também por `clipboardData.items`. Uma captura PNG é enviada
  ao clipboard da máquina acessada e o Ctrl/Cmd+V remoto só é emitido depois da confirmação do agente.
- O protocolo é negociado por `clipboardImageV1`. Agentes antigos não recebem comandos desconhecidos:
  preservam o comportamento anterior, no qual a imagem colada segue como arquivo para `recebimentos`.
- Em WebRTC, metadados e bytes PNG usam o mesmo data channel confiável e ordenado do teclado. No modo
  compatível, o navegador faz upload autenticado de um blob temporário no hub e o agente o consome em
  memória uma única vez; blobs abandonados expiram em cinco minutos.
- O agente valida limite de 25 MB, assinatura PNG, dimensões antes da decodificação (máximo de 40
  milhões de pixels) e SHA-256. Pixels não são gravados em arquivo nem em log. A auditoria central
  registra apenas tamanho, transporte e resultado correlacionado.
- Restrição de navegador: a página não monitora silenciosamente o clipboard. No Safari, a leitura é
  acionada pelo gesto de colar (`Cmd+V`) dentro do acesso remoto; capturar a tela sem depois colar não
  dispara envio automático.
