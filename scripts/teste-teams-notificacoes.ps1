# Script de teste/validacao - NAO faz parte do app ainda, e so pra confirmar
# se a API UserNotificationListener do Windows funciona via PowerShell puro
# nesta maquina, antes de construir a integracao de verdade em cima disso.
#
# O que faz: pede permissao para ler as notificacoes do sistema (o Windows vai
# mostrar um popup de "Permitir que este app acesse suas notificacoes?" - clique
# em Sim) e depois lista as notificacoes de toast atualmente ativas (app de
# origem + texto), sem remover nem modificar nada.
#
# Como testar:
#   1. Deixe uma notificacao do Teams (ou qualquer app) visivel na tela / no
#      Central de Notificacoes do Windows (Win+N) antes de rodar.
#   2. Rode este script: powershell -ExecutionPolicy Bypass -File teste-teams-notificacoes.ps1
#   3. Aprove o popup de permissao se aparecer.
#   4. Veja se a notificacao aparece listada com o nome do app e o texto certo.

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

[Windows.UI.Notifications.Management.UserNotificationListener,Windows.UI.Notifications.Management,ContentType=WindowsRuntime] | Out-Null
[Windows.UI.Notifications.Management.UserNotificationListenerAccessStatus,Windows.UI.Notifications.Management,ContentType=WindowsRuntime] | Out-Null
[Windows.UI.Notifications.NotificationKinds,Windows.UI.Notifications,ContentType=WindowsRuntime] | Out-Null
[Windows.UI.Notifications.KnownNotificationBindings,Windows.UI.Notifications,ContentType=WindowsRuntime] | Out-Null

Write-Output "Pedindo acesso as notificacoes (aprove o popup do Windows se aparecer)..."

$listener = [Windows.UI.Notifications.Management.UserNotificationListener]::Current
$status = Await ($listener.RequestAccessAsync()) ([Windows.UI.Notifications.Management.UserNotificationListenerAccessStatus])

Write-Output "Status de acesso: $status"

if ($status -ne 'Allowed') {
    Write-Output "Acesso nao concedido - va em Configuracoes > Privacidade > Notificacoes e libere manualmente, depois rode de novo."
    exit 1
}

$notifs = Await ($listener.GetNotificationsAsync([Windows.UI.Notifications.NotificationKinds]::Toast)) ([System.Collections.Generic.IReadOnlyList[Windows.UI.Notifications.UserNotification]])

Write-Output ""
Write-Output "Notificacoes ativas encontradas: $($notifs.Count)"
Write-Output "---"

foreach ($n in $notifs) {
    $appName = $n.AppInfo.DisplayInfo.DisplayName
    $binding = $n.Notification.Visual.GetBinding([Windows.UI.Notifications.KnownNotificationBindings]::ToastGeneric)
    $texts = @()
    if ($binding) {
        $texts = $binding.GetTextElements() | ForEach-Object { $_.Text }
    }
    Write-Output "App: $appName"
    Write-Output ("Texto: " + ($texts -join ' | '))
    Write-Output "---"
}
