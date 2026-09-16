# Compila os fontes do projeto (sem build tool) para a pasta out/
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not (Test-Path "out")) { New-Item -ItemType Directory -Path "out" | Out-Null }

$sources = Get-ChildItem -Path "src" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = Join-Path $env:TEMP "transacao-sources.txt"
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))

javac -d out -encoding UTF-8 "@$sourcesFile"

Write-Host "Compilacao concluida. Classes em: out/"
