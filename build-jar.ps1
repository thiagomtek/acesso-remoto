# Empacota as classes compiladas em out/ como jars executaveis (server e client),
# organiza a estrutura completa da pasta dist e gera o pacote zip de primeira instalacao do client.
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

# 4. Conteudo dos scripts de inicializacao .bat e .vbs
$batServer = @"
@echo off
cd /d "%~dp0"
start "" javaw -jar transacao-server.jar
"@

$batClient = @"
@echo off
cd /d "%~dp0"
start "" javaw -jar transacao-client.jar
"@

$batServerConsole = @"
@echo off
cd /d "%~dp0"
java -jar transacao-server.jar
pause
"@

$batClientConsole = @"
@echo off
cd /d "%~dp0"
java -jar transacao-client.jar
pause
"@

$vbsClient = @"
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
shell.Run "javaw -jar transacao-client.jar --background", 0, False
"@

$batInstallStartup = @'
@echo off
title Instalacao do Transacao Client
cd /d "%~dp0"
echo Configurando inicio automatico com o Windows (em segundo plano)...
powershell -NoProfile -Command "$startup = [Environment]::GetFolderPath('Startup'); $vbsPath = Join-Path $startup 'TransacaoClient.vbs'; $curDir = (Get-Location).Path; $vbsContent = 'Set shell = CreateObject(""WScript.Shell"")' + [Environment]::NewLine + 'shell.CurrentDirectory = ""' + $curDir + '""' + [Environment]::NewLine + 'shell.Run ""javaw -jar """"' + (Join-Path $curDir 'transacao-client.jar') + '"""" --background"", 0, False' + [Environment]::NewLine; [IO.File]::WriteAllText($vbsPath, $vbsContent, [Text.Encoding]::ASCII); Write-Host 'Inicio automatico configurado com sucesso em:' $vbsPath"
echo.
echo Iniciando o client agora em segundo plano...
start "" javaw -jar transacao-client.jar --background
echo.
echo Pronto! O Transacao Client ja esta em execucao em segundo plano.
timeout /t 3 >nul
'@

$batUninstallStartup = @'
@echo off
title Desinstalar Inicio Automatico
powershell -NoProfile -Command "$startup = [Environment]::GetFolderPath('Startup'); $vbsPath = Join-Path $startup 'TransacaoClient.vbs'; if (Test-Path $vbsPath) { Remove-Item $vbsPath -Force; Write-Host 'Inicio automatico removido com sucesso.' } else { Write-Host 'Nao estava configurado.' }"
pause
'@

# Salva launchers na raiz de dist
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\instalar-inicializacao-automatica.bat"), $batInstallStartup, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\desinstalar-inicializacao-automatica.bat"), $batUninstallStartup, [System.Text.Encoding]::ASCII)

# Salva launchers nas subpastas Server e Client
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\instalar-inicializacao-automatica.bat"), $batInstallStartup, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\desinstalar-inicializacao-automatica.bat"), $batUninstallStartup, [System.Text.Encoding]::ASCII)

# 5. Gera o pacote ZIP de primeira instalacao do Client para distribuicao
$clientZip = Join-Path $here "dist\transacao-client-instalador.zip"
if (Test-Path $clientZip) { Remove-Item $clientZip -Force }

Write-Host "Gerando pacote ZIP de instalacao do Client..."
Compress-Archive -Path "$here\dist\Client\*" -DestinationPath $clientZip -Force

Write-Host "Estrutura completa e pacote ZIP gerados em dist\ com sucesso!"
