package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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

    private static final long DEFAULT_INTERVAL_MS = 3 * 60 * 1000L; // 3 minutos

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

    // ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED (0x80000003, como
    // UInt32 explicito: o literal hex vira Int32 negativo no PowerShell e a chamada
    // falhava em silencio). Valido enquanto a thread do PowerShell viver; o laco sai
    // se o app pai morrer. O auxiliar so imprime OK depois de confirmar que o
    // Windows devolveu o estado recem-aplicado; qualquer erro imprime ERR e sai.
    private boolean startHelper() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        Process p = null;
        try {
            String script = "$ProgressPreference='SilentlyContinue';try{"
                    + "Add-Type -MemberDefinition '[DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint f);' -Name K -Namespace W;"
                    + "$f=[uint32]2147483651;"
                    + "[void][W.K]::SetThreadExecutionState($f);"
                    + "if([W.K]::SetThreadExecutionState($f) -ne $f){throw 'estado nao aplicado'}"
                    + "'OK';"
                    + "$p=" + ProcessHandle.current().pid() + ";"
                    + "while(Get-Process -Id $p -ErrorAction Stop){[void][W.K]::SetThreadExecutionState($f);Start-Sleep 30}"
                    + "}catch{'ERR '+$_.Exception.Message;exit 1}";
            String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                    "-OutputFormat", "Text", "-EncodedCommand", encoded)
                    .redirectErrorStream(true)
                    .start();
            final Process proc = p;
            final java.util.concurrent.atomic.AtomicReference<String> first = new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // ignora ruido (ex.: cabecalho CLIXML) ate a confirmacao ou o erro
                        if (first.get() == null && (line.equals("OK") || line.startsWith("ERR"))) {
                            first.set(line);
                            answered.countDown();
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "keepalive-helper-reader");
            reader.setDaemon(true);
            reader.start();
            answered.await(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!"OK".equals(first.get()) || !p.isAlive()) {
                throw new IllegalStateException(first.get() != null ? first.get() : "sem confirmacao do auxiliar");
            }
            helper = p;
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: SetThreadExecutionState confirmado (sem teclas).");
            }
            return true;
        } catch (Exception e) {
            if (p != null) {
                p.destroy();
            }
            helper = null;
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: auxiliar indisponivel, usando mouse (" + e.getMessage() + ").");
            }
            return false;
        }
    }

    @Override
    public void run() {
        if (!startHelper()) {
            pulseMouse();
        }
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
        // Auxiliar caiu (ou nunca subiu): tenta religar; enquanto nao confirmar,
        // o pulso de mouse garante que o PC nao entra em suspensao.
        if (running && startHelper()) {
            return;
        }
        pulseMouse();
    }

    private void pulseMouse() {
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
