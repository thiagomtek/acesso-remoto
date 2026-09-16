# Transacao - Transferencia de arquivos via SSL (server/client)

Dois apps Java com interface Swing:

- **Servidor** (`com.transacao.server.ServerApp`): fica ouvindo uma porta e aceita a conexao do client.
- **Client** (`com.transacao.client.ClientApp`): conecta no servidor.

Depois de conectados, **qualquer um dos dois lados** pode enviar arquivos/pastas para o outro (comunicacao de mao dupla), pela mesma conexao SSL com autenticacao mutua (client + server, cada um valida o certificado do outro).

## Regras de transferencia

- Selecionar um **arquivo `.zip`**: e transferido em modo binario, byte a byte, chegando identico do outro lado.
- Selecionar uma **pasta** (com subpastas de codigo fonte, etc.): todos os arquivos dentro sao lidos e transferidos como **texto**, e a mesma estrutura de pastas/arquivos e recriada na outra maquina.
- Qualquer outro tipo de arquivo avulso (nao `.zip`) precisa ser zipado antes, ou enviado dentro de uma pasta (sera tratado como texto).

## Pre-requisitos

JDK 17+ instalado (`java`, `javac`, `keytool` no PATH).

## 1. Gerar certificados SSL (uma vez)

```powershell
cd certs
.\gen-certs.ps1
```

Isso cria, dentro de `certs/`:
- `server.jks` / `client.jks` — par de chaves de cada lado
- `server-truststore.jks` — truststore do servidor, confia no certificado do client
- `client-truststore.jks` — truststore do client, confia no certificado do servidor

Senha padrao de tudo: `changeit` (ja preenchida nos campos da interface).

**Para usar em maquinas diferentes**: copie a pasta `certs/` inteira (ou pelo menos `server.jks` + `server-truststore.jks` para a maquina do servidor, e `client.jks` + `client-truststore.jks` para a maquina do client).

## 2. Compilar

```powershell
.\compile.ps1
```

Gera as classes em `out/`.

## 3. Rodar

Na maquina servidor:
```powershell
.\run-server.ps1
```
Clique em "Iniciar servidor".

Na maquina client:
```powershell
.\run-client.ps1
```
Preencha o host/IP do servidor e clique em "Conectar".

Depois disso, em qualquer uma das duas janelas: "Selecionar arquivo .zip ou pasta..." e depois "Enviar". Os arquivos recebidos caem na pasta configurada em "Pasta de saida" (por padrao `recebidos_server` / `recebidos_client`).

## Liberar a porta no firewall (maquina servidor)

Se client e servidor estiverem em maquinas diferentes, libere a porta (padrao 9443) no Firewall do Windows na maquina servidor:

```powershell
New-NetFirewallRule -DisplayName "Transacao SSL" -Direction Inbound -Protocol TCP -LocalPort 9443 -Action Allow
```
