package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Troca de versao do agente SEM scripts (.bat/.vbs/.sh, que o Smart App Control/WDAC podem bloquear): um
 * pequeno processo Java, executado a partir de uma COPIA do jar (o original fica livre para ser trocado).
 *
 * Fluxo: espera o agente sair; guarda cada arquivo antigo como .bak e coloca o .new no lugar; sobe a versao
 * nova; espera o sinal de saude (agent-healthy.flag, escrito pela nova depois de ~20s estavel). Se a nova
 * morrer ou nao der o sinal no prazo, encerra-a, restaura os .bak, sobe a versao anterior, marca a nova
 * como ruim e deixa um aviso para o agente informar o painel.
 *
 * Argumentos: pid dir timeoutS novoHash java mainJar [alvo novo]...
 */
public final class UpdateSwapper {

    static final class Pair {
        final File target;
        final File fresh;

        Pair(File target, File fresh) {
            this.target = target;
            this.fresh = fresh;
        }
    }

    private static File logFile;

    public static void main(String[] a) throws Exception {
        if (a.length < 6) {
            System.err.println("uso: UpdateSwapper pid dir timeoutS novoHash java mainJar [alvo novo]...");
            System.exit(2);
        }
        long pid = Long.parseLong(a[0]);
        File dir = new File(a[1]);
        int timeoutS = Integer.parseInt(a[2]);
        String newHash = a[3];
        String java = a[4];
        File mainJar = new File(a[5]);
        List<Pair> pairs = new ArrayList<>();
        for (int i = 6; i + 1 < a.length; i += 2) {
            pairs.add(new Pair(new File(a[i]), new File(a[i + 1])));
        }
        logFile = new File(dir, "agent-update.log");
        log("Troca iniciada para a versao " + shortHash(newHash) + " (" + pairs.size() + " arquivo(s)).");

        waitForExit(pid, 40);
        Thread.sleep(1000);

        File flag = new File(dir, AgentUpdater.HEALTHY_FLAG);
        try {
            swapAll(pairs, 60);
        } catch (IOException e) {
            log("Falha ao trocar os arquivos: " + e.getMessage() + ". Restaurando a versao anterior.");
            restoreAll(pairs);
            markRollback(dir, newHash);
            startAgent(java, mainJar, dir);
            System.exit(1);
        }
        flag.delete();
        Process fresh = startAgent(java, mainJar, dir);
        log("Versao nova iniciada. Aguardando o sinal de saude (ate " + timeoutS + "s).");

        if (waitHealthy(flag, fresh, timeoutS)) {
            cleanBackups(pairs);
            log("Versao " + shortHash(newHash) + " saudavel. Backups removidos.");
            return;
        }

        log("A versao " + shortHash(newHash) + " NAO subiu. Voltando para a anterior.");
        stop(fresh);
        Thread.sleep(2000);
        restoreAll(pairs);
        markRollback(dir, newHash);
        startAgent(java, mainJar, dir);
        log("Versao anterior reiniciada.");
    }

    // ---------- passos (package-private para teste) ----------

    /** Guarda cada alvo como .bak e coloca o novo no lugar; tenta de novo enquanto o Windows mantiver o arquivo preso. */
    static void swapAll(List<Pair> pairs, int retrySeconds) throws IOException, InterruptedException {
        for (Pair p : pairs) {
            File bak = bakOf(p.target);
            long until = System.currentTimeMillis() + retrySeconds * 1000L;
            while (true) {
                try {
                    Files.deleteIfExists(bak.toPath());
                    if (p.target.exists()) {
                        Files.move(p.target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    break;
                } catch (IOException e) {
                    if (System.currentTimeMillis() > until) {
                        throw e;
                    }
                    Thread.sleep(1000);
                }
            }
            Files.move(p.fresh.toPath(), p.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Desfaz: onde ha .bak, devolve; o que a atualizacao criou do zero (sem .bak) e removido. */
    static void restoreAll(List<Pair> pairs) {
        for (int i = pairs.size() - 1; i >= 0; i--) {
            Pair p = pairs.get(i);
            File bak = bakOf(p.target);
            try {
                if (bak.exists()) {
                    Files.move(bak.toPath(), p.target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(p.target.toPath());
                }
                Files.deleteIfExists(p.fresh.toPath());
            } catch (IOException e) {
                log("Nao foi possivel restaurar " + p.target.getName() + ": " + e.getMessage());
            }
        }
    }

    static void cleanBackups(List<Pair> pairs) {
        for (Pair p : pairs) {
            bakOf(p.target).delete();
        }
    }

    static File bakOf(File target) {
        return new File(target.getPath() + ".bak");
    }

    /** Saudavel = o sinal apareceu. Se o processo novo morrer antes de dar o sinal, nao ha por que esperar o prazo. */
    static boolean waitHealthy(File flag, Process fresh, int timeoutS) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutS * 1000L;
        while (System.currentTimeMillis() < until) {
            if (flag.exists()) {
                return true;
            }
            if (fresh != null && !fresh.isAlive() && !flag.exists()) {
                log("O processo da versao nova terminou (codigo " + fresh.exitValue() + ") sem dar o sinal de saude.");
                return false;
            }
            Thread.sleep(500);
        }
        return flag.exists();
    }

    /** Marca qualquer falha de instalacao, inclusive arquivo bloqueado por um servico, para nao entrar em loop. */
    static void markRollback(File dir, String newHash) throws IOException {
        append(new File(dir, AgentUpdater.BAD_VERSIONS), newHash + "\r\n");
        Files.writeString(new File(dir, AgentUpdater.ROLLBACK_PENDING).toPath(), newHash);
    }

    private static Process startAgent(String java, File mainJar, File dir) throws IOException {
        return new ProcessBuilder(java, "-jar", mainJar.getAbsolutePath(), "--background")
                .directory(dir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void stop(Process p) {
        if (p == null) {
            return;
        }
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }

    private static void waitForExit(long pid, int seconds) {
        Optional<ProcessHandle> h = ProcessHandle.of(pid);
        if (h.isPresent() && h.get().isAlive()) {
            try {
                h.get().onExit().get(seconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                log("O agente nao terminou em " + seconds + "s; seguindo mesmo assim.");
            }
        }
    }

    private static void append(File f, String text) throws IOException {
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void log(String msg) {
        if (logFile == null) {
            return;
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            w.println("[" + LocalDateTime.now().withNano(0) + "] " + msg);
        } catch (IOException ignored) {
            // sem log, so perde o diagnostico
        }
    }

    private static String shortHash(String h) {
        return h.length() > 8 ? h.substring(0, 8) : h;
    }
}
