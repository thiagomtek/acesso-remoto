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

# 6. Script compartilhado que localiza o javaw.exe/java.exe na maquina onde
# roda (Servidor ou Client) - NUNCA depende do PATH (motivo real, ja visto em
# producao, de "iniciar com o Windows" falhar silenciosamente numa maquina: o
# atalho da pasta Inicializacao roda antes do PATH do usuario estar
# totalmente carregado, ou o Java so foi adicionado ao PATH de outro usuario).
# Busca, em ordem: JAVA_HOME, PATH (Get-Command), e por fim varre as pastas
# de instalacao mais comuns. So falha (imprime nada) se realmente nao achar
# nenhum Java instalado.
$resolveJavaPs1 = @'
param([switch]$Console)
$exeName = if ($Console) { "java.exe" } else { "javaw.exe" }

if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\$exeName"
    if (Test-Path $candidate) { Write-Output $candidate; exit 0 }
}

$cmd = Get-Command $exeName -ErrorAction SilentlyContinue
if ($cmd) { Write-Output $cmd.Source; exit 0 }

$roots = @(
    $env:ProgramFiles,
    ${env:ProgramFiles(x86)},
    (Join-Path $env:LOCALAPPDATA "Programs")
) | Where-Object { $_ -and (Test-Path $_) }

foreach ($root in $roots) {
    $found = Get-ChildItem -Path $root -Filter $exeName -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { Write-Output $found.FullName; exit 0 }
}

exit 1
'@

# 7. Scripts de inicializacao para o Servidor
$batServer = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1"') do set "JAVAW=%%i"
if not defined JAVAW (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
start "" "%JAVAW%" -jar transacao-server.jar
'@

$batServerConsole = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1" -Console') do set "JAVA=%%i"
if not defined JAVA (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
"%JAVA%" -jar transacao-server.jar
pause
'@

$commandServer = @'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

if [ -x "$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java" ]; then
    JAVA_CMD="$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Java nao encontrado nesta maquina. Instale o Java (JRE 17+)."
    read -p "Pressione Enter para sair..."
    exit 1
fi

"$JAVA_CMD" -Djava.net.preferIPv4Stack=true -jar "$DIR/transacao-server.jar"
'@

[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\resolve-java.ps1"), $resolveJavaPs1, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor.bat"), $batServer, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor-console.bat"), $batServerConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Server\iniciar-servidor.command"), $commandServer, [System.Text.Encoding]::ASCII)

# 8. Scripts de inicializacao e instalacao para o Client
$batClient = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1"') do set "JAVAW=%%i"
if not defined JAVAW (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
start "" "%JAVAW%" -jar transacao-client.jar
'@

$batClientConsole = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1" -Console') do set "JAVA=%%i"
if not defined JAVA (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
"%JAVA%" -jar transacao-client.jar
pause
'@

# So delega para iniciar-client.bat (janela 0 = totalmente escondida) - toda
# a logica de encontrar o Java mora num unico lugar (resolve-java.ps1),
# entao um atalho de inicializacao automatica nunca fica com uma copia
# desatualizada/divergente dessa logica.
$vbsClient = @'
Set shell = CreateObject("WScript.Shell")
dir = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
shell.Run """" & dir & "\iniciar-client.bat""", 0, False
'@

# Scripts .ps1 dedicados para instalar/remover o atalho na pasta
# Inicializacao - evita uma linha "-Command" gigante com aspas triplamente
# aninhadas (cmd -> PowerShell -> conteudo do .vbs gerado), que e fragil e
# dificil de revisar; um arquivo .ps1 normal nao tem esse problema.
$installStartupPs1 = @'
$ErrorActionPreference = "Stop"
$installDir = $PSScriptRoot
$startup = [Environment]::GetFolderPath("Startup")
$vbsPath = Join-Path $startup "TransacaoClient.vbs"
$target = Join-Path $installDir "iniciar-client-oculto.vbs"
# Usa Chr(34) para as aspas em vez de escapa-las no texto do .vbs - evita ter
# que "dobrar aspas" a mao (fragil de revisar) so pra fechar um caminho que
# pode ter espacos.
$line1 = "Set shell = CreateObject(" + [char]34 + "WScript.Shell" + [char]34 + ")"
$line2 = "shell.Run " + [char]34 + "wscript.exe " + [char]34 + " & Chr(34) & " + [char]34 + $target + [char]34 + " & Chr(34), 0, False"
$content = $line1 + [Environment]::NewLine + $line2 + [Environment]::NewLine
[IO.File]::WriteAllText($vbsPath, $content, [Text.Encoding]::ASCII)
Write-Host "Inicio automatico configurado com sucesso em: $vbsPath"
'@

$uninstallStartupPs1 = @'
$startup = [Environment]::GetFolderPath("Startup")
$vbsPath = Join-Path $startup "TransacaoClient.vbs"
if (Test-Path $vbsPath) {
    Remove-Item $vbsPath -Force
    Write-Host "Inicio automatico removido com sucesso."
} else {
    Write-Host "Nao estava configurado."
}
'@

$batInstallStartup = @'
@echo off
title Instalacao do Transacao Client
cd /d "%~dp0"
echo Configurando inicio automatico com o Windows (em segundo plano)...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-startup.ps1"
echo.
echo Iniciando o client agora em segundo plano...
call "%~dp0iniciar-client.bat"
echo.
echo Pronto! O Transacao Client ja esta em execucao em segundo plano.
timeout /t 3 >nul
'@

$batUninstallStartup = @'
@echo off
title Desinstalar Inicio Automatico
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall-startup.ps1"
pause
'@

$commandClient = @'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

if [ -x "$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java" ]; then
    JAVA_CMD="$HOME/.jdk/jdk-21.0.12.1+1/Contents/Home/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_CMD="java"
else
    echo "Java nao encontrado nesta maquina. Instale o Java (JRE 17+)."
    read -p "Pressione Enter para sair..."
    exit 1
fi

"$JAVA_CMD" -Djava.net.preferIPv4Stack=true -jar "$DIR/transacao-client.jar"
'@

[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\resolve-java.ps1"), $resolveJavaPs1, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\install-startup.ps1"), $installStartupPs1, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\uninstall-startup.ps1"), $uninstallStartupPs1, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client.bat"), $batClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-console.bat"), $batClientConsole, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client-oculto.vbs"), $vbsClient, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\instalar-inicializacao-automatica.bat"), $batInstallStartup, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\desinstalar-inicializacao-automatica.bat"), $batUninstallStartup, [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\Client\iniciar-client.command"), $commandClient, [System.Text.Encoding]::ASCII)

# 9. Gera o pacote ZIP de instalacao do Client
$clientZip = Join-Path $here "dist\transacao-client-instalador.zip"
Write-Host "Gerando pacote ZIP de instalacao do Client..."
Compress-Archive -Path "$here\dist\Client\*" -DestinationPath $clientZip -Force

# 10. Gera o ZIP de "extras" (tudo em dist\Client MENOS o jar - certificados
# e os scripts .bat/.vbs/.ps1) que o auto-update push manda para o client
# sobrescrever no proprio diretorio antes de trocar o jar. Sem isso, uma
# atualizacao automatica so trocava o .jar e deixava scripts/certs antigos
# parados na maquina do client (ex: lancador de inicializacao desatualizado,
# nunca corrigido so por um auto-update).
Write-Host "Gerando pacote de extras (certificados/scripts) para o auto-update..."
$extrasStage = Join-Path $here "dist\_extras_stage"
if (Test-Path $extrasStage) {
    Remove-Item -Path $extrasStage -Recurse -Force
}
Copy-Item "dist\Client" $extrasStage -Recurse -Force
Remove-Item (Join-Path $extrasStage "transacao-client.jar") -Force -ErrorAction SilentlyContinue
$extrasZip = Join-Path $here "dist\Server\updates\transacao-client-extras.zip"
Compress-Archive -Path "$extrasStage\*" -DestinationPath $extrasZip -Force
Remove-Item -Path $extrasStage -Recurse -Force

Write-Host "Build concluido com sucesso! Estrutura limpa em dist\"
