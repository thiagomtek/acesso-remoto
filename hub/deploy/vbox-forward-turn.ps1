# Adiciona ao NAT da VM os redirecionamentos de porta do coturn.
# Roda com a VM ligada (controlvm natpf1 e persistente). Idempotente: ignora regras ja existentes.
# Uso: .\vbox-forward-turn.ps1 [-Vm "Ubuntu Server"]
param([string]$Vm = "Ubuntu Server")

$vbm = "C:\Program Files\Oracle\VirtualBox\VBoxManage.exe"
$existing = & $vbm showvminfo $Vm --machinereadable | Select-String '^Forwarding\(\d+\)="([^,]+),'
$names = $existing | ForEach-Object { $_.Matches[0].Groups[1].Value }

function Add-Rule($name, $proto, $port) {
    if ($names -contains $name) { return }
    & $vbm controlvm $Vm natpf1 "$name,$proto,,$port,,$port"
    if ($LASTEXITCODE -ne 0) { Write-Warning "falhou: $name" }
}

Add-Rule "turn_udp" "udp" 3478
Add-Rule "turn_tcp" "tcp" 3478
foreach ($p in 49152..49200) { Add-Rule "turn_relay_$p" "udp" $p }

& $vbm showvminfo $Vm --machinereadable | Select-String '^Forwarding\(\d+\)="turn_' | Measure-Object | ForEach-Object { "$($_.Count) regras turn_* ativas" }
Write-Host "Lembrete: o Firewall do Windows (host) precisa permitir essas portas de entrada (UDP 3478, TCP 3478, UDP 49152-49200)."
