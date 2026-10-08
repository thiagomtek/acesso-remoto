package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

/** Sincroniza <usuario>/recebimentos com a pasta persistente do hub. Nomes e conteudo nao entram no log. */
final class SharedFolderSync {
    private final AgentConfig cfg;
    private final AgentSettings settings;
    private final File root;
    private final File stateFile;
    private final Consumer<String> log;
    private final HubHttp hubHttp;
    private boolean lastEnabled;

    SharedFolderSync(AgentConfig cfg, AgentSettings settings, HubHttp hubHttp, Consumer<String> log) {
        this.cfg = cfg; this.settings = settings; this.log = log;
        this.hubHttp = hubHttp;
        this.root = FileTransfer.receiptsDirectory(System.getenv("USERPROFILE"), System.getProperty("user.home"));
        this.stateFile = new File(cfg.dir, "shared-folder-state.properties");
    }

    void syncIfEnabled() {
        if (!settings.sharedFolderSync) {
            if (lastEnabled) log.accept("Sincronizacao da pasta compartilhada desativada.");
            lastEnabled = false;
            return;
        }
        if (!lastEnabled) log.accept("Sincronizacao da pasta compartilhada ativada.");
        lastEnabled = true;
        try {
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("pasta indisponivel");
            Map<String, Entry> remote = manifest();
            Map<String, Entry> local = scan();
            Properties old = loadState();
            int changes = 0;
            Map<String, Entry> all = new HashMap<>(); all.putAll(local); remote.forEach(all::putIfAbsent);
            for (String path : all.keySet()) {
                Entry l = local.get(path), r = remote.get(path);
                String saved = old.getProperty(path, "|");
                String[] sides = saved.split("\\|", -1);
                String sl = sides.length > 0 ? sides[0] : "", sr = sides.length > 1 ? sides[1] : "";
                if (l == null && r != null) {
                    if (!sl.isEmpty() && sr.equals(r.signature())) { removeRemote(path); changes++; } else { download(path, r); changes++; }
                } else if (l != null && r == null) {
                    if (!sr.isEmpty() && sl.equals(l.signature())) { Files.deleteIfExists(file(path).toPath()); changes++; } else { upload(path, file(path)); changes++; }
                } else if (l != null) {
                    boolean lc = !l.signature().equals(sl), rc = !r.signature().equals(sr);
                    if (lc && !rc) { upload(path, file(path)); changes++; }
                    else if (rc && !lc) { download(path, r); changes++; }
                    else if (lc && rc) { if (l.mtime >= r.mtime) upload(path, file(path)); else download(path, r); changes++; }
                }
            }
            pruneEmptyDirectories(root, true);
            // Registra o estado observado depois de reconciliar; uma queda no meio nao mascara alteracoes.
            Map<String, Entry> finalRemote = manifest(), finalLocal = scan();
            Properties next = new Properties();
            Map<String, Entry> keys = new HashMap<>(); keys.putAll(finalLocal); finalRemote.forEach(keys::putIfAbsent);
            for (String p : keys.keySet()) {
                Entry l = finalLocal.get(p), r = finalRemote.get(p);
                next.setProperty(p, (l == null ? "" : l.signature()) + "|" + (r == null ? "" : r.signature()));
            }
            try (java.io.OutputStream out = Files.newOutputStream(stateFile.toPath())) { next.store(out, "Estado da pasta compartilhada"); }
            if (changes > 0) log.accept("Pasta compartilhada sincronizada: " + changes + " alteracao(oes).");
        } catch (Exception e) { log.accept("Falha na sincronizacao da pasta compartilhada: " + e.getClass().getSimpleName()); }
    }

    private Map<String, Entry> manifest() throws Exception {
        String url = hubHttp.base() + "/shared/manifest?clientId=" + enc(cfg.clientId);
        HttpResponse<String> r = hubHttp.client().send(hubHttp.request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        Map<String, Entry> out = new HashMap<>(); Object raw = Json.parseObject(r.body()).get("files");
        if (raw instanceof List) for (Object o : (List<?>) raw) { Map<String,Object> m = Json.obj(o); String p = Json.str(m, "path"); if (safe(p)) out.put(p, new Entry((long) Json.num(m, "size", 0), (long) Json.num(m, "mtime", 0))); }
        return out;
    }
    private Map<String, Entry> scan() throws IOException { Map<String, Entry> out = new HashMap<>(); scan(root, root, out); return out; }
    private void scan(File base, File dir, Map<String, Entry> out) throws IOException {
        File[] files = dir.listFiles(); if (files == null) return;
        for (File f : files) { if (f.getName().startsWith(".transacao-")) continue; if (f.isDirectory()) scan(base, f, out); else if (f.isFile()) { String p = base.toPath().relativize(f.toPath()).toString().replace('\\', '/'); if (safe(p)) out.put(p, new Entry(f.length(), f.lastModified())); } }
    }
    private void upload(String path, File source) throws Exception {
        HttpRequest r = hubHttp.request(fileUrl(path)).PUT(HttpRequest.BodyPublishers.ofFile(source.toPath())).build();
        if (hubHttp.client().send(r, HttpResponse.BodyHandlers.discarding()).statusCode() != 201) throw new IOException("upload");
    }
    private void download(String path, Entry meta) throws Exception {
        HttpResponse<java.io.InputStream> r = hubHttp.client().send(hubHttp.request(fileUrl(path)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("download");
        File target = file(path); File parent = target.getParentFile(); if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("diretorio");
        File temp = new File(parent, ".transacao-download");
        try (java.io.InputStream in = r.body()) { Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); Files.setLastModifiedTime(target.toPath(), FileTime.fromMillis(meta.mtime));
    }
    private void removeRemote(String path) throws Exception { HttpRequest r = hubHttp.request(fileUrl(path)).DELETE().build(); if (hubHttp.client().send(r, HttpResponse.BodyHandlers.discarding()).statusCode() != 204) throw new IOException("delete"); }
    private String fileUrl(String path) { return hubHttp.base() + "/shared/file?clientId=" + enc(cfg.clientId) + "&path=" + enc(path); }
    private File file(String path) { return new File(root, path.replace('/', File.separatorChar)); }
    private Properties loadState() { Properties p = new Properties(); try (java.io.InputStream in = Files.newInputStream(stateFile.toPath())) { p.load(in); } catch (Exception ignored) { } return p; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static boolean safe(String p) {
        if (p == null || p.isBlank() || p.startsWith("/") || p.length() >= 500) return false;
        for (String part : p.replace('\\', '/').split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.matches(".*[<>:\"|?*\\x00-\\x1f].*")) return false;
        }
        return true;
    }
    private static boolean pruneEmptyDirectories(File dir, boolean keepRoot) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) if (child.isDirectory()) pruneEmptyDirectories(child, false);
        children = dir.listFiles();
        if (!keepRoot && children != null && children.length == 0) return dir.delete();
        return false;
    }
    private static final class Entry { final long size, mtime; Entry(long size, long mtime) { this.size=size; this.mtime=mtime; } String signature() { return size + "@" + mtime; } }
}
