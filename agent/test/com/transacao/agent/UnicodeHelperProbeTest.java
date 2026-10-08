package com.transacao.agent;

import com.transacao.common.remote.InputInjector;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Testa so compilacao e protocolo do helper; jamais chama SendInput nem toca a tela. */
public final class UnicodeHelperProbeTest {
    public static void main(String[] args) throws Exception {
        Field field = InputInjector.class.getDeclaredField("UNICODE_HELPER_SCRIPT");
        field.setAccessible(true);
        String script = (String) field.get(null);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).start();
        try {
            try (OutputStreamWriter input = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
                input.write("PING\nSIZE\n");
                input.flush();
            }
            boolean ended = p.waitFor(10, TimeUnit.SECONDS);
            if (!ended) throw new AssertionError("helper nao encerrou depois do EOF");
            String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String expectedSize = System.getProperty("sun.arch.data.model", "64").equals("32") ? "28" : "40";
            if (!("PONG\n" + expectedSize).equals(stdout.replace("\r", "")) || p.exitValue() != 0) {
                throw new AssertionError("helper PING falhou: exit=" + p.exitValue() + " out=" + stdout + " err=" + stderr);
            }
            System.out.println("Helper compilou e respondeu PING sem injetar nenhuma tecla.");
        } finally {
            p.destroyForcibly();
        }
    }
}
