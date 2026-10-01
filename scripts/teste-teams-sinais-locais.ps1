# Script de teste/validacao (parte 2) - o UserNotificationListener nao
# funciona para o "New Teams" (confirmado: ele nao usa o sistema nativo de
# notificacoes do Windows). Este script investiga sinais ALTERNATIVOS que
# talvez indiquem "chegou mensagem nova", sem precisar de nuvem/Graph API:
#
#   1. Titulo da janela do processo do Teams (as vezes mostra contagem de
#      nao lidas entre parenteses, ex: "(3) Chat | Microsoft Teams").
#   2. Acessibilidade (UI Automation) do botao do Teams na barra de tarefas -
#      alguns apps expoem o numero de nao lidas no "Name"/"HelpText" desse
#      botao, mesmo sem passar pelo Central de Notificacoes.
#
# Nao modifica nada, so LE e imprime informacoes. Rode isso com o Teams
# aberto, idealmente com pelo menos 1 mensagem nao lida chegando durante o
# teste (para comparar o "antes" e o "depois").
#
# Como usar:
#   powershell -ExecutionPolicy Bypass -File teste-teams-sinais-locais.ps1

Write-Output "=== 1. Processos do Teams e titulo da janela ==="
$teamsProcs = Get-Process | Where-Object { $_.ProcessName -match 'teams' }
if (-not $teamsProcs) {
    Write-Output "Nenhum processo com 'teams' no nome encontrado. O Teams esta aberto?"
} else {
    $teamsProcs | Select-Object Id, ProcessName, MainWindowTitle | Format-Table -AutoSize | Out-String | Write-Output
}

Write-Output ""
Write-Output "=== 2. Botoes da barra de tarefas (UI Automation) ==="

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement

# A barra de tarefas fica numa janela de classe "Shell_TrayWnd"; os botoes
# individuais ficam em elementos do tipo Button dentro dela.
$trayCondition = New-Object System.Windows.Automation.PropertyCondition(
    [System.Windows.Automation.AutomationElement]::ClassNameProperty, "Shell_TrayWnd")
$tray = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $trayCondition)

if (-not $tray) {
    Write-Output "Nao encontrei a barra de tarefas (Shell_TrayWnd) via UI Automation."
} else {
    $buttonCondition = New-Object System.Windows.Automation.PropertyCondition(
        [System.Windows.Automation.AutomationElement]::ControlTypeProperty,
        [System.Windows.Automation.ControlType]::Button)
    $buttons = $tray.FindAll([System.Windows.Automation.TreeScope]::Descendants, $buttonCondition)

    Write-Output "Total de botoes encontrados na barra de tarefas: $($buttons.Count)"
    Write-Output "Listando os que mencionam 'Teams' (Name/HelpText):"
    Write-Output "---"

    foreach ($btn in $buttons) {
        $name = $btn.Current.Name
        $helpText = $btn.Current.HelpText
        if ($name -match 'Teams' -or $helpText -match 'Teams') {
            Write-Output "Name: $name"
            Write-Output "HelpText: $helpText"
            Write-Output "AutomationId: $($btn.Current.AutomationId)"
            Write-Output "---"
        }
    }
}

Write-Output ""
Write-Output "=== Fim do teste ==="
Write-Output "Se alguma das secoes acima mostrou um numero de nao lidas que MUDA"
Write-Output "quando chega mensagem nova, me avise qual (titulo da janela, ou"
Write-Output "Name/HelpText do botao da barra de tarefas) e o valor exato."
