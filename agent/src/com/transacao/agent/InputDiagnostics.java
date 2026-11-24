package com.transacao.agent;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Diagnostico de "o clique chegou mas nada acontece". O {@code java.awt.Robot} nao lanca erro quando
 * o Windows descarta a entrada, entao o agente nao tinha como saber. Este auxiliar consulta, no momento
 * do clique, as duas causas conhecidas:
 * <ul>
 *   <li><b>Desktop seguro</b>: tela bloqueada, sessao RDP desconectada ou prompt de UAC. O desktop de
 *       entrada nao e acessivel e o Windows ignora a entrada do agente.</li>
 *   <li><b>UIPI</b>: a janela em primeiro plano roda como administrador e o agente nao. O Windows
 *       bloqueia a entrada de um processo comum para um processo elevado.</li>
 * </ul>
 * Nao le conteudo de janela, titulo, digitacao nem area de transferencia: so o nome do processo e se ele
 * e elevado. Usa o mesmo PowerShell auxiliar do padrao do {@code InputInjector}; onde o PowerShell e
 * restrito ({@code nativeKeyboardHelper} desligado) fica inativo.
 */
final class InputDiagnostics {

    /** Resultado de uma consulta. {@code foreground}/{@code self}: 1 elevado, 0 nao, 2 acesso negado, -1 desconhecido. */
    static final class Probe {
        final boolean desktopAccessible;
        final int foreground;
        final int self;
        final String process;

        Probe(boolean desktopAccessible, int foreground, int self, String process) {
            this.desktopAccessible = desktopAccessible;
            this.foreground = foreground;
            this.self = self;
            this.process = process;
        }
    }

    private static final String SCRIPT = """
            Add-Type @'
            using System;
            using System.Diagnostics;
            using System.Runtime.InteropServices;
            public static class InputProbe {
              [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
              [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
              [DllImport("user32.dll", SetLastError = true)] static extern IntPtr OpenInputDesktop(uint f, bool i, uint a);
              [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr d);
              [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr OpenProcess(uint a, bool i, uint pid);
              [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
              [DllImport("advapi32.dll", SetLastError = true)] static extern bool OpenProcessToken(IntPtr p, uint a, out IntPtr t);
              [DllImport("advapi32.dll", SetLastError = true)] static extern bool GetTokenInformation(IntPtr t, int c, out uint v, int len, out int ret);
              // 1 elevado, 0 nao elevado, 2 acesso negado (processo mais privilegiado que o agente), -1 desconhecido
              static int Elevated(uint pid) {
                IntPtr p = OpenProcess(0x1000, false, pid);
                if (p == IntPtr.Zero) return Marshal.GetLastWin32Error() == 5 ? 2 : -1;
                try {
                  IntPtr t;
                  if (!OpenProcessToken(p, 8, out t)) return Marshal.GetLastWin32Error() == 5 ? 2 : -1;
                  try {
                    uint v; int r;
                    if (!GetTokenInformation(t, 20, out v, 4, out r)) return -1;
                    return v != 0 ? 1 : 0;
                  } finally { CloseHandle(t); }
                } finally { CloseHandle(p); }
              }
              public static string Probe() {
                IntPtr d = OpenInputDesktop(0, false, 0x100);
                int desk = d == IntPtr.Zero ? 0 : 1;
                if (d != IntPtr.Zero) CloseDesktop(d);
                uint pid = 0; string name = "-";
                IntPtr w = GetForegroundWindow();
                if (w != IntPtr.Zero) {
                  GetWindowThreadProcessId(w, out pid);
                  try { name = Process.GetProcessById((int)pid).ProcessName; } catch { name = "?"; }
                }
                int fg = pid == 0 ? -1 : Elevated(pid);
                int self = Elevated((uint)Process.GetCurrentProcess().Id);
                return desk + ":" + fg + ":" + self + ":" + name.Replace(":", "_");
              }
            }
            '@
            [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
            while ($line = [Console]::In.ReadLine()) {
              if ($line -eq 'Q') { try { [Console]::Out.WriteLine([InputProbe]::Probe()) } catch { [Console]::Out.WriteLine('ERR') } }
            }
            """;

    private static final long RETRY_AFTER_FAILURE_MS = 10 * 60_000L;
    private static final long FIRST_REPLY_TIMEOUT_MS = 12_000L;
    private static final long REPLY_TIMEOUT_MS = 1_500L;

    private Process helper;
    private BufferedWriter helperIn;
    private BufferedReader helperOut;
    private boolean fresh;
    private long retryAfterMs;
    private String lastFailure;

    /** Motivo da ultima falha ao subir o auxiliar (para um unico aviso), ou null. */
    synchronized String takeFailure() {
        String f = lastFailure;
        lastFailure = null;
        return f;
    }

    /** Consulta o estado atual. Nulo se o auxiliar nao esta disponivel. Bloqueia: chamar fora da thread de entrada. */
    synchronized Probe query() {
        if (System.currentTimeMillis() < retryAfterMs) {
            return null;
        }
        try {
            ensureHelper();
            helperIn.write("Q");
            helperIn.newLine();
            helperIn.flush();
            String reply = readLine(fresh ? FIRST_REPLY_TIMEOUT_MS : REPLY_TIMEOUT_MS);
            fresh = false;
            Probe p = parse(reply);
            if (p == null) {
                throw new IllegalStateException("resposta invalida do auxiliar");
            }
            return p;
        } catch (Exception e) {
            lastFailure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            close();
            retryAfterMs = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
            return null;
        }
    }

    synchronized void close() {
        try {
            if (helperIn != null) helperIn.close();
        } catch (Exception ignored) {
        }
        helperIn = null;
        try {
            if (helperOut != null) helperOut.close();
        } catch (Exception ignored) {
        }
        helperOut = null;
        if (helper != null) {
            helper.destroy();
            helper = null;
        }
        fresh = false;
    }

    private void ensureHelper() throws Exception {
        if (helper != null && helper.isAlive()) {
            return;
        }
        close();
        String encoded = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        helper = pb.start();
        fresh = true;
        helperIn = new BufferedWriter(new OutputStreamWriter(helper.getOutputStream(), StandardCharsets.UTF_8));
        helperOut = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
        final Process started = helper;
        Runtime.getRuntime().addShutdownHook(new Thread(started::destroy));
    }

    private String readLine(long timeoutMs) throws Exception {
        long limit = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < limit) {
            if (helperOut.ready()) {
                return helperOut.readLine();
            }
            if (!helper.isAlive()) {
                throw new IllegalStateException("auxiliar encerrou");
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("auxiliar sem resposta em " + timeoutMs + " ms");
    }

    // ---------- interpretacao (pura, testavel sem Windows) ----------

    /** Formato do auxiliar: {@code desktop:foreground:self:processo}. */
    static Probe parse(String line) {
        if (line == null) {
            return null;
        }
        String[] p = line.trim().split(":", 4);
        if (p.length < 4) {
            return null;
        }
        try {
            return new Probe("1".equals(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), cleanName(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Mensagem para o log quando a entrada tem grande chance de estar sendo descartada; nulo quando esta
     * tudo normal. O texto precisa conter "bloquead" para o AgentApp envia-lo ao hub como aviso, e nao pode
     * conter as palavras que o AgentApp trata como sensiveis ou descarta.
     */
    static String explain(Probe p) {
        if (p == null) {
            return null;
        }
        if (!p.desktopAccessible) {
            return "Entrada remota bloqueada: a tela esta bloqueada, a sessao desconectada ou ha um aviso de seguranca (UAC) "
                    + "na frente. O Windows ignora os cliques do agente nesse estado.";
        }
        if (p.self == 0 && p.foreground == 1) {
            return "Entrada remota bloqueada: a janela em primeiro plano (" + p.process + ") roda como administrador e o agente nao. "
                    + "O Windows ignora os cliques nela (UIPI). Rode o agente como administrador ou abra o programa sem elevacao.";
        }
        if (p.self == 0 && p.foreground == 2) {
            return "Entrada remota provavelmente bloqueada: a janela em primeiro plano (" + p.process + ") tem mais privilegio que o agente "
                    + "(acesso negado ao consultar, tipico de programa rodando como administrador). O Windows ignora os cliques nela (UIPI).";
        }
        return null;
    }

    /** Mensagem quando o cursor nao foi para onde o agente mandou; nulo se bateu (tolerancia em pixels). */
    static String explainCursor(int expectedX, int expectedY, int actualX, int actualY) {
        if (Math.abs(expectedX - actualX) <= 8 && Math.abs(expectedY - actualY) <= 8) {
            return null;
        }
        return "Entrada remota bloqueada: o cursor nao foi para onde o agente mandou (esperado " + expectedX + "," + expectedY
                + "; atual " + actualX + "," + actualY + "). O Windows pode estar ignorando a entrada (tela bloqueada ou sessao desconectada).";
    }

    /** Nome de processo so com caracteres simples e curto: vai para o log central. */
    static String cleanName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9._ -]", "_");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s.isEmpty() ? "?" : s;
    }
}
