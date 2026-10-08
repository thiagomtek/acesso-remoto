package com.transacao.agent;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Conexao de SAIDA do agente com o hub: e sempre o client que disca (o hub nunca conecta nele).
 * Tenta se registrar para sempre: espera crescente entre tentativas (1s ate 60s, com variacao
 * aleatoria), detecta conexao "meio morta" (sem trafego), e reconecta na hora quando o
 * computador volta da suspensao ou a rede muda.
 */
public final class HubConnection {

    public interface Listener {
        void onOpen();

        void onMessage(Map<String, Object> message);

        void onClose(String reason);
    }

    static final long MIN_BACKOFF_MS = 1_000;
    static final long MAX_BACKOFF_MS = 60_000;
    private static final long AUTH_FAILURE_BACKOFF_MS = 30_000;
    private static final long STABLE_AFTER_MS = 30_000;
    private static final long PING_EVERY_MS = 20_000;
    private static final long DEAD_AFTER_MS = 60_000;
    private static final long RESUME_GAP_MS = 20_000;

    /**
     * Rota interna nunca pode obedecer PAC/proxy corporativo: um proxy transforma uma conexao para
     * 192.168.x.x em CONNECT externo e faz a LAN parecer indisponivel. E o equivalente seguro do
     * Socket direto que o aplicativo legado usava; a conexao continua TLS e autenticada.
     */
    static final ProxySelector LAN_DIRECT_PROXY = new ProxySelector() {
        @Override public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // Sem proxy: a falha e tratada pelo ciclo normal de fallback para a nuvem.
        }
    };

    private final AgentConfig cfg;
    private final String hostname;
    private final Listener listener;
    private final Consumer<String> log;
    /** Cloudflare pode exigir proxy corporativo; conserva exatamente o comportamento existente. */
    private final HttpClient cloudHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .proxy(ProxySelector.getDefault())
            .build();
    /** LAN e sempre discada diretamente, sem PAC, proxy do Windows ou proxy da VPN. */
    private final HttpClient lanHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .proxy(LAN_DIRECT_PROXY)
            .build();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> daemon(r, "hub-dispatch"));
    private final Object sendLock = new Object();
    private final Object wake = new Object();

    private volatile boolean running;
    private volatile boolean wakeRequested;
    private volatile WebSocket ws;
    private volatile CompletableFuture<Void> closed;
    private volatile long lastRx;
    private volatile boolean connected;
    private volatile String connectedUrl = "";

    public HubConnection(AgentConfig cfg, String hostname, Listener listener, Consumer<String> log) {
        this.cfg = cfg;
        this.hostname = hostname;
        this.listener = listener;
        this.log = log;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Rota atual, apenas para telemetria tecnica; nunca contem segredo. */
    public boolean isLanConnected() {
        return connectedUrl.equals(cfg.lanHubUrl);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        daemon(this::connectLoop, "hub-connection").start();
        daemon(this::watchdogLoop, "hub-watchdog").start();
    }

    public void stop() {
        running = false;
        abort("encerrando");
        wakeNow();
        dispatcher.shutdownNow();
    }

    /** Envia uma mensagem; devolve false se nao ha conexao (o chamador decide se repete depois). */
    public boolean send(Map<String, Object> message) {
        WebSocket w = ws;
        if (w == null || !connected) {
            return false;
        }
        synchronized (sendLock) {
            try {
                w.sendText(Json.stringify(message), true).get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                abort("falha ao enviar: " + e.getMessage());
                return false;
            }
        }
    }

    /** Envia um frame binario (modo compativel); mesmo cuidado de send(). */
    public boolean sendBinary(byte[] data) {
        WebSocket w = ws;
        if (w == null || !connected) {
            return false;
        }
        synchronized (sendLock) {
            try {
                w.sendBinary(ByteBuffer.wrap(data), true).get(30, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                abort("falha ao enviar: " + e.getMessage());
                return false;
            }
        }
    }

    /** Derruba a conexao atual e tenta de novo imediatamente (ex: voltou da suspensao). */
    public void reconnectNow(String reason) {
        log.accept("Reconectando agora: " + reason);
        abort(reason);
        wakeNow();
    }

    // ---------- ciclo de conexao ----------

    private void connectLoop() {
        int attempt = 0;
        while (running) {
            long startedAt = System.currentTimeMillis();
            boolean authFailure = false;
            boolean everReceived = false;
            try {
                session(selectEndpoint());
                everReceived = lastRx >= startedAt;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof WebSocketHandshakeException) {
                    int status = ((WebSocketHandshakeException) cause).getResponse().statusCode();
                    authFailure = status == 401 || status == 403;
                    log.accept("Hub recusou a conexao (HTTP " + status + ")."
                            + (authFailure ? " Verifique o Service Token / identidade desta maquina." : ""));
                } else {
                    log.accept("Sem conexao com o hub: " + describe(cause));
                }
            }
            connected = false;
            ws = null;
            if (!running) {
                return;
            }
            if (everReceived && System.currentTimeMillis() - startedAt >= STABLE_AFTER_MS) {
                attempt = 0;
            }
            long delay = authFailure ? AUTH_FAILURE_BACKOFF_MS : backoff(attempt);
            attempt++;
            log.accept("Nova tentativa em " + (delay / 1000.0) + "s.");
            if (sleepOrWake(delay)) {
                attempt = 0;
            }
        }
    }

    private String selectEndpoint() throws Exception {
        // LAN primeiro. Se a VPN corporativa bloquear a sub-rede ou o DNS interno, a tentativa curta
        // falha e o mesmo ciclo segue para Cloudflare sem deixar o agente offline.
        LinkedHashSet<String> endpoints = new LinkedHashSet<>();
        if (cfg.lanHubUrl != null && !cfg.lanHubUrl.isBlank()) endpoints.add(cfg.lanHubUrl);
        endpoints.add(cfg.hubUrl);
        Exception last = null;
        for (String endpoint : endpoints) {
            try {
                session(endpoint);
                return endpoint;
            } catch (Exception e) {
                last = e;
                if (!endpoint.equals(cfg.hubUrl)) {
                    // Somente a classe tecnica: permite distinguir DNS, timeout, TLS e HTTP sem
                    // registrar URL completa, credencial ou qualquer dado de sessao.
                    log.accept("Relay LAN indisponivel (" + failureKind(e) + "); mantendo rota pela nuvem.");
                }
            }
        }
        throw last == null ? new IllegalStateException("nenhum endpoint do hub configurado") : last;
    }

    private void session(String endpoint) throws Exception {
        boolean lan = endpoint.equals(cfg.lanHubUrl);
        WebSocket.Builder b = (lan ? lanHttp : cloudHttp).newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(endpoint.equals(cfg.lanHubUrl) ? 2 : 15))
                .header("x-client-id", cfg.clientId)
                .header("authorization", "Bearer " + cfg.clientSecret)
                .header("x-client-name", URLEncoder.encode(hostname, StandardCharsets.UTF_8).replace("+", "%20"));
        if (!cfg.enrollmentToken.isEmpty()) {
            b.header("x-agent-enrollment", cfg.enrollmentToken);
        }
        // O vhost LAN exige o segredo individual do agente, ja enviado acima. Nao entrega o
        // Service Token do Cloudflare a uma rota que nao depende do Access.
        if (!lan && !cfg.accessClientId.isEmpty()) {
            b.header("CF-Access-Client-Id", cfg.accessClientId);
            b.header("CF-Access-Client-Secret", cfg.accessClientSecret);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        closed = done;
        StringBuilder partial = new StringBuilder();
        WebSocket socket = b.buildAsync(URI.create(endpoint), new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket w) {
                lastRx = System.currentTimeMillis();
                w.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                lastRx = System.currentTimeMillis();
                partial.append(data);
                if (last) {
                    String text = partial.toString();
                    partial.setLength(0);
                    dispatcher.execute(() -> {
                        try {
                            listener.onMessage(Json.parseObject(text));
                        } catch (Exception e) {
                            log.accept("Mensagem do hub ignorada: " + e.getMessage());
                        }
                    });
                }
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket w, ByteBuffer message) {
                lastRx = System.currentTimeMillis();
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPong(WebSocket w, ByteBuffer message) {
                lastRx = System.currentTimeMillis();
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
                done.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket w, Throwable error) {
                log.accept("Erro na conexao: " + describe(error));
                done.complete(null);
            }
        // `endpoint` e a rota que esta sendo tentada neste ciclo. Usar cfg.hubUrl aqui
        // mascarava a rota: o log podia dizer LAN, mas o socket continuava atravessando
        // Cloudflare. A tentativa LAN precisa de fato discar o hostname interno; se falhar,
        // selectEndpoint() tenta a rota de nuvem no mesmo ciclo.
        }).get(endpoint.equals(cfg.lanHubUrl) ? 4 : 30, TimeUnit.SECONDS);

        ws = socket;
        connected = true;
        connectedUrl = endpoint;
        log.accept(endpoint.equals(cfg.lanHubUrl) ? "Conectado ao hub pela rede local." : "Conectado ao hub pela nuvem.");
        listener.onOpen();
        done.get();
        connected = false;
        connectedUrl = "";
        listener.onClose("conexao encerrada");
        log.accept("Conexao com o hub encerrada.");
    }

    static long backoff(int attempt) {
        long base = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(attempt, 6));
        // Variacao aleatoria (50%-100%) para muitos clients nao voltarem todos no mesmo instante.
        return (long) (base * (0.5 + ThreadLocalRandom.current().nextDouble() * 0.5));
    }

    private void abort(String reason) {
        WebSocket w = ws;
        CompletableFuture<Void> c = closed;
        connected = false;
        if (w != null) {
            try {
                w.abort();
            } catch (Exception ignored) {
            }
        }
        if (c != null) {
            c.complete(null);
        }
    }

    /** Espera o tempo do backoff; devolve true se foi acordada antes (reconexao imediata pedida). */
    private boolean sleepOrWake(long ms) {
        long until = System.currentTimeMillis() + ms;
        synchronized (wake) {
            while (running && !wakeRequested) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                try {
                    wake.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            boolean woken = wakeRequested;
            wakeRequested = false;
            return woken;
        }
    }

    private void wakeNow() {
        synchronized (wake) {
            wakeRequested = true;
            wake.notifyAll();
        }
    }

    // ---------- vigia: conexao morta, suspensao, troca de rede ----------

    private void watchdogLoop() {
        long lastWall = System.currentTimeMillis();
        long lastMono = System.nanoTime() / 1_000_000;
        long lastPing = 0;
        String net = networkFingerprint();
        while (running) {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                return;
            }
            long wall = System.currentTimeMillis();
            long mono = System.nanoTime() / 1_000_000;
            // O relogio de parede avanca durante a suspensao e o monotonico nao (no Windows/macOS):
            // uma diferenca grande entre os dois = o computador acabou de acordar.
            boolean resumed = (wall - lastWall) - (mono - lastMono) > RESUME_GAP_MS
                    || (wall - lastWall) > RESUME_GAP_MS + 5_000;
            lastWall = wall;
            lastMono = mono;
            String nowNet = networkFingerprint();
            boolean netChanged = !nowNet.equals(net);
            net = nowNet;

            if (resumed) {
                reconnectNow("computador voltou da suspensao");
                continue;
            }
            if (netChanged) {
                reconnectNow("a rede mudou");
                continue;
            }
            if (!connected) {
                continue;
            }
            if (wall - lastRx > DEAD_AFTER_MS) {
                reconnectNow("sem resposta do hub ha " + (DEAD_AFTER_MS / 1000) + "s");
                continue;
            }
            if (wall - lastPing >= PING_EVERY_MS) {
                lastPing = wall;
                WebSocket w = ws;
                if (w != null) {
                    try {
                        synchronized (sendLock) {
                            w.sendPing(ByteBuffer.allocate(0));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    static String networkFingerprint() {
        TreeSet<String> addrs = new TreeSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                Collections.list(ni.getInetAddresses()).forEach(a -> {
                    if (!a.isLinkLocalAddress() && !a.isLoopbackAddress()) {
                        addrs.add(ni.getName() + "=" + a.getHostAddress());
                    }
                });
            }
        } catch (Exception ignored) {
        }
        return String.join(",", addrs);
    }

    private static String describe(Throwable t) {
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
    }

    private static String failureKind(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) root = root.getCause();
        if (root instanceof java.net.UnknownHostException) return "dns";
        if (root instanceof java.net.ConnectException) return "tcp";
        if (root instanceof java.net.http.HttpConnectTimeoutException || root instanceof java.util.concurrent.TimeoutException) return "timeout";
        if (root instanceof javax.net.ssl.SSLException) return "tls";
        if (t instanceof WebSocketHandshakeException) return "http-" + ((WebSocketHandshakeException) t).getResponse().statusCode();
        return root.getClass().getSimpleName().replaceAll("[^A-Za-z0-9-]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
