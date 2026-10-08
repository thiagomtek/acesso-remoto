package com.transacao.agent;

import com.transacao.common.FirstRunGate;
import com.transacao.common.JarUtils;
import com.transacao.common.remote.ClipboardSync;
import com.transacao.common.remote.InputInjector;
import com.transacao.common.remote.ScreenKeepAlive;
import com.transacao.common.remote.TeamsActivityWatcher;
import dev.onvoid.webrtc.PeerConnectionFactory;

import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.UUID;

/**
 * Client simples: sem janela de configuracao. Registra-se sozinho no hub (conexao de saida,
 * sempre tentando de novo), recebe as configuracoes do painel web e atende as sessoes de acesso
 * remoto que os operadores abrirem. Tudo sem privilegio de administrador.
 */
public final class AgentApp {

    /** Assinatura do software como Assistente. */
    static final String APP_NAME = "Assistente";
    private static final String VERSION = "agent";

    private final AgentConfig cfg;
    private final AgentSettings settings = new AgentSettings();
    /** Uma sessao de acesso: tenta WebRTC primeiro; se nao funcionar, cai para o modo compativel (Java puro). */
    private static final class Session {
        final String id;
        volatile RemoteSession rtc;
        volatile CompatSession compat;
        volatile boolean closed;

        Session(String id) {
            this.id = id;
        }
    }

    private static final long RTC_CONNECT_TIMEOUT_S = 12;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** Falso depois que a biblioteca nativa do WebRTC e bloqueada (ex.: Smart App Control): nao insiste a cada sessao. */
    private volatile boolean webrtcUsable = true;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-timer");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService worker = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-worker");
        t.setDaemon(true);
        return t;
    });
    private final File logFile;
    private HubConnection hub;
    private AgentUpdater updater;
    private boolean appliedAutostart;
    private ClipboardSync clipboard;
    private final Object clipboardLock = new Object();
    private boolean filesNoticeLogged;
    private String jarHash = "";
    private File runningJar;
    private PeerConnectionFactory factory;
    private ScreenSource screen;
    private InputInjector injector;
    private ScreenKeepAlive keepAlive;
    private TeamsActivityWatcher teams;
    private SharedFolderSync sharedFolder;
    private TrayIcon tray;
    private volatile String status = "Conectando...";
    // Locks separados: nenhum deles e segurado durante chamadas nativas do WebRTC nem durante log().
    private final Object sessionLock = new Object();
    private final Object settingsLock = new Object();
    private final Object injectorLock = new Object();
    private final Object logLock = new Object();
    private final Map<String, Map<String, Object>> pendingCentralLogs = new ConcurrentHashMap<>();
    /** Resultados ficam pendentes ate o hub confirmar persistencia; reconexao nao cria lacunas. */
    private final Map<String, Map<String, Object>> pendingInputResults = new ConcurrentHashMap<>();
    /** Evita reenviar continuamente a mesma falha para o hub. */
    private final Map<String, Long> lastCentralLogAt = new ConcurrentHashMap<>();
    /** Evita que falhas identicas encham stdout/agent.log em loops de reconexao. */
    private final Map<String, Long> lastLocalLogAt = new ConcurrentHashMap<>();

    private AgentApp(AgentConfig cfg) {
        this.cfg = cfg;
        this.logFile = new File(cfg.dir, "agent.log");
    }

    public static void main(String[] args) throws Exception {
        boolean[] ok = {false};
        SwingUtilities.invokeAndWait(() -> ok[0] = FirstRunGate.requirePassword(APP_NAME));
        if (!ok[0]) {
            return;
        }
        File jarDir = jarDir();
        AgentConfig cfg = AgentConfig.load(AgentConfig.defaultDir(), jarDir);
        HubDnsFallback.configure(java.net.URI.create(cfg.hubUrl).getHost(), cfg.hubIp, cfg.restricted);
        FileLock lock = singleInstanceLock(cfg.dir);
        if (lock == null) {
            return; // ja ha um agente rodando neste usuario
        }
        AgentApp app = new AgentApp(cfg);
        app.start();
        Thread.currentThread().join(); // o resto roda em threads daemon e na bandeja
    }

    private static File jarDir() {
        try {
            File f = JarUtils.findRunningJar(AgentApp.class);
            File p = f.isFile() ? f.getParentFile() : f;
            return p != null ? p : new File(".");
        } catch (Exception e) {
            return new File(".");
        }
    }

    private static FileLock singleInstanceLock(File dir) throws IOException {
        FileChannel ch = FileChannel.open(new File(dir, "agent.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        return ch.tryLock();
    }

    private void start() {
        try {
            File f = JarUtils.findRunningJar(AgentApp.class);
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                runningJar = f;
                jarHash = JarUtils.sha256(f);
                // restos de atualizacoes de versoes antigas (que usavam scripts)
                for (String leftover : new String[] {"auto-update.vbs"}) {
                    new File(f.getParentFile(), leftover).delete();
                }
                // Nao deve haver arquivo de leia-me na pasta de instalacao (nem os que vinham em pacotes antigos)
                File[] docs = f.getParentFile().listFiles((d, n) -> n.toUpperCase().startsWith("LEIA-ME"));
                if (docs != null) {
                    for (File doc : docs) {
                        if (doc.delete()) {
                            log("Removido da pasta de instalacao: " + doc.getName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("Nao foi possivel identificar a versao do agente: " + e.getMessage());
        }
        log("Assistente iniciando. Hub: " + cfg.hubUrl + " | id " + cfg.clientId + " | versao " + (jarHash.isEmpty() ? "dev" : jarHash.substring(0, 8)));
        // Inicializacao automatica SO para o usuario atual (HKCU Run / LaunchAgent / autostart), sem scripts:
        // registrada em toda partida - inclusive logo apos uma atualizacao - de acordo com a preferencia local.
        settings.loadLocal(new File(cfg.dir, "local-settings.properties"));
        boolean localOnly = settings.localOnly || cfg.restricted;
        HubDnsFallback.configure(java.net.URI.create(cfg.hubUrl).getHost(), cfg.hubIp, localOnly);

        applyKeyboardHelperSettings();

        // Migracao unica: o modo servico/driver virtual foi removido do produto. Se esta maquina
        // ainda tiver os artefatos de uma ativacao antiga, desfaz tudo com UAC e volta ao modo normal.
        if (!localOnly && LegacyServiceModeCleanup.isPresent()) {
            log("Detectados componentes legados do modo servico; iniciando reversao para o modo normal.");
            LegacyServiceModeCleanup.applyAsync(this::log);
        }
        appliedAutostart = settings.startWithSystem;
        if (settings.startWithSystem) {
            Autostart.ensure(runningJar, this::log);
        } else {
            Autostart.remove(this::log);
        }
        // WebRTC
        if (!settings.webrtcEnabled) {
            webrtcUsable = false;
            log("WebRTC desativado nas configuracoes da maquina; operando em modo compativel.");
        } else if (WebrtcGuard.smartAppControlEnforced()) {
            webrtcUsable = false;
            log("Smart App Control ativo: usando o modo compativel (sem carregar o WebRTC).");
        } else if (WebrtcGuard.blockedBefore(cfg.dir, jarHash)) {
            webrtcUsable = false;
            log("O WebRTC foi bloqueado nesta maquina antes: usando o modo compativel (uma versao nova tenta de novo).");
        }
        updater = new AgentUpdater(cfg, runningJar, jarHash, () -> sessions.isEmpty(), () -> hub != null && hub.isLanConnected(),
                this::shutdown, this::log, this::reportEvent);
        initTray();
        scheduleHealthSignal();
        timer.scheduleAtFixedRate(this::sendStats, 5, 10, TimeUnit.SECONDS);
        sharedFolder = new SharedFolderSync(cfg, settings, new HubHttp(cfg, () -> hub != null && hub.isLanConnected()), this::log);
        timer.scheduleWithFixedDelay(sharedFolder::syncIfEnabled, 8, 5, TimeUnit.SECONDS);
        hub = new HubConnection(cfg, hostname(), new HubConnection.Listener() {
            @Override
            public void onOpen() {
                setStatus("Conectado");
                sendHello();
                flushCentralLogs();
                flushInputResults();
                reportRollbackIfAny();
            }

            @Override
            public void onMessage(Map<String, Object> m) {
                handle(m);
            }

            @Override
            public void onClose(String reason) {
                setStatus("Reconectando...");
                closeAllSessions();
            }
        }, this::log, () -> settings.localOnly || cfg.restricted);
        hub.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    // ---------- mensagens do hub ----------

    @SuppressWarnings("unchecked")
    private void handle(Map<String, Object> m) {
        String type = Json.str(m, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "pending":
                setStatus("Aguardando aprovacao no painel");
                log("Maquina registrada. Aguardando um operador aprovar no painel.");
                break;
            case "settings":
                setStatus("Conectado");
                settings.update(Json.obj(m.get("settings")));
                applySettings();
                refreshClipboard();
                for (Session s : sessions.values()) {
                    RemoteSession r = s.rtc;
                    if (r != null) {
                        worker.execute(r::applyEncoding);
                    }
                    CompatSession c = s.compat;
                    if (c != null) {
                        c.applySettings();
                    }
                }
                break;
            case "update": // o hub tem uma versao diferente: baixa, confere assinatura e troca quando ocioso
                if (!settings.autoUpdate) {
                    log("Atualizacao automatica recusada: desabilitada nas configuracoes da maquina.");
                    break;
                }
                updater.onOffer(Json.str(m, "jarSha256"), Json.str(m, "zipSha256"), (long) Json.num(m, "size", 0));
                break;
            case "update-status":
                String updateStatus = Json.str(m, "status");
                if ("up-to-date".equals(updateStatus)) {
                    setStatus("Atualizado");
                    showTrayMessage("Atualização", "Este agente já está atualizado.", TrayIcon.MessageType.INFO);
                } else if ("unavailable".equals(updateStatus)) {
                    setStatus("Atualização indisponível");
                    showTrayMessage("Atualização", "Não foi possível consultar uma atualização agora.", TrayIcon.MessageType.WARNING);
                }
                break;
            case "file-download":
                String transferId = Json.str(m, "transferId");
                String transferSession = Json.str(m, "sessionId");
                worker.execute(() -> FileTransfer.downloadToReceipts(cfg, transferId, Json.str(m, "name"),
                        (long) Json.num(m, "size", -1), this::log,
                        ok -> reportFileDelivery(transferSession, transferId, ok)));
                break;
            case "session-open": {
                String id = Json.str(m, "sessionId");
                List<Map<String, Object>> ice = new java.util.ArrayList<>();
                Object raw = m.get("iceServers");
                if (raw instanceof List) {
                    for (Object o : (List<Object>) raw) {
                        if (Json.obj(o) != null) {
                            ice.add(Json.obj(o));
                        }
                    }
                }
                worker.execute(() -> openSession(id, ice));
                break;
            }
            case "rtc": {
                Session s = sessions.get(Json.str(m, "sessionId"));
                RemoteSession r = s == null ? null : s.rtc;
                if (r != null) {
                    r.onRemoteMessage(Json.obj(m.get("data")));
                }
                break;
            }
            case "cmd": { // comandos do operador no modo compativel (e o pedido explicito de trocar de modo)
                Session s = sessions.get(Json.str(m, "sessionId"));
                Map<String, Object> data = Json.obj(m.get("data"));
                if (s == null || data == null) {
                    break;
                }
                if ("clip".equals(Json.str(data, "t"))) {
                    String result = "applied";
                    try {
                        applyRemoteClipboard(Json.str(data, "text"));
                    } catch (RuntimeException e) {
                        result = "error";
                    }
                    reportInputResult(s.id, "compat", (long) Json.num(data, "q", 0),
                            Json.str(data, "trace"), "clip", result);
                } else if ("clip-image".equals(Json.str(data, "t"))) {
                    long seq = (long) Json.num(data, "q", 0);
                    String trace = Json.str(data, "trace");
                    String imageTransferId = Json.str(data, "transferId");
                    long imageSize = (long) Json.num(data, "size", 0);
                    String imageSha = Json.str(data, "sha256");
                    worker.execute(() -> {
                        String result = "rejected";
                        try {
                            byte[] png = FileTransfer.downloadBytes(cfg, imageTransferId, imageSize, ClipboardImage.MAX_BYTES);
                            if (settings.clipboardSync && ClipboardImage.apply(png, imageSha)) result = "applied";
                        } catch (Exception e) {
                            log("Falha tecnica ao aplicar imagem remota no clipboard.");
                        }
                        reportInputResult(s.id, "compat", seq, trace, "clip-image", result);
                    });
                } else if ("use-compat".equals(Json.str(data, "t"))) {
                    worker.execute(() -> fallbackToCompat(s, "pedido do operador"));
                } else if ("latency-ping".equals(Json.str(data, "t")) && s.compat != null) {
                    sendCompatPong(s.id, (int) Json.num(data, "n", -1));
                } else if (s.compat != null) {
                    s.compat.onCommand(data);
                }
                break;
            }
            case "input-result-ack": {
                String trace = Json.str(m, "trace");
                if (trace != null) pendingInputResults.remove(trace);
                break;
            }
            case "agent-log-ack": {
                String logId = Json.str(m, "id");
                if (logId != null) pendingCentralLogs.remove(logId);
                break;
            }
            case "session-close": {
                Session s = sessions.remove(Json.str(m, "sessionId"));
                if (s != null) {
                    worker.execute(() -> closeSession(s));
                }
                refreshClipboard();
                break;
            }
            default:
                break;
        }
    }

    private void openSession(String id, List<Map<String, Object>> ice) {
        Session s = new Session(id);
        sessions.put(id, s);
        refreshClipboard();
        // A bandeja e um identificador discreto do agente; nao revela para quem olhar a barra de
        // tarefas que existe uma sessao remota ativa. O estado operacional continua centralizado
        // no painel autenticado e nos logs tecnicos.
        if (settings.allowRemoteControl && settings.nativeKeyboardHelper) worker.execute(() -> {
            try { injector().prepareNativeKeyboard(); } catch (Exception ignored) { /* fallback do injetor */ }
        });
        if (!webrtcUsable || !settings.webrtcEnabled) {
            fallbackToCompat(s, !settings.webrtcEnabled ? "o WebRTC esta desativado nas configuracoes da maquina" : "o WebRTC esta indisponivel nesta maquina");
            return;
        }
        try {
            synchronized (sessionLock) {
                if (factory == null) {
                    factory = new PeerConnectionFactory();
                    screen = new ScreenSource(factory);
                }
            }
            RemoteSession r = new RemoteSession(id, ice, factory, screen, settings, this::injector, this::applyRemoteClipboard,
                    this::applyRemoteClipboardImage, data -> sendRtc(id, data), this::log,
                    (seq, trace, command, result) -> reportInputResult(id, "webrtc", seq, trace, command, result),
                    worker, () -> fallbackToCompat(s, "o WebRTC nao conseguiu conectar"));
            s.rtc = r;
            r.start();
            log("Sessao " + id.substring(0, 8) + " aberta por um operador (WebRTC).");
            // Se a rede nao deixar o WebRTC fechar a conexao a tempo, usa o modo compativel.
            timer.schedule(() -> {
                RemoteSession cur = s.rtc;
                if (!s.closed && s.compat == null && cur != null && !cur.isConnected()) {
                    worker.execute(() -> fallbackToCompat(s, "o WebRTC nao conectou em " + RTC_CONNECT_TIMEOUT_S + "s"));
                }
            }, RTC_CONNECT_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (Throwable t) {
            // UnsatisfiedLinkError / NoClassDefFoundError: o Windows bloqueou a DLL nativa (Controle de Aplicativo).
            if (t instanceof LinkageError) {
                webrtcUsable = false;
                WebrtcGuard.markBlocked(cfg.dir, jarHash); // proximo inicio nem tenta
            }
            log("WebRTC indisponivel: " + t);
            fallbackToCompat(s, "falha ao iniciar o WebRTC (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Troca a sessao para o modo compativel (Java puro): sem nenhuma biblioteca nativa. Idempotente. */
    private void fallbackToCompat(Session s, String reason) {
        CompatSession c;
        synchronized (s) {
            if (s.closed || s.compat != null) {
                return;
            }
            c = new CompatSession(s.id, settings, this::injector, hub::sendBinary, this::log,
                    (seq, trace, command, result) -> reportInputResult(s.id, "compat", seq, trace, command, result));
            s.compat = c;
        }
        log("Sessao " + s.id.substring(0, 8) + ": usando o modo compativel porque " + reason + ".");
        RemoteSession r = s.rtc;
        s.rtc = null;
        if (r != null) {
            try {
                r.close();
            } catch (Throwable t) {
                log("Aviso ao encerrar o WebRTC antes de trocar de modo: " + t);
            }
        }
        Map<String, Object> mode = new LinkedHashMap<>();
        mode.put("type", "mode");
        mode.put("mode", "compat");
        mode.put("reason", reason);
        mode.put("clipboardImageV1", true);
        sendRtc(s.id, mode); // o visualizador troca para o desenho dos blocos antes dos primeiros frames
        try {
            c.start();
        } catch (Throwable t) {
            log("O modo compativel tambem falhou: " + t);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("type", "error");
            err.put("message", "Nao foi possivel iniciar o acesso nesta maquina (" + reason + "; modo compativel: " + t + ").");
            sendRtc(s.id, err);
            sessions.remove(s.id);
            closeSession(s);
        }
    }

    private void closeSession(Session s) {
        s.closed = true;
        RemoteSession r = s.rtc;
        CompatSession c = s.compat;
        if (r != null) {
            r.close();
        }
        if (c != null) {
            c.close();
        }
    }

    private void sendRtc(String sessionId, Map<String, Object> data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "rtc");
        msg.put("sessionId", sessionId);
        msg.put("data", data);
        hub.send(msg);
    }

    /** Eco sem dados da sessao, usado apenas para medir o RTT no modo compativel. */
    private void sendCompatPong(String sessionId, int n) {
        if (n < 0 || hub == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("n", n);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "compat-pong");
        msg.put("sessionId", sessionId);
        msg.put("data", data);
        hub.send(msg);
    }

    private InputInjector injector() throws AWTException {
        synchronized (injectorLock) {
            if (injector == null) {
                injector = new InputInjector();
                if (!settings.nativeKeyboardHelper) {
                    injector.disableNativeHelper();
                }
            }
            return injector;
        }
    }

    private void applyKeyboardHelperSettings() {
        synchronized (injectorLock) {
            if (injector != null) {
                if (!settings.nativeKeyboardHelper) {
                    injector.disableNativeHelper();
                } else {
                    injector.enableNativeHelper();
                }
            }
        }
    }

    private void closeAllSessions() {
        for (Session s : sessions.values()) {
            worker.execute(() -> closeSession(s));
        }
        sessions.clear();
        refreshClipboard();
    }

    // ---------- area de transferencia (texto) ----------

    /** Sincroniza so enquanto ha operador conectado E a configuracao da maquina permite (privacidade). */
    private void refreshClipboard() {
        synchronized (clipboardLock) {
            boolean want = settings.clipboardSync && !sessions.isEmpty();
            if (want && clipboard == null) {
                // Sem pasta de recebidos configuravel: o destino dos arquivos sera a pasta em que o usuario
                // esta trabalhando (futuro); esta pasta e so area de preparo interna do ClipboardSync.
                File dir = new File(cfg.dir, "staging");
                try {
                    ClipboardSync sync = new ClipboardSync(new ClipboardSync.Sender() {
                        @Override
                        public void sendText(String text) {
                            if (text == null || text.length() > 500_000) {
                                return;
                            }
                            Map<String, Object> d = new LinkedHashMap<>();
                            d.put("type", "clip");
                            d.put("text", text);
                            for (Session s : sessions.values()) {
                                // Se a sessao esta em WebRTC, o clipboard percorre o proprio canal
                                // de controle confiavel/ordenado. O hub fica exclusivamente para o
                                // modo compativel (ou como fallback enquanto o WebRTC negocia).
                                RemoteSession r = s.rtc;
                                if (r == null || !r.sendCtl(d)) {
                                    sendRtc(s.id, d);
                                }
                            }
                        }

                        @Override
                        public void sendFiles(byte[] zipBytes) {
                            for (Session s : sessions.values()) {
                                worker.execute(() -> FileTransfer.upload(cfg, s.id, zipBytes, AgentApp.this::log));
                            }
                        }
                    }, dir);
                    sync.setErrorListener(this::log);
                    clipboard = sync;
                    log("Sincronizacao da area de transferencia (texto) ativa.");
                } catch (Throwable t) {
                    log("Area de transferencia indisponivel: " + t);
                }
            } else if (!want && clipboard != null) {
                clipboard.stop();
                clipboard = null;
                log("Sincronizacao da area de transferencia parada.");
            }
        }
    }

    private void applyRemoteClipboard(String text) {
        if (text == null || text.length() > 500_000) {
            return;
        }
        synchronized (clipboardLock) {
            if (settings.clipboardSync && clipboard != null) {
                clipboard.applyRemoteText(text);
            }
        }
    }

    private boolean applyRemoteClipboardImage(byte[] png, String sha256) {
        return settings.clipboardSync && ClipboardImage.apply(png, sha256);
    }

    /**
     * Depois de ~20s rodando sem cair, avisa ao script de atualizacao que esta versao sobe bem. Sem esse
     * sinal, o script desfaz a atualizacao e volta a versao anterior.
     */
    private void scheduleHealthSignal() {
        if (runningJar == null) {
            return;
        }
        timer.schedule(() -> {
            try {
                Files.writeString(new File(runningJar.getParentFile(), AgentUpdater.HEALTHY_FLAG).toPath(), jarHash);
            } catch (IOException e) {
                log("Nao foi possivel gravar o sinal de saude: " + e.getMessage());
            } finally {
                // Mesmo que o arquivo nao possa ser escrito, esta instancia ja passou a janela de
                // estabilidade. Libera apenas agora qualquer oferta que tenha chegado no inicio.
                updater.confirmHealthy();
            }
        }, 20, TimeUnit.SECONDS);
    }

    /** Evento para o hub (historico/painel). Melhor esforco: sem conexao, so nao registra. */
    private void reportEvent(String event, Map<String, Object> data) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "status");
        msg.put("event", event);
        msg.put("data", data);
        if (hub != null) {
            hub.send(msg);
        }
    }

    /** Confirma ao hub o resultado da gravacao em recebimentos; nao inclui nome, caminho nem conteudo. */
    private void reportFileDelivery(String sessionId, String transferId, boolean delivered) {
        if (sessionId == null || transferId == null || hub == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("transferId", transferId);
        data.put("delivered", delivered);
        reportEvent("file-delivery", data);
    }

    /** Confirma apenas a imagem de clipboard, unico controle que aguarda resultado fim a fim. */
    private void reportInputResult(String sessionId, String transport, long seq, String trace,
                                   String command, String result) {
        if (!"clip-image".equals(command) && !"clip-image-start".equals(command)) return;
        if (hub == null || sessionId == null || trace == null || seq <= 0) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId); data.put("transport", transport);
        data.put("seq", seq); data.put("trace", trace); data.put("command", command); data.put("result", result);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "status"); msg.put("event", "input-result"); msg.put("data", data);
        pendingInputResults.put(trace, msg);
        hub.send(msg); // so sai da fila quando o hub responder input-result-ack
    }

    private void flushInputResults() {
        if (hub == null) return;
        for (Map<String, Object> msg : pendingInputResults.values()) {
            if (!hub.send(msg)) return;
        }
    }

    /** Se uma atualizacao foi desfeita (a nova nao subiu), avisa o painel uma vez. */
    private void reportRollbackIfAny() {
        if (runningJar == null) {
            return;
        }
        File pending = new File(runningJar.getParentFile(), AgentUpdater.ROLLBACK_PENDING);
        if (!pending.isFile()) {
            return;
        }
        String bad = "";
        try {
            bad = Files.readString(pending.toPath()).trim();
        } catch (IOException ignored) {
            // segue sem o hash
        }
        log("A atualizacao para a versao " + (bad.length() >= 8 ? bad.substring(0, 8) : bad) + " nao subiu: voltei para esta versao.");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("badVersion", bad.length() >= 8 ? bad.substring(0, 8) : bad);
        data.put("currentVersion", jarHash.length() >= 8 ? jarHash.substring(0, 8) : jarHash);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "status");
        msg.put("event", "update-rollback");
        msg.put("data", data);
        if (hub.send(msg)) {
            pending.delete();
        }
    }

    /** Uso de CPU/memoria da maquina, pro painel mostrar ao vivo (ver SystemStats). */
    private void sendStats() {
        if (hub == null) {
            return;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "stats");
        Map<String, Object> data = SystemStats.snapshot();
        // A rota e metadado operacional (nao identifica rede, IP ou conteudo) e permite ao
        // operador conferir no HUD se a sessao esta realmente na LAN ou pela Internet.
        data.put("hubRoute", hub.isLanConnected() ? "lan" : "cloud");
        msg.put("data", data);
        hub.send(msg);
    }

    private void sendHello() {
        int[] phys = ScreenGeometry.physicalSize();
        Map<String, Object> screenInfo = new LinkedHashMap<>();
        screenInfo.put("w", phys[0]);
        screenInfo.put("h", phys[1]);
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("hostname", hostname());
        hello.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        hello.put("version", VERSION + (jarHash.isEmpty() ? "-dev" : "-" + jarHash.substring(0, 8)));
        hello.put("jarSha256", jarHash);
        // Enviado no inicio da conexao (antes de qualquer video), para o HUD nunca depender do
        // timer de estatisticas que pode esperar atras de um fluxo compatível congestionado.
        hello.put("hubRoute", hub != null && hub.isLanConnected() ? "lan" : "cloud");
        hello.put("screen", screenInfo);
        hub.send(hello);
    }

    // ---------- configuracoes vindas do painel ----------

    private void applySettings() {
        synchronized (settingsLock) {
            applySettingsLocked();
        }
    }

    private void applySettingsLocked() {
        // Rede local exclusiva vs normal
        boolean localOnly = settings.localOnly || cfg.restricted;
        HubDnsFallback.setForceExclusive(localOnly, java.net.URI.create(cfg.hubUrl).getHost(), cfg.hubIp);
        if (localOnly && hub != null && hub.isConnected() && !hub.isLanConnected()) {
            hub.reconnectNow("Restricao exclusiva de rede local ativada pelo painel");
        }
        // Injetor nativo
        applyKeyboardHelperSettings();
        // WebRTC
        if (!settings.webrtcEnabled) {
            webrtcUsable = false;
        } else if (!WebrtcGuard.smartAppControlEnforced() && !WebrtcGuard.blockedBefore(cfg.dir, jarHash)) {
            webrtcUsable = true;
        }
        // Anti-suspensao
        if (settings.keepAwake && keepAlive == null) {
            keepAlive = new ScreenKeepAlive(this::log);
            Thread t = new Thread(keepAlive, "screen-keepalive");
            t.setDaemon(true);
            t.start();
            log("Anti-suspensao ativado pelo painel.");
        } else if (!settings.keepAwake && keepAlive != null) {
            keepAlive.stop();
            keepAlive = null;
            log("Anti-suspensao desativado pelo painel.");
        }
        // Atividade no Teams (so avisa que houve; nao le o conteudo)
        if (settings.teamsWatcher && teams == null) {
            teams = new TeamsActivityWatcher(active -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("active", active);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "status");
                msg.put("event", "teams");
                msg.put("data", data);
                hub.send(msg);
                log(active ? "Atividade nova no Teams." : "Atividade do Teams voltou ao normal.");
            });
            teams.setErrorListener(this::log);
            Thread t = new Thread(teams, "teams-activity-watcher");
            t.setDaemon(true);
            t.start();
            log("Monitoramento do Teams ativado pelo painel.");
        } else if (!settings.teamsWatcher && teams != null) {
            teams.stop();
            teams = null;
            log("Monitoramento do Teams desativado pelo painel.");
        }
        // Iniciar com o sistema (so o usuario atual, sem admin e sem scripts)
        if (settings.startWithSystem != appliedAutostart) {
            appliedAutostart = settings.startWithSystem;
            if (settings.startWithSystem) {
                Autostart.ensure(runningJar, this::log);
            } else {
                Autostart.remove(this::log);
            }
        }
        settings.saveLocal(new File(cfg.dir, "local-settings.properties"));
    }

    // ---------- bandeja / log ----------

    private void initTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            PopupMenu popup = new PopupMenu();
            MenuItem info = new MenuItem("Assistente");
            info.setEnabled(false);
            MenuItem update = new MenuItem("Atualizar");
            update.addActionListener(e -> requestManualUpdate());
            MenuItem exit = new MenuItem("Sair");
            exit.addActionListener(e -> {
                shutdown();
                System.exit(0);
            });
            popup.add(info);
            popup.addSeparator();
            popup.add(update);
            popup.add(exit);
            tray = new TrayIcon(trayImage(), "Assistente", popup);
            tray.setImageAutoSize(true);
            SystemTray.getSystemTray().add(tray);
        } catch (Exception e) {
            log("Sem icone na bandeja: " + e.getMessage());
        }
    }

    private void setStatus(String s) {
        status = s;
        if (tray != null) {
            tray.setToolTip("Assistente: " + s);
        }
    }

    /** A bandeja pede ao hub a mesma oferta assinada usada no fluxo automático. */
    private void requestManualUpdate() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "check-update");
        if (hub == null || !hub.send(request)) {
            setStatus("Atualização indisponível");
            showTrayMessage("Atualização", "Sem conexão com o hub para verificar a atualização.", TrayIcon.MessageType.WARNING);
            return;
        }
        setStatus("Verificando atualização...");
        showTrayMessage("Atualização", "Verificando versão publicada...", TrayIcon.MessageType.INFO);
    }

    private void showTrayMessage(String title, String message, TrayIcon.MessageType type) {
        if (tray != null) {
            tray.displayMessage(title, message, type);
        }
    }

    private static Image trayImage() {
        return new BaseMultiResolutionImage(
                drawTrayImage(16), drawTrayImage(20), drawTrayImage(24), drawTrayImage(32));
    }

    /** Icone de bandeja sem asset externo: monitor remoto + indicador de conexao. */
    private static BufferedImage drawTrayImage(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double scale = size / 32.0;
        g.scale(scale, scale);

        g.setPaint(new GradientPaint(3, 2, new Color(67, 142, 255), 29, 31, new Color(29, 78, 216)));
        g.fillRoundRect(1, 1, 30, 30, 9, 9);

        g.setColor(new Color(255, 255, 255, 238));
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(6, 7, 20, 14, 3, 3);
        g.drawLine(16, 21, 16, 24);
        g.drawLine(11, 25, 21, 25);

        // Ponto de estado legivel mesmo quando o Windows reduz o icone para 16 px.
        g.setColor(Color.WHITE);
        g.fillOval(20, 3, 10, 10);
        g.setColor(new Color(34, 197, 94));
        g.fillOval(22, 5, 6, 6);
        g.dispose();
        return img;
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private void log(String message) {
        if (message == null || message.isBlank()) return;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("teclado") || lower.contains("tecla")) return;
        long now = System.currentTimeMillis();
        String localKey = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        Long previous = lastLocalLogAt.put(localKey, now);
        if (previous != null && now - previous < 60_000) return;
        if (lastLocalLogAt.size() > 256) {
            lastLocalLogAt.entrySet().removeIf(entry -> now - entry.getValue() > 5 * 60_000);
        }
        String line = "[" + LocalDateTime.now().format(TS) + "] " + message;
        System.out.println(line);
        reportCentralLog(message);
    }

    private void reportCentralLog(String message) {
        if (message == null) return;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        boolean sensitive = lower.contains("clipboard") || lower.contains("senha") || lower.contains("token")
                || lower.contains("secret") || lower.contains("authorization") || lower.contains("arquivo");
        String detail = sensitive ? "Evento tecnico em subsistema sensivel (conteudo omitido)." : message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (detail.length() > 240) detail = detail.substring(0, 240);
        String level = (lower.contains("falha") || lower.contains("erro") || lower.contains("recus")) ? "error"
                : lower.contains("bloquead") || lower.contains("compat") ? "warn" : "info";
        // O hub recebe apenas avisos e erros. Mensagens informativas permanecem somente locais.
        if ("info".equals(level)) return;
        long now = System.currentTimeMillis();
        String dedupeKey = diagnosticCode(lower) + ':' + level + ':' + detail;
        Long previous = lastCentralLogAt.put(dedupeKey, now);
        if (previous != null && now - previous < 5 * 60_000) return;
        if (lastCentralLogAt.size() > 256) {
            lastCentralLogAt.entrySet().removeIf(entry -> now - entry.getValue() > 30 * 60_000);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        data.put("id", id);
        data.put("code", diagnosticCode(lower)); data.put("level", level); data.put("detail", detail);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "status"); event.put("event", "agent-log"); event.put("data", data);
        if (pendingCentralLogs.size() >= 200) {
            var oldest = pendingCentralLogs.keySet().stream().findFirst().orElse(null);
            if (oldest != null) pendingCentralLogs.remove(oldest);
        }
        pendingCentralLogs.put(id, event);
        if (hub != null) hub.send(event); // so remove depois da confirmacao de persistencia do hub
    }

    private void flushCentralLogs() {
        if (hub == null) return;
        for (Map<String, Object> event : pendingCentralLogs.values()) {
            if (!hub.send(event)) return;
        }
    }

    private static String diagnosticCode(String lower) {
        if (lower.contains("atualiza") || lower.contains("trocar os arquivos")) return "update";
        if (lower.contains("modo servico") || lower.contains("winsw") || lower.contains("driver")) return "service-mode";
        if (lower.contains("webrtc")) return "webrtc";
        if (lower.contains("hub") || lower.contains("conexao")) return "connection";
        return "agent";
    }

    private void shutdown() {
        closeAllSessions();
        if (hub != null) {
            hub.stop();
        }
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            String env = System.getenv("COMPUTERNAME");
            return env != null ? env : "client";
        }
    }
}
