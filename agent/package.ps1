# Gera o pacote de instalacao do agente: agent\dist\ (pasta pronta) e agent\transacao-agent-instalador.zip.
# O Service Token da Cloudflare e lido de ~\.remote-hub\cf-service-token.env (fora do repositorio) e vai
# gravado em agent.properties DENTRO do pacote - trate o zip como segredo (nao versionar, nao enviar por canal aberto).
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

& .\build.ps1

# ---- agent.properties (URL do hub + Service Token) ----
# Cloudflare desligado: o hub e alcancado pela malha Tailscale (sem Service Token).
$hubUrl = "wss://servidor.tail074692.ts.net:8787/agent"
$lanHubUrl = "wss://servidor.tail074692.ts.net:8787/agent"
# IP LAN do hub, usado so se o DNS falhar (ex.: VPN). Fica em ~\.remote-hub\hub-ip.txt, fora do repo.
$hubIpFile = Join-Path $env:USERPROFILE ".remote-hub\hub-ip.txt"
$hubIp = if (Test-Path $hubIpFile) { (Get-Content $hubIpFile -Raw).Trim() } else { "" }
$id = ""; $secret = ""
$props = "# Configuracao do agente. Gerado por package.ps1.`r`nhub.url=$hubUrl`r`nlan.hubUrl=$lanHubUrl`r`nhub.ip=$hubIp`r`naccess.clientId=$id`r`naccess.clientSecret=$secret`r`n"
[System.IO.File]::WriteAllText((Join-Path $here "dist\agent.properties"), $props, (New-Object System.Text.UTF8Encoding($false)))

# ---- localizador de Java (nunca depende do PATH; mesma logica do client antigo) ----
$resolveJava = @'
param([switch]$Console)
$exeName = if ($Console) { "java.exe" } else { "javaw.exe" }
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\$exeName"
    if (Test-Path $candidate) { Write-Output $candidate; exit 0 }
}
$cmd = Get-Command $exeName -ErrorAction SilentlyContinue
if ($cmd) { Write-Output $cmd.Source; exit 0 }
$roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA "Programs")) | Where-Object { $_ -and (Test-Path $_) }
foreach ($root in $roots) {
    $found = Get-ChildItem -Path $root -Filter $exeName -Recurse -Depth 4 -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { Write-Output $found.FullName; exit 0 }
}
exit 1
'@
$bat = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1"') do set "JAVAW=%%i"
if not defined JAVAW (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
if exist "%~dp0assistente.jar" (
    start "" "%JAVAW%" -jar "%~dp0assistente.jar"
) else (
    start "" "%JAVAW%" -jar "%~dp0transacao-agent.jar"
)
'@
$batConsole = @'
@echo off
cd /d "%~dp0"
for /f "delims=" %%i in ('powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-java.ps1" -Console') do set "JAVA=%%i"
if not defined JAVA (
    echo Java nao encontrado nesta maquina. Instale o Java ^(JRE 17+^) ou defina a variavel JAVA_HOME.
    pause
    exit /b 1
)
if exist "%~dp0assistente.jar" (
    "%JAVA%" -jar "%~dp0assistente.jar"
) else (
    "%JAVA%" -jar "%~dp0transacao-agent.jar"
)
pause
'@
$command = @'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
if command -v java >/dev/null 2>&1; then JAVA_CMD="java"; else
    echo "Java nao encontrado. Instale o Java (JRE 17+)."; read -p "Pressione Enter para sair..."; exit 1
fi
if [ -f "$DIR/assistente.jar" ]; then
    "$JAVA_CMD" -jar "$DIR/assistente.jar"
else
    "$JAVA_CMD" -jar "$DIR/transacao-agent.jar"
fi
'@
function Write-Ascii($name, $content) { [System.IO.File]::WriteAllText((Join-Path $here "dist\$name"), $content.Replace("`r`n","`n").Replace("`n","`r`n"), [System.Text.Encoding]::ASCII) }
Write-Ascii "resolve-java.ps1" $resolveJava
Write-Ascii "iniciar-assistente.bat" $bat
Write-Ascii "iniciar-assistente-console.bat" $batConsole
Write-Ascii "iniciar-agent.bat" $bat
Write-Ascii "iniciar-agent-console.bat" $batConsole
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-assistente.command"), $command.Replace("`r`n","`n"), [System.Text.Encoding]::ASCII)
[System.IO.File]::WriteAllText((Join-Path $here "dist\iniciar-agent.command"), $command.Replace("`r`n","`n"), [System.Text.Encoding]::ASCII)

# ---- zip ----
$zipAssistente = Join-Path $here "assistente-instalador.zip"
if (Test-Path $zipAssistente) { Remove-Item $zipAssistente -Force }
Compress-Archive -Path "dist\*" -DestinationPath $zipAssistente
$mb = [math]::Round((Get-Item $zipAssistente).Length / 1MB, 1)
Write-Host "Pacote pronto: $zipAssistente ($mb MB)"

$zipLegacy = Join-Path $here "transacao-agent-instalador.zip"
Copy-Item $zipAssistente $zipLegacy -Force
Write-Host "Pacote legado sincronizado: $zipLegacy"
