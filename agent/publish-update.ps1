# Publica uma nova versao do agente para atualizar sozinho todas as maquinas.
#  - compila (a menos que -SkipBuild), monta agent-update.zip (jar + opcionalmente lib\), calcula os hashes,
#    ASSINA o zip com a chave privada (~\.remote-hub\update-signing.key) e grava em -Target.
#  - O hub (container) le esse diretorio em /data/updates; os agentes conectados recebem a oferta em ate 1 minuto,
#    baixam, conferem hash e assinatura, e trocam o proprio jar quando nao ha acesso remoto em andamento.
# Uso:  .\publish-update.ps1                 (padrao: jar principal)
#       .\publish-update.ps1 -IncludeLibs    (so quando as bibliotecas nativas mudarem)
param(
    [string]$Target = "D:\Servidores\Servicos\remote-hub\data\updates",
    [switch]$IncludeLibs,
    [switch]$SkipBuild,
    [string]$JarPath = ""   # jar a publicar (padrao: dist\transacao-agent.jar)
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $here

if (-not $SkipBuild) { & .\build.ps1 }
if (-not $JarPath) { $JarPath = Join-Path $here "dist\transacao-agent.jar" }
if (-not (Test-Path $JarPath)) { throw "$JarPath nao existe. Rode build.ps1." }

$stage = Join-Path $env:TEMP ("agent-update-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force $stage | Out-Null
try {
    Copy-Item $JarPath (Join-Path $stage "transacao-agent.jar")
    Copy-Item $JarPath (Join-Path $stage "assistente.jar")
    if ($IncludeLibs) { Copy-Item "dist\lib" (Join-Path $stage "lib") -Recurse }

    $zip = Join-Path $env:TEMP ("agent-update-" + [guid]::NewGuid().ToString("N") + ".zip")
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip -Force

    $jarHash = (Get-FileHash $JarPath -Algorithm SHA256).Hash.ToLower()
    $zipHash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    $size = (Get-Item $zip).Length
    $sig = & node (Join-Path $here "tools\sign.js") $zip
    if ($LASTEXITCODE -ne 0 -or -not $sig) { throw "Falha ao assinar o pacote." }

    $version = (Get-Date).ToString("yyyyMMdd-HHmmss")
    $meta = @{ version = $version; jarSha256 = $jarHash; zipSha256 = $zipHash; size = $size; createdAt = (Get-Date).ToString("o") } | ConvertTo-Json
    New-Item -ItemType Directory -Force $Target | Out-Null
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    # zip e assinatura primeiro; o meta.json por ultimo (e ele que "libera" a versao para o hub)
    Copy-Item $zip (Join-Path $Target "agent-update.zip") -Force
    [System.IO.File]::WriteAllText((Join-Path $Target "agent-update.zip.sig"), $sig, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $Target "meta.json"), $meta, $utf8)
    Write-Host "Publicado: versao $version | jar $($jarHash.Substring(0,12)) | $([math]::Round($size/1MB,2)) MB | libs=$($IncludeLibs.IsPresent)"
    Write-Host "Destino: $Target"
} finally {
    Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
}
