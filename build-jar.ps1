# Empacota as classes compiladas em out/ como jars executaveis (server e client).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Test-Path "out\com\transacao\common")) {
    Write-Host "Compilando primeiro..."
    & .\compile.ps1
}

if (-not (Test-Path "dist")) { New-Item -ItemType Directory -Path "dist" | Out-Null }

Push-Location out
jar --create --file ..\dist\transacao-server.jar --main-class com.transacao.server.ServerApp com\transacao\common com\transacao\server
jar --create --file ..\dist\transacao-client.jar --main-class com.transacao.client.ClientApp com\transacao\common com\transacao\client
Pop-Location

Write-Host "Jars gerados em dist\transacao-server.jar e dist\transacao-client.jar"
