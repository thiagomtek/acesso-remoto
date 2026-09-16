# Empacota as classes compiladas em out/ como jars executaveis (server e client)
# e organiza a estrutura completa da pasta dist para execucao direta e auto-update.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# Sempre compila antes de empacotar para garantir que as alteracoes estejam no jar
Write-Host "Compilando fontes..."
& .\compile.ps1

# Cria pastas necessarias na raiz de dist
$distDirs = @(
    "dist",
    "dist\certs",
    "dist\updates",
    "dist\Server",
    "dist\Server\certs",
    "dist\Server\updates",
    "dist\Client",
    "dist\Client\certs"
)
foreach ($dir in $distDirs) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
}

# 1. Gera os JARs executaveis na raiz de dist
Push-Location out
jar --create --file ..\dist\transacao-server.jar --main-class com.transacao.server.ServerApp com\transacao\common com\transacao\server
jar --create --file ..\dist\transacao-client.jar --main-class com.transacao.client.ClientApp com\transacao\common com\transacao\client
Pop-Location

# 2. Copia os JARs para as pastas dedicadas (Server / Client) e pasta de updates (para auto-atualizacao)
Copy-Item "dist\transacao-server.jar" "dist\Server\transacao-server.jar" -Force
Copy-Item "dist\transacao-client.jar" "dist\Client\transacao-client.jar" -Force
Copy-Item "dist\transacao-client.jar" "dist\updates\transacao-client.jar" -Force
Copy-Item "dist\transacao-client.jar" "dist\Server\updates\transacao-client.jar" -Force

# 3. Copia certificados para dist\certs, dist\Server\certs e dist\Client\certs
if (Test-Path "certs") {
    Copy-Item "certs\*" "dist\certs\" -Recurse -Force
    
    # Server certs
    if (Test-Path "certs\server.jks") { Copy-Item "certs\server.jks" "dist\Server\certs\" -Force }
    if (Test-Path "certs\server-truststore.jks") { Copy-Item "certs\server-truststore.jks" "dist\Server\certs\" -Force }
    if (Test-Path "certs\server.cer") { Copy-Item "certs\server.cer" "dist\Server\certs\" -Force }
    
    # Client certs
    if (Test-Path "certs\client.jks") { Copy-Item "certs\client.jks" "dist\Client\certs\" -Force }
    if (Test-Path "certs\client-truststore.jks") { Copy-Item "certs\client-truststore.jks" "dist\Client\certs\" -Force }
    if (Test-Path "certs\client.cer") { Copy-Item "certs\client.cer" "dist\Client\certs\" -Force }
}

# 4. Cria scripts de inicializacao .bat e .vbs (execucao silenciosa sem tela preta)
$batServer = "@echo off`r`ncd /d `"%~dp0`"`r`nstart `"`" javaw -jar transacao-server.jar`r`n"
$batClient = "@echo off`r`ncd /d `"%~dp0`"`r`nstart `"`" javaw -jar transacao-client.jar`r`n"
$batServerConsole = "@echo off`r`ncd /d `"%~dp0`"`r`njava -jar transacao-server.jar`r`npause`r`n"
$batClientConsole = "@echo off`r`ncd /d `"%~dp0`"`r`njava -jar transacao-client.jar`r`npause`r`n"

$vbsClient = "Set shell = CreateObject(`"WScript.Shell`")`r`nshell.CurrentDirectory = CreateObject(`"Scripting.FileSystemObject`").GetParentFolderName(WScript.ScriptFullName)`r`nshell.Run `"javaw -jar transacao-client.jar`", 0, False`r`n"

# Salva launchers na raiz de dist
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)

# Salva launchers nas subpastas Server e Client
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)

Write-Host "Estrutura completa gerada em dist\ com sucesso!"
