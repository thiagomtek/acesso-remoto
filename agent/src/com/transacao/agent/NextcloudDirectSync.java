package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sincronizador direto entre a pasta <usuario>/recebimentos e o Nextcloud do usuario via WebDAV.
 *
 * Arquitetura e isolamento:
 * - O agente comunica DIRETAMENTE com o Nextcloud via HTTPS, sem trafegar arquivos pelo Hub.
 * - Roda em thread propria de prioridade reduzida (agent-nextcloud-sync, prioridade MIN_PRIORITY + 1).
 *   Nunca concorre com captura de tela, codificacao de video ou injecao de entrada.
 * - Sincronizacao bidirecional completa: adicao, alteracao e exclusao de arquivos e pastas.
 * - Nunca trava shutdown, encerramento ou bandeja: chamadas de close sao nao-bloqueantes.
 */
final class NextcloudDirectSync {
    static final long IDLE_SYNC_INTERVAL_MS = 60_000L;
    // A captura de video tem prioridade sobre a sincronizacao automatica. Um pedido
    // manual do operador e explicito e, portanto, pode consumir recursos do video.
    private static final String DIR_KEY_PREFIX = "/dir:";
    private static final String SCOPE_KEY = "/sync-scope";

    private static final String PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<d:propfind xmlns:d=\"DAV:\">\n"
            + "  <d:prop>\n"
            + "    <d:resourcetype/>\n"
            + "    <d:getcontentlength/>\n"
            + "    <d:getlastmodified/>\n"
            + "    <d:getetag/>\n"
            + "  </d:prop>\n"
            + "</d:propfind>";

    private static final Pattern RESP_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?response\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?response>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MULTISTATUS_END_PAT = Pattern.compile("</(?:[a-zA-Z0-9_-]+:)?multistatus\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?href\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?href>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COL_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?collection\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEN_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?getcontentlength\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?getcontentlength>", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOD_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?getlastmodified\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?getlastmodified>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ETAG_PAT = Pattern.compile("<(?:[a-zA-Z0-9_-]+:)?getetag\\b[^>]*>([\\s\\S]*?)</(?:[a-zA-Z0-9_-]+:)?getetag>", Pattern.CASE_INSENSITIVE);

    private final AgentConfig cfg;
    private final File root;
    private final File stateFile;
    private final File configFile;
    private final Consumer<String> log;
    private final BiConsumer<String, Map<String, Object>> statusReporter;
    private final BooleanSupplier isMachineAccessed;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    // Thread dedicada de baixa prioridade, apartada da transmissao de video
    private final ScheduledExecutorService syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "agent-nextcloud-sync");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private final HttpClient httpClient;

    private boolean enabled;
    private String url = "https://servidor.tail074692.ts.net:8445/";
    private String username = "";
    private String appPassword = "";
    private String remoteFolder = "/";

    private String resolvedDavRoot = null;
    private String authHeader = null;

    private boolean wasAccessed;
    private volatile boolean forceSyncRequested = true;
    private volatile String pendingRequestId;
    private long lastFullSyncAt;
    private long lastRemoteCheckAt;
    private long localChangeDetectedAt;
    private String lastRemoteEtag = "";
    private RemoteSnapshot lastRemoteSnapshot;
    private WatchService watcher;

    NextcloudDirectSync(AgentConfig cfg, Consumer<String> log, BiConsumer<String, Map<String, Object>> statusReporter, BooleanSupplier isMachineAccessed) {
        this(cfg, log, statusReporter, isMachineAccessed,
                FileTransfer.receiptsDirectory(System.getenv("USERPROFILE"), System.getProperty("user.home")), true);
    }

    NextcloudDirectSync(AgentConfig cfg, Consumer<String> log, BiConsumer<String, Map<String, Object>> statusReporter,
            BooleanSupplier isMachineAccessed, File receiptsRoot, boolean startScheduler) {
        this.cfg = cfg;
        this.log = log;
        this.statusReporter = statusReporter;
        this.isMachineAccessed = isMachineAccessed != null ? isMachineAccessed : () -> false;
        this.root = receiptsRoot;
        this.stateFile = new File(cfg.dir, "nextcloud-sync-state.properties");
        this.configFile = new File(cfg.dir, "nextcloud-config.properties");
        this.httpClient = createHttpClient();
        loadLocalConfig();
        if (startScheduler) this.syncExecutor.scheduleWithFixedDelay(this::tickQuietly, 5, 5, TimeUnit.SECONDS);
    }

    private static HttpClient createHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    private synchronized void loadLocalConfig() {
        Properties p = new Properties();
        if (configFile.isFile()) {
            try (InputStream in = Files.newInputStream(configFile.toPath())) {
                p.load(in);
            } catch (Exception ignored) {}
        }
        this.enabled = "true".equalsIgnoreCase(p.getProperty("enabled", "false"));
        this.url = p.getProperty("url", "https://servidor.tail074692.ts.net:8445/");
        this.username = p.getProperty("username", "");
        this.appPassword = p.getProperty("appPassword", "");
        this.remoteFolder = p.getProperty("remoteFolder", "/");
        this.resolvedDavRoot = null;
        this.authHeader = makeAuthHeader(username, appPassword);
    }

    private synchronized void saveLocalConfig() {
        Properties p = new Properties();
        p.setProperty("enabled", String.valueOf(enabled));
        p.setProperty("url", url != null ? url : "");
        p.setProperty("username", username != null ? username : "");
        p.setProperty("appPassword", appPassword != null ? appPassword : "");
        p.setProperty("remoteFolder", remoteFolder != null ? remoteFolder : "/");
        try (OutputStream out = Files.newOutputStream(configFile.toPath())) {
            p.store(out, "Configuracao Nextcloud do Agente");
        } catch (Exception ignored) {}
    }

    private static String makeAuthHeader(String user, String pass) {
        if (user == null || user.isBlank() || pass == null || pass.isBlank()) return null;
        String raw = user.trim() + ":" + pass.trim();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    synchronized void updateConfig(Map<String, Object> m) {
        if (m == null) return;
        boolean prevEnabled = this.enabled;
        String prevUser = this.username;
        String prevPass = this.appPassword;
        String prevUrl = this.url;
        String prevFolder = this.remoteFolder;

        if (m.containsKey("enabled")) this.enabled = Boolean.TRUE.equals(m.get("enabled"));
        if (m.containsKey("url") && m.get("url") instanceof String) this.url = (String) m.get("url");
        if (m.containsKey("username") && m.get("username") instanceof String) this.username = (String) m.get("username");
        if (m.containsKey("appPassword") && m.get("appPassword") instanceof String) {
            String p = (String) m.get("appPassword");
            if (!p.isBlank()) this.appPassword = p;
        }
        if (m.containsKey("remoteFolder") && m.get("remoteFolder") instanceof String) this.remoteFolder = (String) m.get("remoteFolder");

        this.authHeader = makeAuthHeader(this.username, this.appPassword);
        this.resolvedDavRoot = null;
        saveLocalConfig();

        boolean changed = (prevEnabled != this.enabled) || !Objects.equals(prevUser, this.username)
                || !Objects.equals(prevPass, this.appPassword) || !Objects.equals(prevUrl, this.url)
                || !Objects.equals(prevFolder, this.remoteFolder);
        if (changed) {
            this.forceSyncRequested = true;
            this.lastRemoteEtag = "";
            this.lastRemoteSnapshot = null;
            if (this.enabled && !prevEnabled) {
                log.accept("Sincronizacao direta com o Nextcloud ativada.");
            } else if (!this.enabled && prevEnabled) {
                log.accept("Sincronizacao direta com o Nextcloud desativada.");
            }
        }
        if (enabled) {
            requestSync();
        }
    }

    void requestSync() {
        requestSync(null);
    }

    void requestSync(String requestId) {
        if (requestId != null && !requestId.isBlank()) pendingRequestId = requestId;
        this.forceSyncRequested = true;
        if (!closed.get()) {
            syncExecutor.execute(this::tickQuietly);
        }
    }

    /** Encerramento imediato sem nenhum bloqueio de monitor. */
    void close() {
        closed.set(true);
        closeWatcher();
        syncExecutor.shutdownNow();
    }

    private void tickQuietly() {
        if (closed.get()) return;
        if (!syncing.compareAndSet(false, true)) {
            return; // Ja ha um tick em execucao, nao acumula e nao bloqueia
        }
        try {
            tick();
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            log.accept("Falha inesperada na sincronizacao Nextcloud: " + msg);
            reportStatus(false, 0, msg, pendingRequestId);
        } finally {
            syncing.set(false);
        }
    }

    private void tick() {
        if (closed.get()) return;
        if (!enabled || authHeader == null || username.isBlank() || appPassword.isBlank()) {
            closeWatcher();
            wasAccessed = false;
            if (pendingRequestId != null) {
                String requestId = pendingRequestId;
                pendingRequestId = null;
                reportStatus(false, 0, "Nextcloud nao configurado ou desativado", requestId);
            }
            return;
        }

        String requestId = null;
        try {
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("pasta recebimentos indisponivel");

            long now = System.currentTimeMillis();
            boolean accessed = isMachineAccessed.getAsBoolean();
            // Um pedido com id vem do botao "Sincronizar agora". Ele deve ser
            // atendido mesmo durante o acesso remoto; apenas as verificacoes
            // automaticas aguardam o video terminar.
            boolean manualRequest = pendingRequestId != null;
            if (accessed && !manualRequest) {
                wasAccessed = true;
                closeWatcher();
                return;
            }
            if (wasAccessed) {
                wasAccessed = false;
                forceSyncRequested = true;
                localChangeDetectedAt = 0;
            }

            boolean dueForce = forceSyncRequested;
            boolean dueIdle = lastFullSyncAt == 0 || (now - lastFullSyncAt >= IDLE_SYNC_INTERVAL_MS);

            if (!(dueForce || dueIdle)) {
                return;
            }

            String davRoot = resolveDavRoot();

            forceSyncRequested = false;
            localChangeDetectedAt = 0;
            lastRemoteCheckAt = now;

            requestId = pendingRequestId;
            pendingRequestId = null;
            reportStatus(true, 0, null, requestId);

            awaitVideoIdle(requestId, manualRequest);
            RemoteSnapshot remoteSnap = listAllRemoteFiles(davRoot);
            this.lastRemoteSnapshot = remoteSnap;
            if (!remoteSnap.rootEtag.isEmpty()) {
                this.lastRemoteEtag = remoteSnap.rootEtag;
            }

            awaitVideoIdle(requestId, manualRequest);
            LocalSnapshot localSnap = scanLocal();
            Properties oldState = loadState();
            String scope = syncScope();
            if (!scope.equals(oldState.getProperty(SCOPE_KEY))) oldState.clear();
            Set<String> oldDirs = extractOldDirs(oldState);

            Map<String, Entry> local = localSnap.files;
            Map<String, Entry> remote = new HashMap<>(remoteSnap.files);
            Set<String> localDirs = new HashSet<>(localSnap.dirs);
            Set<String> remoteDirs = new HashSet<>(remoteSnap.dirs);

            // Identifica pastas excluidas em cada lado
            Set<String> locallyDeletedDirs = new HashSet<>();
            for (String d : oldDirs) {
                if (!localDirs.contains(d) && !hasChildPath(local.keySet(), d)) {
                    locallyDeletedDirs.add(d);
                }
            }
            Set<String> remotelyDeletedDirs = new HashSet<>();
            for (String d : oldDirs) {
                if (!remoteDirs.contains(d) && !hasChildPath(remote.keySet(), d)) {
                    remotelyDeletedDirs.add(d);
                }
            }

            int changes = 0;
            boolean hadFailures = false;
            String firstFailure = null;
            Set<String> failedPaths = new HashSet<>();
            Set<String> failedDirs = new HashSet<>();
            Map<String, Entry> all = new HashMap<>(local);
            remote.forEach(all::putIfAbsent);

            Set<String> knownRemoteDirs = new HashSet<>(remoteDirs);

            List<String> allPaths = new ArrayList<>(all.keySet());
            allPaths.sort(Comparator.comparingInt((String p) -> p.split("/").length)
                    .thenComparingLong(p -> all.get(p).size).thenComparing(Comparator.naturalOrder()));
            int processed = 0;
            for (String path : allPaths) {
                if (closed.get()) throw new IOException("Sincronizacao interrompida");
                awaitVideoIdle(requestId, manualRequest);
                if (!safe(path)) continue;
                try {
                    Entry l = local.get(path);
                    Entry r = remote.get(path);
                    String saved = oldState.getProperty(path, "|");
                    String[] sides = saved.split("\\|", -1);
                    String sl = sides.length > 0 ? sides[0] : "", sr = sides.length > 1 ? sides[1] : "";
                    boolean wasInOld = !sl.isEmpty() && !sr.isEmpty();

                    if (l == null && r != null) {
                        if (wasInOld && r.signature().equals(sr)) {
                            // Exclusao local apenas se o remoto nao mudou desde o ultimo espelho.
                            deleteRemotePath(davRoot, path);
                            remote.remove(path);
                            changes++;
                            reportActivity("delete-remote", path, null, requestId);
                        } else {
                            // Arquivo novo ou alterado em outra maquina: restaura localmente.
                            downloadFile(davRoot, path, r);
                            addParentDirs(path, localDirs);
                            changes++;
                            reportActivity("download", path, null, requestId);
                        }
                    } else if (l != null && r == null) {
                        if (wasInOld && l.signature().equals(sl)) {
                            // Exclusao remota apenas se o local nao mudou desde o ultimo espelho.
                            forceDelete(file(path));
                            local.remove(path);
                            changes++;
                            reportActivity("delete-local", path, null, requestId);
                        } else {
                            // Arquivo novo ou alterado localmente: restaura no Nextcloud.
                            uploadFile(davRoot, path, file(path), knownRemoteDirs);
                            addParentDirs(path, remoteDirs);
                            changes++;
                            reportActivity("upload", path, null, requestId);
                        }
                    } else if (l != null) {
                        boolean lc = !l.signature().equals(sl);
                        boolean rc = !r.signature().equals(sr);
                        if ((!sl.isEmpty() && !sr.isEmpty() && !lc && !rc)
                                || ((sl.isEmpty() || sr.isEmpty()) && l.size == r.size && Math.abs(l.mtime - r.mtime) < 2000)) {
                            // Ambos inalterados
                        } else if (lc && !rc) {
                            uploadFile(davRoot, path, file(path), knownRemoteDirs);
                            changes++;
                            reportActivity("upload", path, null, requestId);
                        } else if (rc && !lc) {
                            downloadFile(davRoot, path, r);
                            changes++;
                            reportActivity("download", path, null, requestId);
                        } else {
                            // Ambos mudaram: conserva a versao perdedora como copia de conflito.
                            String conflict = path + ".conflict-" + Long.toHexString(System.nanoTime());
                            if (l.mtime >= r.mtime) {
                                downloadFile(davRoot, path, r, conflict);
                                uploadFile(davRoot, conflict, file(conflict), knownRemoteDirs);
                                uploadFile(davRoot, path, file(path), knownRemoteDirs);
                            } else {
                                Files.copy(file(path).toPath(), file(conflict).toPath());
                                uploadFile(davRoot, conflict, file(conflict), knownRemoteDirs);
                                downloadFile(davRoot, path, r);
                            }
                            changes += 2;
                            reportActivity("conflict", path, conflict, requestId);
                        }
                    }
                } catch (Exception e) {
                    hadFailures = true;
                    failedPaths.add(path);
                    if (firstFailure == null) firstFailure = "arquivo " + path + ": " + e.getMessage();
                    reportActivity("error", path, e.getMessage(), requestId);
                    log.accept("Aviso ao sincronizar arquivo " + path + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
                processed++;
                if (processed % 100 == 0 || processed == allPaths.size()) reportProgress(processed, allPaths.size(), requestId);
            }

            // Reconciliacao de pastas:
            // 1. Exclusoes primeiro (da mais profunda para a mais rasa)
            List<String> allDirsDesc = new ArrayList<>();
            Set<String> unionDirs = new HashSet<>(localDirs);
            unionDirs.addAll(remoteDirs);
            unionDirs.addAll(oldDirs);
            allDirsDesc.addAll(unionDirs);
            allDirsDesc.sort(Comparator.comparingInt((String p) -> p.split("/").length).reversed().thenComparing(Comparator.reverseOrder()));

            for (String dirPath : allDirsDesc) {
                if (closed.get()) throw new IOException("Sincronizacao interrompida");
                awaitVideoIdle(requestId, manualRequest);
                if (!safe(dirPath)) continue;
                try {
                    boolean inLocal = localDirs.contains(dirPath);
                    boolean inRemote = remoteDirs.contains(dirPath);

                    if (locallyDeletedDirs.contains(dirPath) || (!inLocal && inRemote && oldDirs.contains(dirPath))) {
                        boolean hasRemoteChild = hasChildPath(remote.keySet(), dirPath) || hasChildDir(remoteDirs, dirPath);
                        if (!hasRemoteChild) {
                            deleteRemotePath(davRoot, dirPath);
                            remoteDirs.remove(dirPath);
                            changes++;
                            reportActivity("delete-remote", dirPath, null, requestId);
                        }
                    } else if (remotelyDeletedDirs.contains(dirPath) || (inLocal && !inRemote && oldDirs.contains(dirPath))) {
                        boolean hasLocalChild = hasChildPath(local.keySet(), dirPath) || hasChildDir(localDirs, dirPath);
                        if (!hasLocalChild) {
                            deleteDirRecursive(file(dirPath));
                            localDirs.remove(dirPath);
                            changes++;
                            reportActivity("delete-local", dirPath, null, requestId);
                        }
                    }
                } catch (Exception e) {
                    hadFailures = true;
                    failedDirs.add(dirPath);
                    if (firstFailure == null) firstFailure = "pasta " + dirPath + ": " + e.getMessage();
                    reportActivity("error", dirPath, e.getMessage(), requestId);
                    log.accept("Aviso ao excluir pasta " + dirPath + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }

            // 2. Criacoes de pastas novas (da mais rasa para a mais profunda)
            List<String> allDirsAsc = new ArrayList<>(unionDirs);
            allDirsAsc.sort(Comparator.comparingInt((String p) -> p.split("/").length).thenComparing(Comparator.naturalOrder()));
            for (String dirPath : allDirsAsc) {
                if (closed.get()) throw new IOException("Sincronizacao interrompida");
                awaitVideoIdle(requestId, manualRequest);
                if (!safe(dirPath)) continue;
                if (locallyDeletedDirs.contains(dirPath) || remotelyDeletedDirs.contains(dirPath)) continue;
                try {
                    boolean inLocal = localDirs.contains(dirPath);
                    boolean inRemote = remoteDirs.contains(dirPath);
                    if (!inLocal && inRemote && !oldDirs.contains(dirPath)) {
                        File d = file(dirPath);
                        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Falha ao criar pasta local: " + dirPath);
                        localDirs.add(dirPath);
                        changes++;
                        reportActivity("mkdir-local", dirPath, null, requestId);
                    } else if (inLocal && !inRemote && !oldDirs.contains(dirPath)) {
                        createRemoteDir(davRoot, dirPath, knownRemoteDirs);
                        remoteDirs.add(dirPath);
                        changes++;
                        reportActivity("mkdir-remote", dirPath, null, requestId);
                    }
                } catch (Exception e) {
                    hadFailures = true;
                    failedDirs.add(dirPath);
                    if (firstFailure == null) firstFailure = "pasta " + dirPath + ": " + e.getMessage();
                    reportActivity("error", dirPath, e.getMessage(), requestId);
                    log.accept("Aviso ao criar pasta " + dirPath + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }

            if (closed.get()) throw new IOException("Sincronizacao interrompida");
            awaitVideoIdle(requestId, manualRequest);
            // Atualiza somente arquivos e pastas confirmados nos dois lados.
            LocalSnapshot finalLocal = scanLocal();
            RemoteSnapshot finalRemote = listAllRemoteFiles(davRoot);
            if (!finalLocal.files.keySet().equals(finalRemote.files.keySet())) {
                hadFailures = true;
            }
            Properties next = new Properties();
            next.setProperty(SCOPE_KEY, scope);
            for (Map.Entry<String, Entry> e : finalLocal.files.entrySet()) {
                Entry r = finalRemote.files.get(e.getKey());
                if (r != null && !failedPaths.contains(e.getKey())) {
                    next.setProperty(e.getKey(), e.getValue().signature() + "|" + r.signature());
                }
            }
            Set<String> finalDirs = new HashSet<>(finalLocal.dirs);
            finalDirs.retainAll(finalRemote.dirs);
            for (String d : finalDirs) {
                if (safe(d) && !failedDirs.contains(d)) next.setProperty(DIR_KEY_PREFIX + d, "1|1");
            }

            if (changes > 0 || !next.equals(oldState)) {
                try (OutputStream out = Files.newOutputStream(stateFile.toPath())) {
                    next.store(out, "Estado da sincronizacao Nextcloud direta");
                }
            }

            if (hadFailures) throw new IOException("Sincronizacao parcial: "
                    + (firstFailure != null ? firstFailure : "arquivos locais e remotos ainda divergem"));

            lastFullSyncAt = now;
            drainWatcherQuietly();
            reportStatus(false, changes, null, requestId);
            if (changes > 0) {
                log.accept("Nextcloud sincronizado diretamente: " + changes + " alteracao(oes).");
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            forceSyncRequested = true;
            if (requestId == null) {
                requestId = pendingRequestId;
                pendingRequestId = null;
            }
            reportStatus(false, 0, msg, requestId);
            log.accept("Falha na sincronizacao com Nextcloud: " + msg);
        }
    }

    private void reportStatus(boolean syncing, int changes, String error, String requestId) {
        if (statusReporter == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("syncing", syncing);
        if (!syncing && error == null) data.put("lastSyncAt", System.currentTimeMillis());
        data.put("lastChanges", changes);
        if (requestId != null) data.put("requestId", requestId);
        if (error != null) data.put("error", error);
        statusReporter.accept("nextcloud-status", data);
    }

    private void reportProgress(int done, int total, String requestId) {
        if (statusReporter == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("syncing", true);
        data.put("processedFiles", done);
        data.put("totalFiles", total);
        if (requestId != null) data.put("requestId", requestId);
        statusReporter.accept("nextcloud-status", data);
    }

    private void reportActivity(String action, String path, String detail, String requestId) {
        if (statusReporter == null) return;
        Map<String, Object> data = new HashMap<>();
        data.put("action", action);
        data.put("path", path);
        if (detail != null) data.put("detail", detail);
        if (requestId != null) data.put("requestId", requestId);
        statusReporter.accept("nextcloud-activity", data);
    }

    /** Aguarda o fim do video somente para a sincronizacao automatica. */
    private void awaitVideoIdle(String requestId, boolean manualRequest) throws InterruptedException, IOException {
        if (manualRequest) return;
        boolean paused = false;
        while (isMachineAccessed.getAsBoolean()) {
            if (closed.get()) throw new IOException("Sincronizacao interrompida");
            if (!paused) {
                paused = true;
                reportActivity("pause", "", "Acesso remoto ativo; aguardando para preservar o video.", requestId);
            }
            Thread.sleep(500);
        }
        if (paused) reportActivity("resume", "", "Acesso remoto encerrado; sincronizacao retomada.", requestId);
    }

    private String resolveDavRoot() throws Exception {
        if (resolvedDavRoot != null) return resolvedDavRoot;
        if (username.isBlank() || appPassword.isBlank()) throw new IOException("Credenciais Nextcloud ausentes");
        URI u = URI.create(url);
        String host = u.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equalsIgnoreCase(u.getScheme()) && !("http".equalsIgnoreCase(u.getScheme()) && loopback)) {
            throw new IOException("Nextcloud exige HTTPS com certificado valido");
        }
        String origin = u.getScheme() + "://" + u.getAuthority();
        String path = u.getPath() != null ? u.getPath().replaceAll("/+$", "") : "";
        int davIdx = path.indexOf("/remote.php/");
        String prefix = davIdx >= 0 ? path.substring(0, davIdx) : path;

        String encodedUser = URLEncoder.encode(username, StandardCharsets.UTF_8).replace("+", "%20");
        String cand1 = origin + prefix + "/remote.php/dav/files/" + encodedUser;
        String cand2 = origin + prefix + "/remote.php/webdav";

        String[] candidates = new String[]{ cand1, cand2 };
        int lastStatus = 0;
        for (String base : candidates) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/"))
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                    .header("Authorization", authHeader)
                    .header("Depth", "0")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            lastStatus = resp.statusCode();
            if (resp.statusCode() == 207 || resp.statusCode() == 200) {
                String folder = normalizeFolder(remoteFolder);
                if (!folder.isEmpty()) {
                    ensureFolderSegments(base, folder);
                    resolvedDavRoot = base + "/" + encodeDavPath(folder);
                } else {
                    resolvedDavRoot = base;
                }
                return resolvedDavRoot;
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                throw new IOException("Credenciais do Nextcloud recusadas (HTTP " + resp.statusCode() + "). Verifique usuario e senha.");
            }
        }
        throw new IOException("Nao foi possivel acessar o WebDAV do Nextcloud (HTTP " + lastStatus + ")");
    }

    private void ensureFolderSegments(String baseRoot, String folderPath) throws Exception {
        String[] segments = folderPath.split("/");
        StringBuilder current = new StringBuilder(baseRoot);
        for (String seg : segments) {
            if (seg.isBlank()) continue;
            current.append("/").append(URLEncoder.encode(seg, StandardCharsets.UTF_8).replace("+", "%20"));
            HttpRequest check = HttpRequest.newBuilder(URI.create(current + "/"))
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                    .header("Authorization", authHeader)
                    .header("Depth", "0")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(check, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                HttpRequest mk = HttpRequest.newBuilder(URI.create(current + "/"))
                        .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                        .header("Authorization", authHeader)
                        .timeout(Duration.ofSeconds(10))
                        .build();
                HttpResponse<String> mkResp = httpClient.send(mk, HttpResponse.BodyHandlers.ofString());
                if (mkResp.statusCode() != 201 && mkResp.statusCode() != 405) {
                    throw new IOException("Falha ao criar pasta " + folderPath + " no Nextcloud (HTTP " + mkResp.statusCode() + ")");
                }
            }
        }
    }

    private void ensureParentDirs(String davRoot, String relPath, Set<String> knownDirs) throws Exception {
        String[] parts = relPath.replace('\\', '/').split("/");
        if (parts.length <= 1) return;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isBlank()) continue;
            if (current.length() > 0) current.append("/");
            current.append(parts[i]);
            String dirPath = current.toString();
            if (knownDirs.contains(dirPath)) continue;
            createRemoteDir(davRoot, dirPath, knownDirs);
        }
    }

    private void createRemoteDir(String davRoot, String relDir, Set<String> knownDirs) throws Exception {
        String targetUrl = davRoot + "/" + encodeDavPath(relDir) + "/";
        HttpRequest mk = HttpRequest.newBuilder(URI.create(targetUrl))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody())
                .header("Authorization", authHeader)
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = httpClient.send(mk, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 201 || resp.statusCode() == 405 || resp.statusCode() == 200) {
            knownDirs.add(relDir);
        } else {
            HttpRequest check = HttpRequest.newBuilder(URI.create(targetUrl))
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                    .header("Authorization", authHeader)
                    .header("Depth", "0")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> cResp = httpClient.send(check, HttpResponse.BodyHandlers.ofString());
            if (cResp.statusCode() == 207 || cResp.statusCode() == 200) {
                knownDirs.add(relDir);
            } else {
                throw new IOException("Falha ao criar pasta remota " + relDir + " (HTTP " + resp.statusCode() + ")");
            }
        }
    }

    private String getRootEtag(String base) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/"))
                    .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                    .header("Authorization", authHeader)
                    .header("Depth", "0")
                    .header("Content-Type", "application/xml; charset=utf-8")
                    .timeout(Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 207 && resp.statusCode() != 200) return "";
            Matcher m = ETAG_PAT.matcher(resp.body());
            return m.find() ? cleanEtag(m.group(1)) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private RemoteSnapshot listAllRemoteFiles(String base) throws Exception {
        Map<String, Entry> files = new HashMap<>();
        Set<String> dirs = new HashSet<>();
        String[] rootEtagHolder = new String[]{ "" };
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/"))
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                .header("Authorization", authHeader)
                .header("Depth", "infinity")
                .header("Content-Type", "application/xml; charset=utf-8")
                .timeout(Duration.ofMinutes(2))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 207 || resp.statusCode() == 200) {
            String body = resp.body();
            if (!RESP_PAT.matcher(body).find() || !MULTISTATUS_END_PAT.matcher(body).find()) {
                throw new IOException("Resposta WebDAV incompleta ou invalida");
            }
            Matcher etag = ETAG_PAT.matcher(body);
            if (etag.find()) rootEtagHolder[0] = cleanEtag(etag.group(1));
            List<DavItem> items = parsePropfindXml(body, base, "");
            boolean nested = false;
            for (DavItem item : items) {
                if (closed.get()) throw new IOException("Sincronizacao interrompida");
                if (!safe(item.path)) continue;
                if (item.path.contains("/")) nested = true;
                if (item.isDir) {
                    dirs.add(item.path);
                } else {
                    files.put(item.path, new Entry(item.size, item.mtime));
                    addParentDirs(item.path, dirs);
                }
            }
            if (!dirs.isEmpty() && !nested) {
                // Alguns servidores ignoram Depth: infinity e retornam so a raiz.
                files.clear();
                dirs.clear();
                visitRemote(base, "", 0, files, dirs, rootEtagHolder);
            }
            return new RemoteSnapshot(files, dirs, rootEtagHolder[0]);
        }
        if (resp.statusCode() != 403 && resp.statusCode() != 405 && resp.statusCode() != 501) {
            throw new IOException("Falha ao listar Nextcloud (HTTP " + resp.statusCode() + ")");
        }
        // Servidores WebDAV que nao aceitam Depth: infinity usam a varredura segura por pasta.
        visitRemote(base, "", 0, files, dirs, rootEtagHolder);
        return new RemoteSnapshot(files, dirs, rootEtagHolder[0]);
    }

    private void visitRemote(String base, String relDir, int depth, Map<String, Entry> files, Set<String> dirs, String[] rootEtagHolder) throws Exception {
        if (closed.get()) throw new IOException("Sincronizacao interrompida");
        if (depth > 20) throw new IOException("Pasta Nextcloud excede a profundidade suportada");
        String url = relDir.isEmpty() ? base + "/" : base + "/" + encodeDavPath(relDir) + "/";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY))
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 207 && resp.statusCode() != 200) {
            throw new IOException("Falha ao listar Nextcloud (HTTP " + resp.statusCode() + ")");
        }
        String body = resp.body();
        if (!RESP_PAT.matcher(body).find() || !MULTISTATUS_END_PAT.matcher(body).find()) {
            throw new IOException("Resposta WebDAV incompleta ou invalida");
        }
        if (depth == 0) {
            Matcher em = ETAG_PAT.matcher(body);
            if (em.find()) rootEtagHolder[0] = cleanEtag(em.group(1));
        }
        List<DavItem> items = parsePropfindXml(body, base, relDir);
        for (DavItem item : items) {
            if (closed.get()) throw new IOException("Sincronizacao interrompida");
            if (!safe(item.path)) continue;
            if (item.isDir) {
                dirs.add(item.path);
                visitRemote(base, item.path, depth + 1, files, dirs, rootEtagHolder);
            } else {
                files.put(item.path, new Entry(item.size, item.mtime));
                addParentDirs(item.path, dirs);
            }
        }
    }

    private static List<DavItem> parsePropfindXml(String xml, String davRoot, String relDir) {
        List<DavItem> list = new ArrayList<>();
        Matcher rm = RESP_PAT.matcher(xml);

        // Decodifica a base para que caracteres codificados como %40 e literais como @ fiquem no mesmo formato
        String rawBase = davRoot.replaceAll("^https?://[^/]+", "");
        if (!relDir.isEmpty()) rawBase += "/" + encodeDavPath(relDir);
        String expectedPrefix = decodeHref(rawBase).replaceAll("/+$", "");

        while (rm.find()) {
            String block = rm.group(1);
            Matcher hm = HREF_PAT.matcher(block);
            if (!hm.find()) continue;
            String rawHref = decodeHref(hm.group(1).trim());
            String normHref = rawHref.replaceAll("/+$", "");
            if (normHref.equals(expectedPrefix)) continue;
            if (!expectedPrefix.isEmpty() && !normHref.startsWith(expectedPrefix + "/")) continue;

            String sub = expectedPrefix.isEmpty() ? normHref.replaceFirst("^/", "") : normHref.substring(expectedPrefix.length() + 1);
            String itemRel = relDir.isEmpty() ? sub : relDir + "/" + sub;
            if (itemRel.isBlank()) continue;

            boolean isDir = COL_PAT.matcher(block).find();
            long size = 0;
            Matcher lm = LEN_PAT.matcher(block);
            if (lm.find()) {
                try { size = Long.parseLong(lm.group(1).trim()); } catch (Exception ignored) {}
            }
            long mtime = 0;
            Matcher mm = MOD_PAT.matcher(block);
            if (mm.find()) {
                mtime = parseHttpDate(mm.group(1).trim());
            }
            String etag = "";
            Matcher em = ETAG_PAT.matcher(block);
            if (em.find()) etag = cleanEtag(em.group(1));

            list.add(new DavItem(itemRel, isDir, size, mtime, etag));
        }
        return list;
    }

    private void downloadFile(String davRoot, String relPath, Entry meta) throws Exception {
        downloadFile(davRoot, relPath, meta, relPath);
    }

    private void downloadFile(String davRoot, String relPath, Entry meta, String targetRelPath) throws Exception {
        String targetUrl = davRoot + "/" + encodeDavPath(relPath);
        HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl))
                .GET()
                .header("Authorization", authHeader)
                .timeout(Duration.ofMinutes(15))
                .build();
        HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("download HTTP " + resp.statusCode());

        File target = file(targetRelPath);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Falha ao criar pasta local: " + parent.getAbsolutePath());

        if (target.exists()) {
            try { target.setWritable(true); } catch (Exception ignored) {}
            try { Files.setAttribute(target.toPath(), "dos:readonly", false); } catch (Exception ignored) {}
        }

        File temp = new File(parent, ".transacao-dl-" + Long.toHexString(System.nanoTime()) + ".tmp");
        try {
            try (InputStream in = resp.body()) {
                Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (meta.mtime > 0) {
                try { Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(meta.mtime)); } catch (Exception ignored) {}
            }
        } finally {
            try { Files.deleteIfExists(temp.toPath()); } catch (Exception ignored) {}
        }
    }

    private void uploadFile(String davRoot, String relPath, File localFile, Set<String> knownDirs) throws Exception {
        ensureParentDirs(davRoot, relPath, knownDirs);
        String targetUrl = davRoot + "/" + encodeDavPath(relPath);
        long mtimeSec = Math.max(1, localFile.lastModified() / 1000L);
        HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl))
                .PUT(HttpRequest.BodyPublishers.ofFile(localFile.toPath()))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/octet-stream")
                .header("X-OC-Mtime", String.valueOf(mtimeSec))
                .timeout(Duration.ofMinutes(15))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201 && resp.statusCode() != 204) {
            throw new IOException("Falha no upload para Nextcloud (HTTP " + resp.statusCode() + ")");
        }
    }

    private void deleteRemotePath(String davRoot, String relPath) throws Exception {
            String targetUrl = davRoot + "/" + encodeDavPath(relPath);
            HttpRequest req = HttpRequest.newBuilder(URI.create(targetUrl))
                    .DELETE()
                    .header("Authorization", authHeader)
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 204 && resp.statusCode() != 404 && resp.statusCode() != 200) {
                throw new IOException("Falha ao excluir no Nextcloud: " + relPath + " (HTTP " + resp.statusCode() + ")");
            }
    }

    private LocalSnapshot scanLocal() throws IOException {
        Map<String, Entry> files = new HashMap<>();
        Set<String> dirs = new HashSet<>();
        if (!root.isDirectory()) throw new IOException("Pasta recebimentos indisponivel");

        Path rootPath = root.toPath();
            Files.walkFileTree(rootPath, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (rootPath.equals(dir)) return java.nio.file.FileVisitResult.CONTINUE;
                    String rel = rootPath.relativize(dir).toString().replace('\\', '/');
                    if (rel.startsWith(".transacao-") || rel.contains("/.transacao-")) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    if (safe(rel)) dirs.add(rel);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String rel = rootPath.relativize(f).toString().replace('\\', '/');
                    if (rel.startsWith(".transacao-") || rel.contains("/.transacao-")) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    if (safe(rel)) {
                        files.put(rel, new Entry(attrs.size(), attrs.lastModifiedTime().toMillis()));
                        addParentDirs(rel, dirs);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path f, IOException exc) throws IOException {
                    throw exc;
                }
            });

        return new LocalSnapshot(files, dirs);
    }

    private void ensureWatcherOpen() {
        try {
            if (watcher == null) {
                watcher = FileSystems.getDefault().newWatchService();
                WatchEvent.Kind<?>[] kinds = new WatchEvent.Kind<?>[]{
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                };
                root.toPath().register(watcher, kinds);
            }
        } catch (Exception ignored) {}
    }

    private boolean pollWatcherEvents() {
        if (watcher == null) return false;
        boolean changed = false;
        WatchKey key;
        while ((key = watcher.poll()) != null) {
            for (WatchEvent<?> event : key.pollEvents()) {
                Object ctx = event.context();
                if (ctx instanceof Path) {
                    String name = ((Path) ctx).getFileName().toString();
                    if (!name.startsWith(".transacao-")) {
                        changed = true;
                    }
                } else {
                    changed = true;
                }
            }
            key.reset();
        }
        return changed;
    }

    private void drainWatcherQuietly() {
        if (watcher == null) return;
        WatchKey key;
        while ((key = watcher.poll()) != null) {
            key.pollEvents();
            key.reset();
        }
    }

    private void closeWatcher() {
        if (watcher != null) {
            try { watcher.close(); } catch (Exception ignored) {}
            watcher = null;
        }
    }

    private File file(String path) {
        return new File(root, path.replace('/', File.separatorChar));
    }

    private Properties loadState() {
        Properties p = new Properties();
        if (stateFile.isFile()) {
            try (InputStream in = Files.newInputStream(stateFile.toPath())) {
                p.load(in);
            } catch (Exception ignored) {}
        }
        return p;
    }

    private String syncScope() throws Exception {
        String identity = url.replaceAll("/+$", "") + "\n" + username + "\n" + remoteFolder;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static Set<String> extractOldDirs(Properties old) {
        Set<String> s = new HashSet<>();
        for (String k : old.stringPropertyNames()) {
            if (k.startsWith(DIR_KEY_PREFIX)) {
                String d = k.substring(DIR_KEY_PREFIX.length());
                if (safe(d) && "1|1".equals(old.getProperty(k))) s.add(d);
            }
        }
        return s;
    }

    private static boolean isUnderAny(String path, Set<String> dirs) {
        for (String d : dirs) {
            if (path.startsWith(d + "/")) return true;
        }
        return false;
    }

    private static boolean hasChildPath(Set<String> paths, String dir) {
        String pfx = dir + "/";
        for (String p : paths) {
            if (p.startsWith(pfx)) return true;
        }
        return false;
    }

    private static boolean hasChildDir(Set<String> dirs, String parentDir) {
        String pfx = parentDir + "/";
        for (String d : dirs) {
            if (d.startsWith(pfx)) return true;
        }
        return false;
    }

    private static void addParentDirs(String rel, Set<String> dirs) {
        int idx = rel.lastIndexOf('/');
        while (idx > 0) {
            String parent = rel.substring(0, idx);
            if (safe(parent)) dirs.add(parent);
            idx = parent.lastIndexOf('/');
        }
    }

    private static boolean safe(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.length() > 500) return false;
        String[] parts = path.replace('\\', '/').split("/");
        for (String p : parts) {
            if (p.isBlank() || p.equals(".") || p.equals("..") || p.startsWith(".transacao-")) return false;
            for (char c : p.toCharArray()) {
                if (c < 32 || c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?' || c == '*') return false;
            }
        }
        return true;
    }

    private static void forceDelete(File f) throws IOException {
        if (f == null || !f.exists()) return;
        try { f.setWritable(true); } catch (Exception ignored) {}
        try { Files.setAttribute(f.toPath(), "dos:readonly", false); } catch (Exception ignored) {}
        Files.deleteIfExists(f.toPath());
    }

    private static void deleteDirRecursive(File dir) throws IOException {
        if (dir == null || !dir.exists()) return;
        try { dir.setWritable(true); } catch (Exception ignored) {}
        try { Files.setAttribute(dir.toPath(), "dos:readonly", false); } catch (Exception ignored) {}
        Files.walkFileTree(dir.toPath(), new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                forceDelete(f.toFile());
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (exc != null) throw exc;
                forceDelete(d.toFile());
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static String encodeDavPath(String rel) {
        if (rel == null || rel.isBlank()) return "";
        String[] parts = rel.replace('\\', '/').split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append("/");
            sb.append(URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String decodeHref(String raw) {
        try {
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                raw = URI.create(raw).getPath();
            }
            String[] segments = raw.split("/");
            StringBuilder sb = new StringBuilder();
            for (String s : segments) {
                if (s.isEmpty()) continue;
                String dec;
                try { dec = URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { dec = s; }
                sb.append("/").append(dec);
            }
            return sb.toString();
        } catch (Exception e) {
            return raw;
        }
    }

    private static String cleanEtag(String raw) {
        if (raw == null) return "";
        return raw.trim().replace("\"", "").replace("W/", "").replace("'", "");
    }

    private static String normalizeFolder(String f) {
        if (f == null || f.isBlank()) return "";
        String s = f.replace('\\', '/').trim().replaceAll("^/+|/+$", "");
        return s;
    }

    private static long parseHttpDate(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            return sdf.parse(s.trim()).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static final class Entry {
        final long size;
        final long mtime;
        Entry(long size, long mtime) { this.size = size; this.mtime = mtime; }
        String signature() { return size + ":" + mtime; }
    }

    private static final class DavItem {
        final String path;
        final boolean isDir;
        final long size;
        final long mtime;
        final String etag;
        DavItem(String path, boolean isDir, long size, long mtime, String etag) {
            this.path = path;
            this.isDir = isDir;
            this.size = size;
            this.mtime = mtime;
            this.etag = etag;
        }
    }

    private static final class RemoteSnapshot {
        final Map<String, Entry> files;
        final Set<String> dirs;
        final String rootEtag;
        RemoteSnapshot(Map<String, Entry> files, Set<String> dirs, String rootEtag) {
            this.files = files;
            this.dirs = dirs;
            this.rootEtag = rootEtag != null ? rootEtag : "";
        }
    }

    private static final class LocalSnapshot {
        final Map<String, Entry> files;
        final Set<String> dirs;
        LocalSnapshot(Map<String, Entry> files, Set<String> dirs) {
            this.files = files;
            this.dirs = dirs;
        }
    }
}
