package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

/** Transferencia autenticada de arquivos pelo hub. Conteudo e nomes nunca vao para log. */
final class FileTransfer {
    private FileTransfer() { }

    static void downloadToReceipts(AgentConfig cfg, String transferId, String name, long expectedSize, Consumer<String> log,
                                  Consumer<Boolean> completion) {
        try {
            HttpRequest request = request(cfg, urlBase(cfg) + "/transfers/" + transferId).GET().build();
            HttpResponse<java.io.InputStream> r = httpClient(cfg)
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
            File receipts = receiptsDirectory(System.getenv("USERPROFILE"), System.getProperty("user.home"));
            if (!receipts.isDirectory() && !receipts.mkdirs()) throw new IOException("Pasta de recebimentos indisponivel");
            File target = unique(receipts, safeName(name));
            try (java.io.InputStream in = r.body()) {
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (expectedSize >= 0 && target.length() != expectedSize) {
                Files.deleteIfExists(target.toPath());
                throw new IOException("tamanho nao confere");
            }
            log.accept("Transferencia recebida na pasta de recebimentos do usuario.");
            completion.accept(true);
        } catch (Exception e) {
            log.accept("Falha ao receber arquivo: " + e.getClass().getSimpleName());
            completion.accept(false);
        }
    }

    private static HttpClient httpClient(AgentConfig cfg) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .proxy(cfg.restricted ? HubConnection.LAN_DIRECT_PROXY : java.net.ProxySelector.getDefault())
                .build();
    }

    private static String urlBase(AgentConfig cfg) {
        return AgentUpdater.httpBase(cfg.restricted && cfg.lanHubUrl != null && !cfg.lanHubUrl.isBlank() ? cfg.lanHubUrl : cfg.hubUrl);
    }

    static HttpRequest.Builder request(AgentConfig cfg, String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15))
                .header("x-client-id", cfg.clientId).header("authorization", "Bearer " + cfg.clientSecret);
        if (!cfg.restricted && !cfg.accessClientId.isEmpty()) b.header("CF-Access-Client-Id", cfg.accessClientId).header("CF-Access-Client-Secret", cfg.accessClientSecret);
        return b;
    }

    /** Destino fixo e previsivel: <pasta do usuario>/recebimentos. */
    static File receiptsDirectory(String userProfile, String userHome) {
        String home = userProfile == null || userProfile.isBlank() ? userHome : userProfile;
        return new File(home, "recebimentos");
    }
    private static String safeName(String value) {
        String s = String.valueOf(value == null ? "arquivo" : value).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return s.isEmpty() || s.equals(".") || s.equals("..") ? "arquivo" : s.substring(0, Math.min(180, s.length()));
    }
    private static File unique(File dir, String name) {
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.'); String base = dot > 0 ? name.substring(0, dot) : name; String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 1; f.exists(); n++) f = new File(dir, base + " (" + n + ")" + ext);
        return f;
    }
}
