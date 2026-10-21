package com.transacao.agent;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Regressao: falha de download nao pode virar exclusao no ciclo seguinte. */
public final class NextcloudDirectSyncTest {
    public static void main(String[] args) throws Exception {
        testUnicredListing();
        Path tmp = Files.createTempDirectory("nextcloud-sync-test-");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger downloads = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger uploads = new AtomicInteger();
        AtomicBoolean remoteExists = new AtomicBoolean(true);
        AtomicReference<String> remoteText = new AtomicReference<>("safe");
        AtomicLong remoteModified = new AtomicLong(1792497600000L);
        String dav = "/remote.php/dav/files/test";
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            byte[] body;
            int status;
            if ("PROPFIND".equals(method)) {
                status = 207;
                String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        java.time.Instant.ofEpochMilli(remoteModified.get()).atZone(java.time.ZoneOffset.UTC));
                String listing = "<d:multistatus xmlns:d=\"DAV:\">"
                        + "<d:response><d:href>" + dav + "/</d:href><d:propstat><d:prop>"
                        + "<d:resourcetype><d:collection/></d:resourcetype><d:getetag>root</d:getetag>"
                        + "</d:prop></d:propstat></d:response>"
                        + (remoteExists.get() ? "<d:response><d:href>" + dav + "/keep.txt</d:href><d:propstat><d:prop>"
                        + "<d:resourcetype/><d:getcontentlength>" + remoteText.get().length() + "</d:getcontentlength>"
                        + "<d:getlastmodified>" + date + "</d:getlastmodified>"
                        + "</d:prop></d:propstat></d:response>" : "") + "</d:multistatus>";
                body = listing.getBytes(StandardCharsets.UTF_8);
            } else if ("GET".equals(method)) {
                status = downloads.incrementAndGet() == 1 ? 500 : 200;
                body = status == 200 ? remoteText.get().getBytes(StandardCharsets.UTF_8) : new byte[0];
            } else if ("DELETE".equals(method)) {
                deletes.incrementAndGet();
                remoteExists.set(false);
                status = 204;
                body = new byte[0];
            } else if ("PUT".equals(method)) {
                uploads.incrementAndGet();
                remoteText.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                remoteExists.set(true);
                remoteModified.set(Long.parseLong(exchange.getRequestHeaders().getFirst("X-OC-Mtime")) * 1000L);
                status = 201;
                body = new byte[0];
            } else {
                status = 500;
                body = new byte[0];
            }
            exchange.sendResponseHeaders(status, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        NextcloudDirectSync sync = null;
        try {
            File cfgDir = tmp.resolve("config").toFile();
            cfgDir.mkdirs();
            AgentConfig cfg = new AgentConfig("", "", "", "", "", "", "", "", cfgDir);
            sync = new NextcloudDirectSync(cfg, s -> {}, (event, data) -> {}, () -> false,
                    tmp.resolve("recebimentos").toFile(), false);
            set(sync, "enabled", true);
            set(sync, "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/");
            set(sync, "username", "test");
            set(sync, "appPassword", "test");
            set(sync, "authHeader", "Basic " + Base64.getEncoder().encodeToString("test:test".getBytes(StandardCharsets.UTF_8)));
            Method tick = NextcloudDirectSync.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            tick.invoke(sync);
            Path state = cfgDir.toPath().resolve("nextcloud-sync-state.properties");
            Properties partial = new Properties();
            if (Files.exists(state)) {
                try (var input = Files.newInputStream(state)) { partial.load(input); }
            }
            if (partial.containsKey("keep.txt")) throw new AssertionError("Falha de download nao deve marcar o arquivo como sincronizado");
            tick.invoke(sync);
            if (deletes.get() != 0) throw new AssertionError("Arquivo remoto foi excluido apos falha temporaria");
            if (!"safe".equals(Files.readString(tmp.resolve("recebimentos/keep.txt")))) {
                throw new AssertionError("Arquivo remoto nao foi recuperado no segundo ciclo");
            }
            Properties saved = new Properties();
            try (var input = Files.newInputStream(state)) { saved.load(input); }
            String signature = saved.getProperty("keep.txt", "");
            if (!signature.matches("[^|]+\\|[^|]+")) throw new AssertionError("Estado unilateral gravado: " + signature);

            // Outra maquina altera o arquivo enquanto o usuario local o remove.
            Files.delete(tmp.resolve("recebimentos/keep.txt"));
            remoteText.set("edit");
            remoteModified.addAndGet(10_000L);
            set(sync, "forceSyncRequested", true);
            tick.invoke(sync);
            if (deletes.get() != 0 || !"edit".equals(Files.readString(tmp.resolve("recebimentos/keep.txt")))) {
                throw new AssertionError("Edicao remota concorrente foi apagada pela exclusao local");
            }

            // Outra maquina exclui no servidor enquanto ha edicao local ainda nao enviada.
            Files.writeString(tmp.resolve("recebimentos/keep.txt"), "mine");
            remoteExists.set(false);
            set(sync, "forceSyncRequested", true);
            tick.invoke(sync);
            if (uploads.get() < 1 || !remoteExists.get() || !"mine".equals(remoteText.get())) {
                throw new AssertionError("Edicao local concorrente foi apagada pela exclusao remota");
            }
            System.out.println("TODOS OS TESTES PASSARAM");
        } finally {
            if (sync != null) sync.close();
            server.stop(0);
        }
    }

    private static void testUnicredListing() throws Exception {
        String base = "https://cloud.example/remote.php/dav/files/thiago.magalhaes%40foursys.com.br";
        String prefix = "/remote.php/dav/files/thiago.magalhaes@foursys.com.br/UNICRED/";
        String xml = "<d:multistatus xmlns:d=\"DAV:\">"
                + "<d:response><d:href>" + prefix + "</d:href><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:response>"
                + "<d:response><d:href>" + prefix + "AGENTS.md</d:href><d:prop><d:resourcetype/><d:getcontentlength>6975</d:getcontentlength></d:prop></d:response>"
                + "<d:response><d:href>" + prefix + "GEMINI.md</d:href><d:prop><d:resourcetype/><d:getcontentlength>6975</d:getcontentlength></d:prop></d:response>"
                + "</d:multistatus>";
        Method parse = NextcloudDirectSync.class.getDeclaredMethod("parsePropfindXml", String.class, String.class, String.class);
        parse.setAccessible(true);
        java.util.List<?> result = (java.util.List<?>) parse.invoke(null, xml, base, "UNICRED");
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (Object item : result) {
            Field path = item.getClass().getDeclaredField("path");
            path.setAccessible(true);
            paths.add((String) path.get(item));
        }
        if (!paths.contains("UNICRED/AGENTS.md") || !paths.contains("UNICRED/GEMINI.md")) {
            throw new AssertionError("Arquivos na raiz UNICRED nao foram listados: " + paths);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = NextcloudDirectSync.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
