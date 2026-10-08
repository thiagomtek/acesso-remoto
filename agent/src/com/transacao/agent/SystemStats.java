package com.transacao.agent;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uso de CPU/memoria da maquina (sistema todo, nao so o processo do agente), para mostrar no painel.
 * So usa API padrao do JDK (com.sun.management.OperatingSystemMXBean, presente desde o Java 14 em
 * qualquer JVM da Oracle/OpenJDK) - sem shell-out nem biblioteca nova, roda a cada poucos segundos
 * sem custo perceptivel.
 */
final class SystemStats {

    private SystemStats() {
    }

    /** @return mapa pronto pra virar JSON (so com os campos que deram pra ler nesta plataforma/JVM). */
    static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
                double cpu = os.getCpuLoad(); // 0..1 (sistema todo); -1 se ainda nao disponivel
                if (cpu >= 0) {
                    out.put("cpuPct", Math.round(cpu * 1000) / 10.0); // 1 casa decimal
                }
                long totalMem = os.getTotalMemorySize();
                long freeMem = os.getFreeMemorySize();
                if (totalMem > 0) {
                    out.put("memTotalMb", totalMem / (1024 * 1024));
                    out.put("memUsedMb", (totalMem - freeMem) / (1024 * 1024));
                }
            }
        } catch (Throwable ignored) {
            // plataforma/JVM sem essa extensao: segue so com o que o Runtime da (abaixo)
        }
        Runtime rt = Runtime.getRuntime();
        out.put("agentHeapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        out.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        return out;
    }
}
