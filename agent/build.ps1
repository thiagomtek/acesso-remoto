# Compila e empacota o agente (client novo) em agent\dist\.
# Reaproveita as classes de ..\src (input, anti-suspensao, Teams, inicio com o Windows) via -sourcepath,
# entao o build do sistema antigo (compile.ps1 / build-jar.ps1) continua independente.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

$webrtcVersion = "0.19.0"
$libs = @("webrtc-java-$webrtcVersion.jar",
          "webrtc-java-$webrtcVersion-windows-x86_64.jar",
          "webrtc-java-$webrtcVersion-windows-aarch64.jar",
          "webrtc-java-$webrtcVersion-macos-x86_64.jar",
          "webrtc-java-$webrtcVersion-macos-aarch64.jar")

New-Item -ItemType Directory -Force lib, out, dist\lib | Out-Null
foreach ($l in $libs) {
    if (-not (Test-Path "lib\$l")) {
        Write-Host "Baixando $l ..."
        Invoke-WebRequest "https://repo1.maven.org/maven2/dev/onvoid/webrtc/webrtc-java/$webrtcVersion/$l" -OutFile "lib\$l"
    }
}

Remove-Item out\* -Recurse -Force -ErrorAction SilentlyContinue
$sources = Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesFile = Join-Path $env:TEMP "transacao-agent-sources.txt"
[System.IO.File]::WriteAllLines($sourcesFile, $sources, (New-Object System.Text.UTF8Encoding($false)))
javac -d out -encoding UTF-8 -cp "lib\*" -sourcepath ..\src "@$sourcesFile"
if ($LASTEXITCODE -ne 0) { throw "Compilacao falhou (javac saiu com codigo $LASTEXITCODE)." }

$classPath = ($libs | ForEach-Object { "lib/$_" }) -join " "
$manifest = Join-Path $env:TEMP "transacao-agent-manifest.txt"
[System.IO.File]::WriteAllText($manifest, "Main-Class: com.transacao.agent.AgentApp`r`nClass-Path: $classPath`r`n", (New-Object System.Text.UTF8Encoding($false)))
Remove-Item dist\* -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force dist\lib | Out-Null
Copy-Item -Recurse -Force src\META-INF out\META-INF
jar --create --file dist\transacao-agent.jar --manifest $manifest -C out .
if ($LASTEXITCODE -ne 0) { throw "jar falhou." }
foreach ($l in $libs) { Copy-Item "lib\$l" "dist\lib\" -Force }
Write-Host "Pronto: agent\dist\transacao-agent.jar (+ dist\lib)"
