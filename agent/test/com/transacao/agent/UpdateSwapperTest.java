package com.transacao.agent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Testes (em pasta temporaria) da troca de arquivos e da reversao do UpdateSwapper. */
public final class UpdateSwapperTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("swap").toFile();
        File jar = new File(dir, "transacao-agent.jar");
        File lib = new File(dir, "lib/x.jar");
        File libNew = new File(dir, "lib/novo.jar"); // arquivo que a atualizacao cria do zero
        lib.getParentFile().mkdirs();
        write(jar, "AGENTE-V1");
        write(lib, "LIB-V1");
        File jarN = new File(dir, "transacao-agent.jar.new");
        File libN = new File(dir, "lib/x.jar.new");
        File novoN = new File(dir, "lib/novo.jar.new");
        write(jarN, "AGENTE-V2");
        write(libN, "LIB-V2");
        write(novoN, "NOVO-V2");

        List<UpdateSwapper.Pair> pairs = new ArrayList<>();
        pairs.add(new UpdateSwapper.Pair(lib, libN));
        pairs.add(new UpdateSwapper.Pair(libNew, novoN));
        pairs.add(new UpdateSwapper.Pair(jar, jarN)); // o principal por ultimo

        UpdateSwapper.swapAll(pairs, 5);
        check("troca: arquivos novos no lugar", read(jar).equals("AGENTE-V2") && read(lib).equals("LIB-V2") && read(libNew).equals("NOVO-V2"));
        check("troca: versao antiga guardada em .bak", read(UpdateSwapper.bakOf(jar)).equals("AGENTE-V1") && read(UpdateSwapper.bakOf(lib)).equals("LIB-V1"));
        check("troca: arquivo criado do zero nao tem .bak", !UpdateSwapper.bakOf(libNew).exists());
        check("troca: os .new foram consumidos", !jarN.exists() && !libN.exists() && !novoN.exists());

        UpdateSwapper.restoreAll(pairs);
        check("reversao: versao anterior restaurada", read(jar).equals("AGENTE-V1") && read(lib).equals("LIB-V1"));
        check("reversao: o que a atualizacao criou do zero e removido", !libNew.exists());
        check("reversao: nao sobram .bak", !UpdateSwapper.bakOf(jar).exists() && !UpdateSwapper.bakOf(lib).exists());

        // de novo, desta vez com sucesso: limpa os backups
        write(jarN, "AGENTE-V2");
        write(libN, "LIB-V2");
        write(novoN, "NOVO-V2");
        UpdateSwapper.swapAll(pairs, 5);
        UpdateSwapper.cleanBackups(pairs);
        check("sucesso: backups removidos e versao nova mantida", !UpdateSwapper.bakOf(jar).exists() && read(jar).equals("AGENTE-V2"));

        // saude: sinal presente = saudavel; processo que morre sem sinal = nao espera o prazo
        File flag = new File(dir, AgentUpdater.HEALTHY_FLAG);
        flag.delete();
        check("saude: sem sinal e sem processo, vence o prazo curto como nao saudavel", !UpdateSwapper.waitHealthy(flag, null, 1));
        write(flag, "ok");
        check("saude: com o sinal, saudavel na hora", UpdateSwapper.waitHealthy(flag, null, 5));
        flag.delete();
        Process dead = new ProcessBuilder(javaExe(), "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        dead.waitFor();
        long t0 = System.currentTimeMillis();
        boolean healthy = UpdateSwapper.waitHealthy(flag, dead, 30);
        check("saude: processo novo que morreu sem sinal reprova SEM esperar os 30s (" + (System.currentTimeMillis() - t0) + " ms)",
                !healthy && System.currentTimeMillis() - t0 < 5000);

        UpdateSwapper.markRollback(dir, "versao-que-falhou");
        check("falha ao trocar arquivo tambem marca a versao ruim (nao entra em loop)",
                AgentUpdater.isBadVersion(dir, "versao-que-falhou") && new File(dir, AgentUpdater.ROLLBACK_PENDING).isFile());

        // limpeza
        for (File f : new File[] {flag, new File(dir, AgentUpdater.BAD_VERSIONS), new File(dir, AgentUpdater.ROLLBACK_PENDING), jar, lib, libNew, new File(dir, "lib")}) {
            f.delete();
        }
        dir.delete();

        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static String javaExe() {
        return new File(System.getProperty("java.home"), "bin/java" + (System.getProperty("os.name", "").toLowerCase().contains("win") ? ".exe" : "")).getAbsolutePath();
    }

    private static void write(File f, String text) throws Exception {
        Files.writeString(f.toPath(), text, StandardCharsets.UTF_8);
    }

    private static String read(File f) throws Exception {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
