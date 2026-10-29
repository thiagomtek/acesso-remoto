package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Servico em segundo plano que evita a suspensao do computador e o desligamento
 * do monitor por inatividade, sem gerar nenhuma tecla (CapsLock atrapalhava a
 * digitacao). No Windows usa SetThreadExecutionState, mantido por um processo
 * PowerShell auxiliar que encerra junto com o app. Se o auxiliar nao subir ou
 * cair (outro SO, politica de seguranca), cai no fallback: a cada intervalo, um
 * leve movimento do mouse (1px e volta).
 */
public class ScreenKeepAlive implements Runnable {

    private static final long DEFAULT_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutos

    private final long intervalMs;
    private final Robot robot;
    private final Consumer<String> logConsumer;
    private volatile boolean running = true;
    private volatile Process helper;

    public ScreenKeepAlive(Consumer<String> logConsumer) {
        this(DEFAULT_INTERVAL_MS, logConsumer);
    }

    public ScreenKeepAlive(long intervalMs, Consumer<String> logConsumer) {
        this.intervalMs = intervalMs;
        this.logConsumer = logConsumer;
        Robot r = null;
        try {
            r = new Robot();
        } catch (AWTException ignored) {
        }
        this.robot = r;
    }

    public void stop() {
        running = false;
        stopHelper();
    }

    private boolean helperAlive() {
        Process p = helper;
        return p != null && p.isAlive();
    }

    private void stopHelper() {
        Process p = helper;
        helper = null;
        if (p != null) {
            p.destroy();
        }
    }

    // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED, valido enquanto a
    // thread do PowerShell viver; o laco sai (e o estado some) se o app pai morrer.
    private void startHelper() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        try {
            String script = "Add-Type -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);' -Name K -Namespace W;"
                    + "$p=" + ProcessHandle.current().pid() + ";"
                    + "while(Get-Process -Id $p -ErrorAction SilentlyContinue){[W.K]::SetThreadExecutionState(0x80000003)|Out-Null;Start-Sleep 30}";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            helper = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: SetThreadExecutionState ativo (sem teclas).");
            }
        } catch (Exception e) {
            helper = null;
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: auxiliar indisponivel, usando mouse (" + e.getMessage() + ").");
            }
        }
    }

    @Override
    public void run() {
        startHelper();
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!running) {
                break;
            }

            triggerKeepAlive();
        }
    }

    public void triggerKeepAlive() {
        if (helperAlive()) {
            return;
        }
        if (robot == null) {
            return;
        }
        try {
            // Fallback: mouse 1px e volta conta como atividade real para o Windows.
            Point p = MouseInfo.getPointerInfo().getLocation();
            robot.mouseMove(p.x + 1, p.y);
            robot.mouseMove(p.x, p.y);

            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: sinal de mouse enviado para manter computador ativo.");
            }
        } catch (Exception e) {
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive falhou ao emitir sinal: " + e.getMessage());
            }
        }
    }
}
