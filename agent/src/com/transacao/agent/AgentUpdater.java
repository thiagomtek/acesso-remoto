package com.transacao.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Auto-atualizacao do agente, no mesmo estilo do client original (o hub oferece, o agente baixa, troca
 * o proprio .jar por um script externo e reinicia sozinho), com um reforco de seguranca: como o pacote
 * vem de um servico exposto na internet, o agente so aplica se a ASSINATURA Ed25519 do pacote bater com
 * a chave publica embutida aqui. A chave privada fica so com quem publica (agent/publish-update.ps1).
 *
 * A troca so acontece quando nao ha sessao de acesso em andamento.
 */
final class AgentUpdater {

    /** Chave publica Ed25519 (X.509/DER em base64) que valida os pacotes. Gerada por agent/tools/gen-update-key.js. */
    static final String PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAWqYmFVQE8MOgyS/bIxEgKAlondkBKNTgcD3qD318hSc=";

    private static final String MAIN_JAR = "transacao-agent.jar";
    private static final long MAX_BUNDLE_BYTES = 150L * 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final long IDLE_POLL_MS = 30_000;

    /** Escrito pela versao NOVA depois de subir e ficar estavel: sem ele, o script de atualizacao volta a versao anterior. */
    static final String HEALTHY_FLAG = "agent-healthy.flag";
    /** Hash(es) de versoes que nao subiram nesta maquina: nao sao aplicadas de novo. */
    static final String BAD_VERSIONS = "agent-bad-versions.txt";
    /** Criado pelo script ao desfazer uma atualizacao; o agente avisa o painel e apaga. */
    static final String ROLLBACK_PENDING = "agent-rollback-pending.flag";
    /** Quanto o script espera a versao nova ficar saudavel antes de desfazer (env TRANSACAO_UPDATE_HEALTH_TIMEOUT, em s, para testes). */
    private static final int DEFAULT_HEALTH_TIMEOUT_S = 90;

    private final AgentConfig cfg;
    private final String currentJarHash;
    private final File runningJar;
    private final Consumer<String> log;
    private final BooleanSupplier idle;
    private final Runnable beforeExit;
    /** Informa o hub (historico da maquina): update-applying / update-failed. */
    private final BiConsumer<String, Map<String, Object>> report;
    private final PublicKey publicKey;
    private final HubHttp hubHttp;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-updater");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Integer> attempts = new HashMap<>();
    private volatile String inProgress = "";
    /**
     * Uma versao iniciada pelo UpdateSwapper precisa primeiro escrever seu proprio sinal de saude.
     * Sem esta barreira, o hub pode oferecer um segundo pacote durante esses 20 s e o primeiro
     * swapper interpreta a troca encadeada como falha, restaurando uma versao ja saudavel.
     */
    private volatile boolean acceptsOffers;
    private volatile Offer deferredOffer;

    private static final class Offer {
        final String jarSha256;
        final String zipSha256;
        final long size;

        Offer(String jarSha256, String zipSha256, long size) {
            this.jarSha256 = jarSha256;
            this.zipSha256 = zipSha256;
            this.size = size;
        }
    }

    AgentUpdater(AgentConfig cfg, File runningJar, String currentJarHash, BooleanSupplier idle, BooleanSupplier lanConnected,
                 Runnable beforeExit, Consumer<String> log, BiConsumer<String, Map<String, Object>> report) {
        this.report = report;
        this.cfg = cfg;
        this.runningJar = runningJar;
        this.currentJarHash = currentJarHash;
        this.idle = idle;
        this.beforeExit = beforeExit;
        this.log = log;
        this.hubHttp = new HubHttp(cfg, lanConnected);
        PublicKey key = null;
        try {
            key = loadPublicKey(PUBLIC_KEY_B64);
        } catch (Exception e) {
            log.accept("Atualizacao automatica desativada: chave publica invalida (" + e.getMessage() + ").");
        }
        this.publicKey = key;
    }

    /** O hub informou uma versao diferente da nossa. Roda em segundo plano; ignora se ja esta tratando. */
    void onOffer(String jarSha256, String zipSha256, long size) {
        if (!acceptsOffers) {
            // Guarda so a oferta mais recente. Nao baixa nem reinicia antes de a instancia atual
            // ser considerada saudavel pelo swapper que a iniciou.
            deferredOffer = new Offer(jarSha256, zipSha256, size);
            return;
        }
        processOffer(jarSha256, zipSha256, size);
    }

    /** Chamado depois de a instancia atual estabilizar e publicar o sinal de saude. */
    void confirmHealthy() {
        acceptsOffers = true;
        Offer deferred = deferredOffer;
        deferredOffer = null;
        if (deferred != null) {
            processOffer(deferred.jarSha256, deferred.zipSha256, deferred.size);
        }
    }

    private void processOffer(String jarSha256, String zipSha256, long size) {
        if (publicKey == null || runningJar == null || !runningJar.getName().toLowerCase().endsWith(".jar")) {
            return; // rodando fora de um .jar (ex.: IDE) ou sem chave: nada a fazer
        }
        if (jarSha256 == null || zipSha256 == null || jarSha256.equals(currentJarHash) || jarSha256.equals(inProgress)) {
            return;
        }
        if (isBadVersion(runningJar.getParentFile(), jarSha256)) {
            return; // esta versao ja nao subiu nesta maquina: espera uma nova publicacao
        }
        synchronized (attempts) {
            int n = attempts.merge(jarSha256, 1, Integer::sum);
            if (n > MAX_ATTEMPTS) {
                return; // ja falhou varias vezes com esta versao: nao fica em loop
            }
        }
        inProgress = jarSha256;
        exec.execute(() -> {
            try {
                process(jarSha256, zipSha256, size);
            } catch (Exception e) {
                log.accept("Atualizacao automatica falhou: " + e.getMessage());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("to", jarSha256.substring(0, Math.min(8, jarSha256.length())));
                d.put("error", String.valueOf(e.getMessage()));
                report.accept("update-failed", d);
            } finally {
                inProgress = "";
            }
        });
    }

    private void process(String jarSha256, String zipSha256, long size) throws Exception {
        log.accept("Nova versao do agente disponivel. Baixando...");
        String base = hubHttp.base();
        byte[] zip = get(base + "/updates/agent-update.zip");
        if (size > 0 && zip.length != size) {
            throw new IOException("tamanho do pacote nao confere");
        }
        if (!sha256(zip).equals(zipSha256)) {
            throw new IOException("hash do pacote nao confere");
        }
        byte[] sig = Base64.getDecoder().decode(new String(get(base + "/updates/agent-update.zip.sig"), StandardCharsets.US_ASCII).trim());
        if (!verifySignature(zip, sig, publicKey)) {
            throw new IOException("ASSINATURA INVALIDA: pacote recusado");
        }
        Map<String, byte[]> files = readBundle(zip);
        byte[] jar = files.get(MAIN_JAR);
        if (jar == null) {
            throw new IOException("pacote sem " + MAIN_JAR);
        }
        if (!sha256(jar).equals(jarSha256)) {
            throw new IOException("jar do pacote nao confere com o anunciado");
        }
        log.accept("Pacote verificado (hash e assinatura). Aguardando nao haver acesso remoto em andamento...");
        while (!idle.getAsBoolean()) {
            Thread.sleep(IDLE_POLL_MS);
        }
        apply(files, jarSha256);
    }

    // ---------- aplicacao ----------

    private void apply(Map<String, byte[]> files, String newJarHash) throws IOException {
        File dir = runningJar.getParentFile();
        Path root = dir.toPath().toAbsolutePath().normalize();
        // grava tudo como <arquivo>.new ao lado do original; o script externo troca depois que este processo sair
        Map<File, File> swaps = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().equals(MAIN_JAR)) {
                continue;
            }
            addSwap(root, e.getKey(), e.getValue(), swaps);
        }
        File mainTarget = runningJar.getAbsoluteFile(); // o jar principal por ultimo
        File mainNew = new File(mainTarget.getPath() + ".new");
        Files.write(mainNew.toPath(), files.get(MAIN_JAR));
        swaps.put(mainTarget, mainNew);

        int timeout = DEFAULT_HEALTH_TIMEOUT_S;
        try {
            String env = System.getenv("TRANSACAO_UPDATE_HEALTH_TIMEOUT");
            if (env != null && !env.trim().isEmpty()) {
                timeout = Math.max(5, Integer.parseInt(env.trim()));
            }
        } catch (NumberFormatException ignored) {
            // mantem o padrao
        }
        log.accept("Aplicando a nova versao e reiniciando (se ela nao subir, volto para esta)...");
        Map<String, Object> applying = new LinkedHashMap<>();
        applying.put("from", currentJarHash.substring(0, Math.min(8, currentJarHash.length())));
        applying.put("to", newJarHash.substring(0, Math.min(8, newJarHash.length())));
        report.accept("update-applying", applying); // sai antes do reinicio: o hub registra no historico
        launchSwapper(dir, swaps, mainTarget, newJarHash, timeout);
        beforeExit.run();
        System.exit(0);
    }

    /**
     * Roda o UpdateSwapper (Java puro, sem scripts) a partir de uma COPIA do jar: o jar original precisa
     * ficar livre para ser trocado (no Windows um jar em uso fica travado).
     */
    private void launchSwapper(File dir, Map<File, File> swaps, File jar, String newHash, int timeoutS) throws IOException {
        File copy = File.createTempFile("transacao-swap-", ".jar");
        Files.copy(runningJar.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String javaHome = System.getProperty("java.home");
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        File exe = new File(javaHome, win ? "bin\\javaw.exe" : "bin/java");
        String java = exe.exists() ? exe.getAbsolutePath() : (win ? "javaw" : "java");
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(copy.getAbsolutePath());
        cmd.add(UpdateSwapper.class.getName());
        cmd.add(String.valueOf(ProcessHandle.current().pid()));
        cmd.add(dir.getAbsolutePath());
        cmd.add(String.valueOf(timeoutS));
        cmd.add(newHash);
        cmd.add(java);
        cmd.add(jar.getAbsolutePath());
        for (Map.Entry<File, File> e : swaps.entrySet()) {
            cmd.add(e.getKey().getAbsolutePath());
            cmd.add(e.getValue().getAbsolutePath());
        }
        new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void addSwap(Path root, String name, byte[] data, Map<File, File> swaps) throws IOException {
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("entrada fora da pasta de instalacao: " + name);
        }
        Files.createDirectories(target.getParent());
        File targetFile = target.toFile();
        File newFile = new File(targetFile.getPath() + ".new");
        Files.write(newFile.toPath(), data);
        swaps.put(targetFile, newFile);
    }

    static boolean isBadVersion(File dir, String hash) {
        try {
            File f = new File(dir, BAD_VERSIONS);
            return f.isFile() && Files.readAllLines(f.toPath()).stream().anyMatch(l -> l.trim().equals(hash));
        } catch (IOException e) {
            return false;
        }
    }

    private static String sh(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------- utilitarios (testaveis) ----------

    /** wss://host/agent -> https://host (e ws -> http, para desenvolvimento local). */
    static String httpBase(String hubUrl) {
        URI u = URI.create(hubUrl);
        String scheme = "wss".equalsIgnoreCase(u.getScheme()) ? "https" : "http";
        return scheme + "://" + u.getAuthority();
    }

    private byte[] get(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = hubHttp.client().send(hubHttp.request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode() + " em " + url);
        }
        if (r.body().length > MAX_BUNDLE_BYTES) {
            throw new IOException("pacote grande demais");
        }
        return r.body();
    }

    static PublicKey loadPublicKey(String b64) throws Exception {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    static boolean verifySignature(byte[] data, byte[] signature, PublicKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Le o zip em memoria recusando caminhos que escapam da pasta ("../", absolutos) e tamanho excessivo. */
    static Map<String, byte[]> readBundle(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new ByteArrayInputStream(zip))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) {
                    continue;
                }
                if (name.startsWith("/") || name.contains("../") || name.matches("^[A-Za-z]:.*")) {
                    throw new IOException("entrada invalida no pacote: " + name);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                total += bos.size();
                if (total > MAX_BUNDLE_BYTES) {
                    throw new IOException("pacote descompactado grande demais");
                }
                files.put(name, bos.toByteArray());
            }
        }
        return files;
    }
}
