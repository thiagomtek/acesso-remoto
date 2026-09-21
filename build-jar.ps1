# Compila e organiza os pacotes finais na pasta dist de forma limpa e modular.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

# 1. Compila os fontes para a pasta out/
Write-Host "Compilando fontes..."
& .\compile.ps1

# 2. Limpa e recria a estrutura da pasta dist/
Write-Host "Organizando estrutura da pasta dist..."
if (Test-Path "dist") {
    Remove-Item -Path "dist" -Recurse -Force
}

$distDirs = @(
    "dist\Server\certs",
    "dist\Server\updates",
    "dist\Client\certs"
)
foreach ($dir in $distDirs) {
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
}

# 3. Empacota os JARs executaveis diretamente nas pastas correspondentes
Push-Location out
jar --create --file ..\dist\Server\transacao-server.jar --main-class com.transacao.server.ServerApp com\transacao\common com\transacao\server
jar --create --file ..\dist\Client\transacao-client.jar --main-class com.transacao.client.ClientApp com\transacao\common com\transacao\client
Pop-Location

# 4. Copia o JAR do client para a pasta de atualizacoes do servidor (auto-update)
Copy-Item "dist\Client\transacao-client.jar" "dist\Server\updates\transacao-client.jar" -Force

# 5. Copia os certificados especificos de cada lado
if (Test-Path "certs") {
    # Certificados do Servidor
    if (Test-Path "certs\server.jks") { Copy-Item "certs\server.jks" "dist\Server\certs\" -Force }
    if (Test-Path "certs\server-truststore.jks") { Copy-Item "certs\server-truststore.jks" "dist\Server\certs\" -Force }
    
    # Certificados do Client
    if (Test-Path "certs\client.jks") { Copy-Item "certs\client.jks" "dist\Client\certs\" -Force }
    if (Test-Path "certs\client-truststore.jks") { Copy-Item "certs\client-truststore.jks" "dist\Client\certs\" -Force }
}

# 6. Scripts de inicializacao para o Servidor
$batServer = @"
@echo off
cd /d "%~dp0"
start "" javaw -jar transacao-server.jar
"@

$batServerConsole = @"
@echo off
cd /d "%~dp0"
java -jar transacao-server.jar
pause
"@

[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)

# 7. Scripts de inicializacao e instalacao para o Client
$batClient = @"
@echo off
cd /d "%~dp0"
start "" javaw -jar transacao-client.jar
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

[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\instalar-inicializacao-automatica.bat"), $batInstallStartup, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\desinstalar-inicializacao-automatica.bat"), $batUninstallStartup, [System.Text.Encoding]::ASCII)

# 8. Gera o pacote ZIP de instalacao do Client
$clientZip = Join-Path $here "dist\transacao-client-instalador.zip"
Write-Host "Gerando pacote ZIP de instalacao do Client..."
Compress-Archive -Path "$here\dist\Client\*" -DestinationPath $clientZip -Force

Write-Host "Build concluido com sucesso! Estrutura limpa em dist\"
