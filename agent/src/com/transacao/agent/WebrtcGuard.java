package com.transacao.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evita tentar carregar a DLL do WebRTC onde o Windows vai bloquea-la (e mostrar o aviso "Parte deste
 * aplicativo foi bloqueado" a cada reinicio): detecta o Smart App Control no modo de bloqueio (leitura
 * de registro, sem admin) e lembra de um bloqueio anterior para esta versao do agente.
 */
final class WebrtcGuard {

    private static final String MARKER = "webrtc-blocked.txt";
    private static final Pattern DWORD = Pattern.compile("REG_DWORD\\s+0x([0-9a-fA-F]+)");

    private WebrtcGuard() {
    }

    /** true se o Smart App Control esta ligado em modo de bloqueio (VerifiedAndReputablePolicyState = 1). */
    static boolean smartAppControlEnforced() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\CI\\Policy",
                    "/v", "VerifiedAndReputablePolicyState").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.ISO_8859_1))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            return parseState(out.toString()) == 1;
        } catch (Exception e) {
            return false; // sem como saber: tenta o WebRTC normalmente
        }
    }

    /** Valor numerico do REG_DWORD na saida do "reg query", ou -1 se nao houver. */
    static int parseState(String regOutput) {
        Matcher m = DWORD.matcher(regOutput == null ? "" : regOutput);
        return m.find() ? Integer.parseInt(m.group(1), 16) : -1;
    }

    /** O WebRTC ja foi bloqueado nesta maquina para ESTA versao do agente (uma versao nova tenta de novo). */
    static boolean blockedBefore(File dir, String jarHash) {
        try {
            File f = new File(dir, MARKER);
            return !jarHash.isEmpty() && f.isFile() && jarHash.equals(Files.readString(f.toPath()).trim());
        } catch (IOException e) {
            return false;
        }
    }

    static void markBlocked(File dir, String jarHash) {
        try {
            Files.writeString(new File(dir, MARKER).toPath(), jarHash);
        } catch (IOException ignored) {
            // sem o marcador so perde a otimizacao
        }
    }
}
