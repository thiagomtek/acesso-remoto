package com.transacao.server;

import java.awt.AWTEvent;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Consumer;

/**
 * Detecta se o usuario esta ausente do PC do Servidor: ausente = nenhum
 * input de mouse/teclado ha pelo menos N minutos (configuravel).
 *
 * Fontes de "atividade", da mais para a menos completa:
 * - Windows: GetLastInputInfo (user32) lido por um PowerShell que fica
 *   rodando em segundo plano e imprime o tempo ocioso do sistema a cada
 *   poucos segundos - enxerga mouse E teclado em qualquer programa, nao so
 *   no Servidor. Java puro nao tem como ler o teclado fora da propria janela.
 * - Qualquer SO (fallback): posicao do cursor (MouseInfo, global) e eventos
 *   de mouse/teclado dentro das janelas do proprio Servidor.
 */
public class UserPresenceMonitor {

    public interface Listener {
        void onAway();

        void onBack();
    }

    private static final long POLL_INTERVAL_MS = 2000;

    private static final String IDLE_SCRIPT =
            "Add-Type @'\n" +
            "using System;\n" +
            "using System.Runtime.InteropServices;\n" +
            "public static class TransacaoIdle {\n" +
            "  [StructLayout(LayoutKind.Sequential)]\n" +
            "  struct LASTINPUTINFO { public uint cbSize; public uint dwTime; }\n" +
            "  [DllImport(\"user32.dll\")]\n" +
            "  static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);\n" +
            "  public static uint IdleMs() {\n" +
            "    LASTINPUTINFO info = new LASTINPUTINFO();\n" +
            "    info.cbSize = (uint) Marshal.SizeOf(info);\n" +
            "    if (!GetLastInputInfo(ref info)) { return 0; }\n" +
            "    return unchecked((uint) Environment.TickCount - info.dwTime);\n" +
            "  }\n" +
            "}\n" +
            "'@\n" +
            "while ($true) { [Console]::Out.WriteLine([TransacaoIdle]::IdleMs()); [Console]::Out.Flush(); Start-Sleep -Seconds 2 }\n";

    private final Listener listener;
    private volatile long awayAfterMs;
    private volatile long lastInputAt = System.currentTimeMillis();
    private volatile boolean away;
    private volatile boolean running;
    private volatile Consumer<String> errorListener;
    private Point lastPointer;
    private Process idleProcess;

    public UserPresenceMonitor(long awayAfterMs, Listener listener) {
        this.awayAfterMs = awayAfterMs;
        this.listener = listener;
    }

    /** Chamado quando a leitura do tempo ocioso do Windows falha, so para logar na UI. */
    public void setErrorListener(Consumer<String> errorListener) {
        this.errorListener = errorListener;
    }

    public void setAwayAfterMs(long awayAfterMs) {
        this.awayAfterMs = awayAfterMs;
    }

    public boolean isAway() {
        return away;
    }

    /** Milissegundos desde o ultimo input de mouse/teclado detectado. */
    public long getIdleMs() {
        return Math.max(0, System.currentTimeMillis() - lastInputAt);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> markActivity(),
                AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK
                        | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK);

        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            startWindowsIdleReader();
        }

        Thread poller = new Thread(this::pollLoop, "user-presence-monitor");
        poller.setDaemon(true);
        poller.start();
    }

    public void stop() {
        running = false;
        Process p = idleProcess;
        if (p != null) {
            p.destroy();
        }
    }

    private void markActivity() {
        lastInputAt = System.currentTimeMillis();
    }

    private void pollLoop() {
        while (running) {
            checkPointer();
            boolean nowAway = getIdleMs() >= awayAfterMs;
            if (nowAway != away) {
                away = nowAway;
                if (nowAway) {
                    listener.onAway();
                } else {
                    listener.onBack();
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void checkPointer() {
        try {
            PointerInfo info = MouseInfo.getPointerInfo();
            if (info == null) {
                return;
            }
            Point p = info.getLocation();
            if (lastPointer != null && !p.equals(lastPointer)) {
                markActivity();
            }
            lastPointer = p;
        } catch (Exception ignored) {
            // Sem mouse/ambiente grafico: fica so com as outras fontes.
        }
    }

    private void startWindowsIdleReader() {
        Thread reader = new Thread(() -> {
            try {
                String encoded = Base64.getEncoder().encodeToString(IDLE_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                idleProcess = process;
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while (running && (line = in.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || !line.chars().allMatch(Character::isDigit)) {
                            continue;
                        }
                        long lastSystemInput = System.currentTimeMillis() - Long.parseLong(line);
                        if (lastSystemInput > lastInputAt) {
                            lastInputAt = lastSystemInput;
                        }
                    }
                }
                if (running) {
                    reportError("Leitura do tempo ocioso do Windows parou; usando so o mouse para detectar ausencia.");
                }
            } catch (Exception e) {
                reportError("Falha ao ler tempo ocioso do Windows (" + e.getMessage()
                        + "); usando so o mouse para detectar ausencia.");
            }
        }, "user-presence-windows-idle");
        reader.setDaemon(true);
        reader.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process p = idleProcess;
            if (p != null) {
                p.destroy();
            }
        }));
    }

    private void reportError(String message) {
        Consumer<String> l = errorListener;
        if (l != null) {
            l.accept(message);
        }
    }
}
