package com.transacao.agent;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.function.BooleanSupplier;

/** HTTP autenticado que acompanha a rota atualmente usada pelo WebSocket do agente. */
final class HubHttp {
    private final AgentConfig cfg;
    private final BooleanSupplier lanConnected;
    private final HttpClient cloud = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .proxy(ProxySelector.getDefault()).version(HttpClient.Version.HTTP_1_1).build();
    private final HttpClient lan = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .proxy(HubConnection.LAN_DIRECT_PROXY).version(HttpClient.Version.HTTP_1_1).build();

    HubHttp(AgentConfig cfg, BooleanSupplier lanConnected) {
        this.cfg = cfg;
        this.lanConnected = lanConnected;
    }

    boolean isLan() { return cfg.restricted || (lanConnected.getAsBoolean() && cfg.lanHubUrl != null && !cfg.lanHubUrl.isBlank()); }
    String base() { return AgentUpdater.httpBase(isLan() ? cfg.lanHubUrl : cfg.hubUrl); }
    HttpClient client() { return isLan() ? lan : cloud; }

    HttpRequest.Builder request(String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(15))
                .header("x-client-id", cfg.clientId).header("authorization", "Bearer " + cfg.clientSecret);
        // A rota LAN e autenticada pelo segredo individual do agente e marcada pelo Caddy.
        // Nunca envia o Service Token do Access para dentro da rede local.
        if (!isLan() && !cfg.accessClientId.isEmpty()) {
            request.header("CF-Access-Client-Id", cfg.accessClientId)
                    .header("CF-Access-Client-Secret", cfg.accessClientSecret);
        }
        return request;
    }
}
