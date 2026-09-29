package com.transacao.common.remote;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Fica de olho no icone do Microsoft Teams na area de notificacao (bandeja)
 * via UI Automation do Windows, para avisar quando chega atividade nova (ex:
 * mensagem) - sem ler o conteudo da notificacao. O "New Teams" nao usa o
 * sistema nativo de notificacoes do Windows (UserNotificationListener nao
 * enxerga nada dele), mas o texto acessivel do icone na bandeja muda quando
 * ha algo pendente (confirmado: "Microsoft Teams" vira algo como "Microsoft
 * Teams Microsoft Teams | Nova atividade" - o texto exato varia com o idioma
 * do Windows).
 *
 * Para funcionar em qualquer idioma, este watcher NAO procura por um texto
 * especifico: guarda o primeiro valor lido como "estado parado" (baseline) e
 * dispara o aviso sempre que o valor MUDAR, rearmando quando ele voltar ao
 * baseline (ou seja, so avisa uma vez por "onda" de atividade, nao uma vez
 * por mensagem).
 *
 * Limitacoes conhecidas:
 * - So funciona se o icone do Teams estiver visivel na bandeja (nao escondido
 *   atras da seta "mostrar icones ocultos" do Windows).
 * - Se ja houver atividade pendente no momento em que o watcher inicia, esse
 *   estado inicial vira o baseline (assumido como "normal") e nao gera aviso -
 *   so mudancas subsequentes disparam.
 */
public class TeamsActivityWatcher implements Runnable {

    private static final long POLL_INTERVAL_MS = 8000;

    private static final String POWERSHELL_SCRIPT =
            "Add-Type -AssemblyName UIAutomationClient; " +
            "Add-Type -AssemblyName UIAutomationTypes; " +
            "$root = [System.Windows.Automation.AutomationElement]::RootElement; " +
            "$trayCond = New-Object System.Windows.Automation.PropertyCondition(" +
            "[System.Windows.Automation.AutomationElement]::ClassNameProperty, 'Shell_TrayWnd'); " +
            "$tray = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $trayCond); " +
            "if ($tray) { " +
            "  $idCond = New-Object System.Windows.Automation.PropertyCondition(" +
            "  [System.Windows.Automation.AutomationElement]::AutomationIdProperty, 'NotifyItemIcon'); " +
            "  $icons = $tray.FindAll([System.Windows.Automation.TreeScope]::Descendants, $idCond); " +
            "  foreach ($icon in $icons) { if ($icon.Current.Name -match 'Teams') { Write-Output $icon.Current.Name } } " +
            "}";

    /** true = atividade nova detectada, false = voltou ao normal. */
    private final Consumer<Boolean> listener;
    private volatile boolean running = true;
    private volatile java.util.function.Consumer<String> errorListener;

    private String baseline;
    private boolean armed = true;

    public TeamsActivityWatcher(Consumer<Boolean> listener) {
        this.listener = listener;
    }

    /** Chamado quando a leitura do sinal falha, so para logar na UI. */
    public void setErrorListener(java.util.function.Consumer<String> errorListener) {
        this.errorListener = errorListener;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            String current = readTeamsTrayIconName();
            if (current != null) {
                handleReading(current);
            }

            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void handleReading(String current) {
        if (baseline == null) {
            baseline = current;
            return;
        }
        if (!current.equals(baseline)) {
            if (armed) {
                armed = false;
                if (listener != null) {
                    listener.accept(true);
                }
            }
        } else if (!armed) {
            armed = true;
            if (listener != null) {
                listener.accept(false);
            }
        }
    }

    private String readTeamsTrayIconName() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", POWERSHELL_SCRIPT);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String result = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        result = line;
                    }
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            if (errorListener != null) {
                errorListener.accept("Falha ao checar atividade do Teams: " + e.getMessage());
            }
            return null;
        }
    }
}
