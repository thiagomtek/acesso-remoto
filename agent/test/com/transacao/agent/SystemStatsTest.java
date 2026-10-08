package com.transacao.agent;

import java.util.Map;

/** Testes (sem tocar em nada do sistema) do snapshot de uso de CPU/memoria. */
public final class SystemStatsTest {

    private static int failures;

    public static void main(String[] args) {
        Map<String, Object> s = SystemStats.snapshot();
        check("nao e nulo", s != null);
        check("sempre tem o heap do proprio agente", s.containsKey("agentHeapUsedMb"));
        check("heap do agente e um numero >= 0", s.get("agentHeapUsedMb") instanceof Long && (Long) s.get("agentHeapUsedMb") >= 0);
        check("sempre tem o uptime", s.containsKey("uptimeSec"));
        check("uptime e um numero >= 0", s.get("uptimeSec") instanceof Long && (Long) s.get("uptimeSec") >= 0);
        // cpuPct/memUsedMb/memTotalMb sao melhor-esforco (com.sun.management.OperatingSystemMXBean) -
        // nao da pra garantir que toda JVM/plataforma os exponha, so que, quando vierem, fazem sentido.
        if (s.containsKey("cpuPct")) {
            double cpu = ((Number) s.get("cpuPct")).doubleValue();
            check("cpuPct entre 0 e 100", cpu >= 0 && cpu <= 100);
        }
        if (s.containsKey("memTotalMb")) {
            long total = ((Number) s.get("memTotalMb")).longValue();
            long used = ((Number) s.get("memUsedMb")).longValue();
            check("memTotalMb > 0", total > 0);
            check("memUsedMb entre 0 e memTotalMb", used >= 0 && used <= total);
        }
        System.out.println("snapshot() nesta maquina: " + s);
        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
