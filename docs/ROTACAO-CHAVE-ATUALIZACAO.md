# Rotação da chave de assinatura das atualizações (24/11/2026)

## O que aconteceu
A chave privada Ed25519 que assina o `agent-update.zip` ficava só em `~\.remote-hub\update-signing.key` no PC
Windows de publicação e não estava em nenhum backup (o restic cobre apenas `/mnt/d/Servidores`). Como os agentes só
aceitam atualização assinada pela chave pública embutida no jar (`AgentUpdater.PUBLIC_KEY_B64`) ou pela anterior
(`PREVIOUS_PUBLIC_KEY_B64`), sem a privada não havia como publicar.

## Estado depois da rotação
- Par de chaves novo gerado no servidor Linux (`agent/tools/gen-update-key.js`).
  - Privada: `~/.remote-hub/update-signing.key` **e** cópia em `/mnt/d/Servidores/Configuracoes/Atualizacao/update-signing.key`
    (permissão 600, dentro do que o restic salva). Guarde também uma cópia offline.
  - Pública nova: `MCowBQYDK2VwAyEABg+z1xR75bYgB6P2hPOi7a1dJO+2PwNRqFkJF65rcXM=`
- `AgentUpdater.PUBLIC_KEY_B64` = chave nova; `PREVIOUS_PUBLIC_KEY_B64` = a chave pública antiga (cuja privada se perdeu).
- Pacote publicado no hub (`data/updates`) e jars do `data/installer-base` são o **mesmo jar** (hash em `meta.json`).
  Cópia do estado anterior: `/mnt/d/Servidores/Servicos/remote-hub/backup-pre-rotacao-chave-20261124/`.
- Publicações futuras: `agent/publish-update.sh` (compila, assina, publica e atualiza o installer-base).

## O que falta: trocar o jar UMA vez em cada máquina
Os agentes já instalados só confiam nas chaves antigas, então **não conseguem** baixar este pacote sozinhos (a
tentativa aparece no log como `ASSINATURA INVALIDA: pacote recusado`, no máximo 3 por versão). Depois da troca manual,
o auto-atualizador volta a funcionar.

Máquinas: `servidorLoja`, `cameraLoja`, `os2h-alp-no0344` (esta é a máquina restrita: evita `.bat` e PowerShell).

Arquivo a distribuir (no servidor): `/mnt/d/Servidores/Servicos/remote-hub/data/installer-base/transacao-agent.jar`
(no Windows do host: `D:\Servidores\Servicos\remote-hub\data\installer-base\transacao-agent.jar`).
Confira o hash no `meta.json`: o `jarSha256` precisa ser igual ao do arquivo copiado
(`certutil -hashfile transacao-agent.jar SHA256`).

### Caminho A (simples; precisa de acesso à máquina por outro meio que não o próprio agente)
1. No menu da bandeja do agente, **Sair** (a sessão remota pelo agente cai).
2. Na pasta do agente, trocar **os dois** arquivos `transacao-agent.jar` e `assistente.jar` pelo jar novo (mesmo conteúdo).
   Não mexer na pasta `lib\`: as bibliotecas não mudaram. Guardar os antigos como `.bak` permite voltar atrás.
3. Abrir de novo pelo atalho (`iniciar-assistente.bat`) ou pelo que a máquina já usa.
4. No painel do hub, a máquina deve voltar online com o hash `3e79b331`.

### Caminho B (só há o acesso pelo próprio agente) — NÃO TESTADO
O `UpdateSwapper` troca o jar com backup e desfaz sozinho se a versão nova não ficar saudável. A ideia é rodá-lo à mão:
1. Enviar o jar novo para a máquina pela transferência de arquivos do hub e salvá-lo na pasta do agente como
   `<jar em uso>.new` (o jar em uso é o que o processo `javaw` carregou: `assistente.jar` ou `transacao-agent.jar`).
2. Copiar o jar em uso para uma cópia temporária (o original ficará travado enquanto o agente rodar) e descobrir o PID
   do `javaw.exe` do agente.
3. Num `cmd` aberto pela sessão remota, disparar o swapper **antes** de sair do agente:
   `start "" javaw -cp %TEMP%\swap.jar com.transacao.agent.UpdateSwapper <PID> <pasta> 90 <jarSha256 do meta> javaw <pasta>\<jar em uso> <pasta>\<jar em uso> <pasta>\<jar em uso>.new`
4. Sair do agente pela bandeja. O swapper espera o processo terminar, troca, sobe a versão nova e confere o sinal de saúde.
   Log em `<pasta>\agent-update.log`.

Se qualquer coisa der errado, a conexão remota some junto com o agente: faça o Caminho A quando houver alternativa.

## Se a chave nova também for perdida
Repete-se o problema. Restaurar de `/mnt/d/Servidores/Configuracoes/Atualizacao/` (restic) ou da cópia offline.
Para conferir que o backup tem a chave: `restic find update-signing.key` no repositório `rclone:gdrive:Homelab_Restic_Backup`
(depois do próximo backup, às 19h).
