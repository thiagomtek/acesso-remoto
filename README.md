# Transacao

Aplicativo Java (Swing, sem Maven/Gradle) com dois papeis, **Servidor** e **Client**, que juntos oferecem transferencia de arquivos, acesso remoto (tela, mouse, teclado), audio e atualizacao automatica sobre uma unica conexao TLS com autenticacao mutua.

- **Servidor** (`com.transacao.server.ServerApp`): fica ouvindo uma porta e aceita conexoes de varios clients ao mesmo tempo.
- **Client** (`com.transacao.client.ClientApp`): conecta em um servidor (manualmente ou por descoberta automatica na rede).

## Funcionalidades

### Transferencia de arquivos
Depois de conectados, qualquer um dos dois lados pode enviar arquivos/pastas para o outro (comunicacao de mao dupla), pela mesma conexao.

- Selecionar um **arquivo `.zip`**: e transferido em modo binario, byte a byte, chegando identico do outro lado.
- Selecionar uma **pasta**: todos os arquivos dentro sao lidos e transferidos como texto, recriando a mesma estrutura de pastas/arquivos na outra maquina.
- Qualquer outro tipo de arquivo avulso (nao `.zip`) precisa ser zipado antes, ou enviado dentro de uma pasta.

### Acesso remoto
- O **Client compartilha a propria tela**; o **Servidor visualiza e controla** (mouse/teclado).
- Transmissao em blocos (tiles) que so reenvia o que muda na tela, comprimidos sem perdas (PNG) — sem os artefatos de blocagem do JPEG, com pouco trafego.
- Captura na resolucao fisica real do monitor (nao a reduzida pela escala do Windows), para manter nitidez em telas com escala 125%/150%.
- Area de transferencia (clipboard) sincronizada nos dois sentidos: texto e arquivos copiados em qualquer lado ficam disponiveis para colar no outro.

### Audio
- O **Servidor pode compartilhar seu microfone** com o Client.
- O **Client pode compartilhar o audio do sistema** (precisa de um dispositivo de captura tipo "Stereo Mix" habilitado no Windows) com o Servidor.
- Para o microfone do Servidor aparecer como dispositivo de entrada de verdade para outros programas no Client (Zoom, Teams, etc.), instale o [VB-Audio Virtual Cable](https://vb-audio.com/Cable/) e selecione "CABLE Input" na aba Audio do Client.

### Multiplos clients
O Servidor aceita varias conexoes simultaneas. Uma barra "Client ativo" no topo da janela lista todos os clients conectados; as abas Transferencia/Acesso Remoto/Audio sempre operam sobre o client selecionado no momento.

### Descoberta automatica na rede
O Client varre a rede local por broadcast UDP procurando um Servidor, repetindo a cada 30 segundos ate encontrar (sem precisar digitar IP manualmente). Tambem tenta os peers do [Tailscale](https://tailscale.com/) diretamente (via `tailscale status`), ja que VPNs mesh normalmente nao propagam trafego de broadcast entre os peers.

Fora da rede local/Tailscale, digitar o IP do Servidor manualmente no campo "Host do servidor" tambem funciona normalmente (inclusive um IP Tailscale `100.x.x.x`).

### Reconexao automatica
Se o Client perder a comunicacao com o Servidor, ele se desconecta e volta a varrer a rede sozinho ate encontrar um Servidor de novo.

### Auto-atualizacao do Client
O Client manda, ao conectar, o hash SHA-256 do proprio `.jar`. O Servidor compara com o arquivo configurado em "Jar do client mais recente" (por padrao `../Client/transacao-client.jar`, relativo a pasta do Servidor); se forem diferentes, envia a nova versao automaticamente pela mesma conexao, e o Client se atualiza e reinicia sozinho, sem intervencao do usuario.

### Iniciar com o Windows
O Client tem uma opcao "Iniciar com o Windows (neste usuario)" que registra um atalho na pasta de Inicializacao pessoal do usuario atual — nao mexe em nada global da maquina e nao precisa de admin.

### Senha na primeira execucao
Na primeira vez que o Client e aberto neste usuario do Windows, ele pede uma senha antes de mostrar qualquer tela. Depois de acertar uma vez, nao pede mais (inclusive quando abrir sozinho pelo inicio automatico).

## Pre-requisitos

JDK 17+ instalado (`java`, `javac`, `keytool` no PATH).

## Estrutura do projeto

```
src/com/transacao/
  client/    ClientApp.java
  server/    ServerApp.java, ClientSession.java
  common/    protocolo, transferencia de arquivos, discovery, auto-update, etc.
    remote/  tela remota, audio, clipboard, input
    discovery/ descoberta automatica na rede/Tailscale
certs/       script de geracao de certificados (os .jks nao ficam no repositorio)
```

## 1. Gerar certificados SSL (uma vez)

```powershell
cd certs
.\gen-certs.ps1
```

Isso cria, dentro de `certs/` (fora do controle de versao, ja que sao chaves privadas):
- `server.jks` / `client.jks` — par de chaves de cada lado
- `server-truststore.jks` — truststore do servidor, confia no certificado do client
- `client-truststore.jks` — truststore do client, confia no certificado do servidor

Senha padrao de tudo: `changeit` (ja preenchida nos campos da interface).

**Para usar em maquinas diferentes**: copie `server.jks` + `server-truststore.jks` para a maquina do servidor, e `client.jks` + `client-truststore.jks` para a maquina do client.

## 2. Compilar

```powershell
.\compile.ps1
```

Gera as classes em `out/`.

## 3. Empacotar em .jar (opcional)

```powershell
.\build-jar.ps1
```

Gera `dist/transacao-server.jar` e `dist/transacao-client.jar`, cada um executavel com `java -jar`.

## 4. Rodar

Na maquina servidor:
```powershell
.\run-server.ps1
```
Clique em "Iniciar servidor".

Na maquina client:
```powershell
.\run-client.ps1
```
O Client ja comeca a procurar um Servidor na rede sozinho; ou preencha o host/IP manualmente e clique em "Conectar".

Depois de conectado, em qualquer uma das duas janelas: "Selecionar arquivo .zip ou pasta..." e depois "Enviar". Os arquivos recebidos caem na pasta configurada em "Pasta de saida" (por padrao `recebidos_server` / `recebidos_client`).

## Liberar as portas no firewall (maquina servidor)

Se client e servidor estiverem em maquinas diferentes na mesma rede/VPN, libere as portas no Firewall do Windows na maquina servidor: a porta TCP configurada para a conexao (padrao 9443) e a porta UDP de descoberta automatica (9445).

```powershell
New-NetFirewallRule -DisplayName "Transacao SSL" -Direction Inbound -Protocol TCP -LocalPort 9443 -Action Allow
New-NetFirewallRule -DisplayName "Transacao Discovery" -Direction Inbound -Protocol UDP -LocalPort 9445 -Action Allow
```

## Escopo e limitacoes conhecidas

- Sem relay/NAT traversal: o Client conecta direto no IP:porta do Servidor (mesma rede local ou VPN tipo Tailscale).
- O controle remoto (tela/mouse/teclado) exige que a sessao do Windows no Client esteja **desbloqueada e ativa** — nao funciona com a tela de bloqueio (Win+L) ou apos suspensao, pela mesma limitacao que afeta qualquer app baseado em `java.awt.Robot`. Apenas desligar o monitor (sem bloquear/suspender) nao afeta o funcionamento.
- Compartilhamento de camera nao esta implementado (exigiria um driver de camera virtual nativo, fora do escopo de um app Java puro).
