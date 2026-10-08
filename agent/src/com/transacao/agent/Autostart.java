package com.transacao.agent;

import com.transacao.common.WindowsStartup;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registra a autoinicializacao SO PARA O USUARIO ATUAL (sem administrador) e sem depender de scripts:
 * no Windows pela chave HKCU\...\Run (aponta direto para o javaw assinado, em vez de .vbs/.bat que o
 * Smart App Control/WDAC pode bloquear); no macOS por um LaunchAgent do usuario; no Linux por um
 * arquivo .desktop em ~/.config/autostart. Idempotente: so escreve se o registro estiver diferente
 * do desejado (ex.: o jar mudou de pasta), entao pode rodar a cada inicializacao e apos cada atualizacao.
 */
final class Autostart {

    static final String VALUE_NAME = "TransacaoClient";
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String MAC_LABEL = "br.com.tththiago.transacao-agent";
    private static final Pattern REG_SZ = Pattern.compile("REG_SZ\\s+(.*?)\\s*$", Pattern.MULTILINE);

    private Autostart() {
    }

    private static String os() {
        return System.getProperty("os.name", "").toLowerCase();
    }

    /** Desligado em testes/instalacoes especiais: TRANSACAO_NO_AUTOSTART=1. */
    private static boolean disabledByEnv() {
        return "1".equals(System.getenv("TRANSACAO_NO_AUTOSTART"));
    }

    /** Garante o registro para o usuario atual. @return true se esta registrado ao final. */
    static boolean ensure(File jar, Consumer<String> log) {
        if (disabledByEnv() || jar == null) {
            return false;
        }
        try {
            String javaHome = System.getProperty("java.home");
            String os = os();
            if (os.contains("win")) {
                File javaw = new File(javaHome, "bin\\javaw.exe");
                return ensureWindows(VALUE_NAME, windowsCommand(javaw.exists() ? javaw.getAbsolutePath() : "javaw", jar.getAbsolutePath()), log);
            }
            String java = new File(javaHome, "bin/java").getAbsolutePath();
            if (os.contains("mac")) {
                return writeIfDifferent(new File(System.getProperty("user.home"), "Library/LaunchAgents/" + MAC_LABEL + ".plist"),
                        macPlist(java, jar.getAbsolutePath()), "LaunchAgent", log);
            }
            return writeIfDifferent(new File(System.getProperty("user.home"), ".config/autostart/transacao-agent.desktop"),
                    linuxDesktop(java, jar.getAbsolutePath()), "autostart", log);
        } catch (Exception e) {
            log.accept("Nao foi possivel registrar a inicializacao automatica: " + e.getMessage());
            return false;
        }
    }

    /** Remove o registro do usuario atual (quando o painel desliga "Iniciar com o sistema"). */
    static void remove(Consumer<String> log) {
        if (disabledByEnv()) {
            return;
        }
        try {
            String os = os();
            if (os.contains("win")) {
                removeWindowsValue(VALUE_NAME);
                WindowsStartup.disable(VALUE_NAME);
            } else if (os.contains("mac")) {
                Files.deleteIfExists(new File(System.getProperty("user.home"), "Library/LaunchAgents/" + MAC_LABEL + ".plist").toPath());
            } else {
                Files.deleteIfExists(new File(System.getProperty("user.home"), ".config/autostart/transacao-agent.desktop").toPath());
            }
            log.accept("Inicializacao automatica removida para este usuario.");
        } catch (Exception e) {
            log.accept("Nao foi possivel remover a inicializacao automatica: " + e.getMessage());
        }
    }

    // ---------- Windows ----------

    static boolean ensureWindows(String valueName, String desired, Consumer<String> log) throws IOException, InterruptedException {
        String current = parseRegValue(run("reg", "query", RUN_KEY, "/v", valueName), null);
        if (!desired.equals(current)) {
            importRunValue(valueName, desired);
            current = parseRegValue(run("reg", "query", RUN_KEY, "/v", valueName), null);
            if (!desired.equals(current)) {
                log.accept("A chave de inicializacao nao ficou como esperado (" + current + ").");
                return false;
            }
            log.accept("Inicializacao automatica registrada para o usuario atual (HKCU\\...\\Run).");
        }
        // migra do atalho antigo (.vbs na pasta Inicializacao): scripts podem ser bloqueados e duplicariam o inicio
        File legacy = WindowsStartup.startupScriptFile(VALUE_NAME);
        if (legacy.isFile() && WindowsStartup.isEnabled(VALUE_NAME)) {
            WindowsStartup.disable(VALUE_NAME);
            log.accept("Atalho antigo de inicializacao (.vbs) removido; o novo registro substitui.");
        }
        return true;
    }

    /**
     * Grava o valor importando um .reg temporario: o Java nao escapa aspas internas ao montar a linha de
     * comando, entao "reg add ... /d \"javaw\" -jar ..." chega quebrado ("sintaxe invalida"). O arquivo .reg
     * tem escapes proprios e nao depende de como o argumento e repassado.
     */
    private static void importRunValue(String valueName, String value) throws IOException, InterruptedException {
        File tmp = File.createTempFile("transacao-run", ".reg");
        try {
            Files.write(tmp.toPath(), regFileContent(valueName, value));
            run("reg", "import", tmp.getAbsolutePath());
        } finally {
            tmp.delete();
        }
    }

    /** Conteudo do .reg (UTF-16LE com BOM, aceito por qualquer Windows e por nomes de usuario com acento). */
    static byte[] regFileContent(String valueName, String value) {
        String text = "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"
                + "\"" + regEscape(valueName) + "\"=\"" + regEscape(value) + "\"\r\n";
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[body.length + 2];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xFE;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    static String regEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Remove um valor da chave Run (usado nos testes e em remove()). */
    static void removeWindowsValue(String valueName) throws IOException, InterruptedException {
        run("reg", "delete", RUN_KEY, "/v", valueName, "/f");
    }

    static String queryWindowsValue(String valueName) throws IOException, InterruptedException {
        return parseRegValue(run("reg", "query", RUN_KEY, "/v", valueName), null);
    }

    /** Linha da chave Run: javaw direto, sem script. */
    static String windowsCommand(String javaw, String jar) {
        return "\"" + javaw + "\" -jar \"" + jar + "\" --background";
    }

    /** Valor REG_SZ na saida de "reg query" (ou null). O nome nao e usado: a consulta ja e por valor. */
    static String parseRegValue(String regOutput, String ignored) {
        Matcher m = REG_SZ.matcher(regOutput == null ? "" : regOutput);
        return m.find() ? m.group(1) : null;
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.ISO_8859_1))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return out.toString();
    }

    // ---------- macOS / Linux ----------

    static String macPlist(String java, String jar) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<plist version=\"1.0\">\n<dict>\n"
                + "  <key>Label</key><string>" + MAC_LABEL + "</string>\n"
                + "  <key>ProgramArguments</key>\n  <array>\n"
                + "    <string>" + xml(java) + "</string>\n    <string>-jar</string>\n    <string>" + xml(jar) + "</string>\n    <string>--background</string>\n"
                + "  </array>\n"
                + "  <key>RunAtLoad</key><true/>\n"
                + "  <key>KeepAlive</key><false/>\n"
                + "</dict>\n</plist>\n";
    }

    static String linuxDesktop(String java, String jar) {
        return "[Desktop Entry]\nType=Application\nName=Transacao Client\n"
                + "Exec=\"" + java + "\" -jar \"" + jar + "\" --background\n"
                + "X-GNOME-Autostart-enabled=true\nNoDisplay=true\n";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static boolean writeIfDifferent(File f, String content, String what, Consumer<String> log) throws IOException {
        if (f.isFile() && Files.readString(f.toPath()).equals(content)) {
            return true;
        }
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
        log.accept("Inicializacao automatica registrada para o usuario atual (" + what + ").");
        return true;
    }
}
