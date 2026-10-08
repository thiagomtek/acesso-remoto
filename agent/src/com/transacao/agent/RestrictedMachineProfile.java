package com.transacao.agent;

import java.util.Locale;

/**
 * Perfil de execucao para maquinas restritas por politicas corporativas e antivirus severos.
 * Na maquina alvo (os2h-alp-no0344):
 * - Nao usa recursos que acionam o antivirus (Autostart/Run keys no registro, PowerShell em segundo plano,
 *   compilacao em tempo de execucao via Add-Type, chamadas sc/bcdedit/certutil, scripts e WebRTC nativo).
 * - A conexao de rede e feita exclusivamente para o IP interno do servidor (192.168.16.253),
 *   sem rotas de nuvem e sem consultas DNS externas para o host do hub.
 */
public final class RestrictedMachineProfile {

    public static final String TARGET_HOSTNAME = "os2h-alp-no0344";
    public static final String SERVER_INTERNAL_IP = "192.168.16.253";

    private RestrictedMachineProfile() {
    }

    /**
     * Identifica se a maquina informada ou a maquina local corresponde a maquina restrita os2h-alp-no0344.
     */
    public static boolean isRestrictedMachine(String hostname) {
        if ("1".equals(System.getProperty("transacao.restricted.machine"))
                || "1".equals(System.getenv("TRANSACAO_RESTRICTED_MACHINE"))) {
            return true;
        }
        if (matches(hostname)) {
            return true;
        }
        if (matches(System.getenv("COMPUTERNAME"))) {
            return true;
        }
        if (matches(System.getenv("HOSTNAME"))) {
            return true;
        }
        return false;
    }

    public static boolean isRestrictedMachine() {
        return isRestrictedMachine(currentHostname());
    }

    public static String currentHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("COMPUTERNAME");
            if (env != null && !env.isBlank()) {
                return env.trim();
            }
            String hostEnv = System.getenv("HOSTNAME");
            if (hostEnv != null && !hostEnv.isBlank()) {
                return hostEnv.trim();
            }
            return "client";
        }
    }

    private static boolean matches(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String clean = value.trim().toLowerCase(Locale.ROOT);
        int dot = clean.indexOf('.');
        if (dot > 0) {
            clean = clean.substring(0, dot);
        }
        return TARGET_HOSTNAME.equals(clean);
    }
}
