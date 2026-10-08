package com.transacao.agent;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Transmissao de tela em Java puro, no estilo VNC (modo compativel), com as tecnicas que tornam o
 * VNC bom em enlace lento:
 * <ul>
 *   <li><b>CopyRect</b>: rolagem vertical e arrastes viram "copie esta regiao ja enviada" em vez de
 *       reenviar os pixels.</li>
 *   <li><b>JPEG em movimento, sem perdas quando para</b>: blocos que mudam sem parar vao em JPEG
 *       (leve); assim que ficam estaveis, sao reenviados em PNG sem perdas (refinamento). A imagem
 *       final e sempre nitida.</li>
 *   <li><b>Controle de fluxo</b>: o visualizador confirma (ack) cada quadro; no maximo poucos ficam
 *       em transito, entao a taxa acompanha a rede real e as mudancas acumulam em vez de enfileirar.
 *       A qualidade do JPEG sobe/desce conforme o atraso medido, e ha um teto de banda.</li>
 * </ul>
 * Mantem as regras do streamer original: captura nativa, adaptacao ao tamanho da tela de quem ve
 * (nunca acima da nativa) e captura imediata apos um comando remoto.
 *
 * Protocolo (big-endian, uma mensagem seguida da outra; um quadro termina em FRAME_END):
 * 12 tamanho da tela, 73 tamanho transmitido, 14 bloco PNG, 15 bloco JPEG, 16 CopyRect, 17 fim de quadro.
 */
public final class TileStreamer implements Runnable {

    public static final byte MSG_TILE_PNG = 14;
    public static final byte MSG_TILE_JPEG = 15;
    public static final byte MSG_COPY_RECT = 16;
    public static final byte MSG_FRAME_END = 17;

    private static final int TILE = 128;
    private static final int ACTIVE_INTERVAL_MS = 33;
    private static final int REFINE_INTERVAL_MS = 80;
    private static final int IDLE_INTERVAL_MS = 250;
    // Uma fila de tres quadros parecia aumentar FPS, mas em relay/WAN acumulava pixels no
    // WebSocket. A imagem ficava alguns segundos atrasada e o RTT/jitter dos comandos explodia.
    // Um unico quadro pendente mantem o modo compativel interativo: sempre se transmite o estado
    // mais recente, sem uma fila de video velha para atravessar antes do proximo clique.
    private static final int MAX_INFLIGHT = 1;
    private static final long ACK_TIMEOUT_MS = 2000;
    private static final long MAX_BYTES_PER_SEC = 1_800_000L; // ~14 Mbps: teto de banda desta transmissao
    private static final int HOT_THRESHOLD = 2;
    private static final int BULK_TILES = 12;
    private static final int PARALLEL_ENCODE_THRESHOLD = 6;
    private static final double MIN_DOWNSCALE_TRIGGER = 0.97;
    private static final float JPEG_MIN = 0.40f;
    private static final float JPEG_MAX = 0.88f;

    private final DataOutputStream out;
    private final Object writeLock;
    private final Robot robot;
    private final Rectangle screenRect;
    private final double dpiScale;
    private volatile boolean running = true;
    private volatile Consumer<String> errorListener;
    private final Object frameSignal = new Object();

    private volatile Dimension targetViewport;
    private Dimension appliedViewport;
    private boolean streamSizeAnnounced;
    private volatile Dimension streamSize;
    private volatile Consumer<Dimension> streamSizeListener;

    // estado entre quadros (so a thread de captura mexe)
    private int[] prev;
    private int[] pred;
    private int gridW;
    private int gridH;
    private int[] hot;
    private int[] stable;
    private boolean[] lossy;
    private int frameId;

    // controle de fluxo
    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<Integer, Long> sentAt = new ConcurrentHashMap<>();
    private final Object ackSignal = new Object();
    private volatile float jpegQuality = 0.75f;
    private volatile float jpegMax = JPEG_MAX;
    private volatile long maxBytesPerSec = MAX_BYTES_PER_SEC;
    private volatile int activeIntervalMs = ACTIVE_INTERVAL_MS;
    private volatile long profileMaxBytesPerSec = MAX_BYTES_PER_SEC;
    private volatile int profileActiveIntervalMs = ACTIVE_INTERVAL_MS;
    private long windowStart = System.currentTimeMillis();
    private long windowBytes;

    // estatisticas (log a cada 10s)
    private long statStart = System.currentTimeMillis();
    private long statBytes;
    private int statFrames;
    private int statCopy;
    private int statJpeg;
    private int statPng;
    private int statRefine;

    private final ThreadLocal<PngEncoder> png = ThreadLocal.withInitial(PngEncoder::create);
    private final ThreadLocal<JpegEncoder> jpeg = ThreadLocal.withInitial(JpegEncoder::create);

    public TileStreamer(DataOutputStream out, Object writeLock) throws AWTException {
        this.out = out;
        this.writeLock = writeLock;
        this.robot = new Robot();
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        java.awt.GraphicsConfiguration gc = device.getDefaultConfiguration();
        this.screenRect = gc.getBounds();
        this.dpiScale = gc.getDefaultTransform().getScaleX();
        this.streamSize = new Dimension(screenRect.width, screenRect.height);
        warmUp();
    }

    private void warmUp() {
        try {
            BufferedImage dummy = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
            png.get().encode(dummy, 0.2f);
            jpeg.get().encode(dummy, 0.7f);
            java.util.stream.IntStream.range(0, 4).parallel().forEach(i -> { });
        } catch (IOException ignored) {
        }
    }

    // ---------- API (mesma do ScreenStreamer + ack) ----------

    public Dimension getScreenSize() {
        return new Dimension(screenRect.width, screenRect.height);
    }

    public double getDpiScale() {
        return dpiScale;
    }

    public void setStreamSizeListener(Consumer<Dimension> listener) {
        this.streamSizeListener = listener;
    }

    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    public void setTargetViewport(Dimension viewport) {
        this.targetViewport = viewport;
        requestImmediateCapture();
    }

    public void stop() {
        running = false;
        requestImmediateCapture();
        synchronized (ackSignal) {
            ackSignal.notifyAll();
        }
    }

    public void requestImmediateCapture() {
        synchronized (frameSignal) {
            frameSignal.notifyAll();
        }
    }

    /**
     * Perfil de qualidade escolhido no painel (vale na hora, mesmo com a sessao aberta):
     * economia reduz banda/JPEG/taxa; maxima libera mais banda e JPEG melhor.
     */
    public void setProfile(String quality) {
        switch (quality == null ? "auto" : quality) {
            case "economy":
                jpegMax = 0.60f;
                profileMaxBytesPerSec = 600_000L;
                profileActiveIntervalMs = 66;
                break;
            case "balanced":
                jpegMax = 0.80f;
                profileMaxBytesPerSec = 1_200_000L;
                profileActiveIntervalMs = 50;
                break;
            case "max":
                jpegMax = 0.92f;
                profileMaxBytesPerSec = 3_000_000L;
                profileActiveIntervalMs = ACTIVE_INTERVAL_MS;
                break;
            default:
                jpegMax = JPEG_MAX;
                profileMaxBytesPerSec = MAX_BYTES_PER_SEC;
                profileActiveIntervalMs = ACTIVE_INTERVAL_MS;
        }
        maxBytesPerSec = profileMaxBytesPerSec;
        activeIntervalMs = profileActiveIntervalMs;
        jpegQuality = Math.min(jpegQuality, jpegMax);
    }

    /** O visualizador terminou de desenhar o quadro: libera o fluxo e ajusta a qualidade pelo atraso medido. */
    public void ack(int id) {
        Long t = sentAt.remove(id);
        if (t == null) {
            return;
        }
        inflight.decrementAndGet();
        long rtt = System.currentTimeMillis() - t;
        if (rtt > 400) {
            jpegQuality = Math.max(JPEG_MIN, jpegQuality - 0.06f);
        } else if (rtt < 120) {
            jpegQuality = Math.min(jpegMax, jpegQuality + 0.02f);
        }
        // O ack mede a volta completa (agente → hub → navegador → desenho → hub → agente),
        // portanto e um sinal melhor que uma estimativa fixa de link. Em WAN congestionada reduz
        // a producao antes que bytes antigos formem uma fila; ao estabilizar, volta ao perfil.
        if (rtt > 900) {
            maxBytesPerSec = Math.min(profileMaxBytesPerSec, 350_000L);
            activeIntervalMs = Math.max(profileActiveIntervalMs, 120);
        } else if (rtt > 400) {
            maxBytesPerSec = Math.min(profileMaxBytesPerSec, 700_000L);
            activeIntervalMs = Math.max(profileActiveIntervalMs, 75);
        } else if (rtt < 180) {
            maxBytesPerSec = profileMaxBytesPerSec;
            activeIntervalMs = profileActiveIntervalMs;
        }
        synchronized (ackSignal) {
            ackSignal.notifyAll();
        }
    }

    // ---------- tamanho de transmissao (mesma regra do original) ----------

    private void applyPendingViewport() {
        Dimension viewport = targetViewport;
        boolean changed = !java.util.Objects.equals(viewport, appliedViewport);
        if (!changed && streamSizeAnnounced) {
            return;
        }
        appliedViewport = viewport;
        Dimension newSize = computeStreamSize(viewport);
        boolean sizeChanged = !newSize.equals(streamSize);
        if (sizeChanged) {
            streamSize = newSize;
            prev = null; // quadro anterior era de outro tamanho: reenvio completo
            log("Resolucao de transmissao ajustada para " + newSize.width + "x" + newSize.height
                    + " (nativa: " + screenRect.width + "x" + screenRect.height + ").");
        }
        if (sizeChanged || !streamSizeAnnounced) {
            streamSizeAnnounced = true;
            Consumer<Dimension> l = streamSizeListener;
            if (l != null) {
                l.accept(streamSize);
            }
        }
    }

    private Dimension computeStreamSize(Dimension viewport) {
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            return new Dimension(screenRect.width, screenRect.height);
        }
        double scale = Math.min((double) viewport.width / screenRect.width, (double) viewport.height / screenRect.height);
        if (scale >= MIN_DOWNSCALE_TRIGGER) {
            return new Dimension(screenRect.width, screenRect.height);
        }
        scale = Math.min(1.0, scale);
        return new Dimension(Math.max(1, (int) Math.round(screenRect.width * scale)), Math.max(1, (int) Math.round(screenRect.height * scale)));
    }

    // ---------- laco principal ----------

    @Override
    public void run() {
        int nextInterval = activeIntervalMs;
        while (running) {
            try {
                applyPendingViewport();
                if (!waitForWindow()) {
                    continue;
                }
                throttleBandwidth();
                long t0 = System.nanoTime();
                BufferedImage cap = normalize(robot.createScreenCapture(screenRect));
                int sent = processFrame(cap);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (ms >= 200) {
                    log("Quadro lento: " + ms + "ms (" + sent + " bloco(s)).");
                }
                nextInterval = sent > 0 ? activeIntervalMs : (hasPendingRefinement() ? REFINE_INTERVAL_MS : IDLE_INTERVAL_MS);
                logStats();
            } catch (Exception e) {
                log("Falha ao capturar/enviar quadro (tentando de novo): " + e.getMessage());
                nextInterval = activeIntervalMs;
            }
            try {
                synchronized (frameSignal) {
                    frameSignal.wait(nextInterval);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Espera um ack se ja ha quadros demais em transito (o fluxo acompanha a rede). */
    private boolean waitForWindow() throws InterruptedException {
        if (inflight.get() < MAX_INFLIGHT) {
            return true;
        }
        long start = System.currentTimeMillis();
        synchronized (ackSignal) {
            while (running && inflight.get() >= MAX_INFLIGHT) {
                if (System.currentTimeMillis() - start > ACK_TIMEOUT_MS) {
                    // Acks nao chegam (visualizador antigo ou rede parada): nao trava para sempre.
                    inflight.set(0);
                    sentAt.clear();
                    log("Sem confirmacao do visualizador: retomando o envio.");
                    return true;
                }
                ackSignal.wait(50);
            }
        }
        return running;
    }

    private void throttleBandwidth() throws InterruptedException {
        long now = System.currentTimeMillis();
        if (now - windowStart >= 1000) {
            windowStart = now;
            windowBytes = 0;
        } else if (windowBytes > maxBytesPerSec) {
            Thread.sleep(Math.max(1, 1000 - (now - windowStart)));
            windowStart = System.currentTimeMillis();
            windowBytes = 0;
        }
    }

    private boolean hasPendingRefinement() {
        if (lossy == null) {
            return false;
        }
        for (boolean b : lossy) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    // ---------- um quadro ----------

    static final class Tile {
        final int tx;
        final int ty;
        final int x;
        final int y;
        final int w;
        final int h;
        boolean useJpeg;
        boolean refine;
        int[] dest; // x,y,w,h no espaco de transmissao
        byte[] data;

        Tile(int tx, int ty, int x, int y, int w, int h) {
            this.tx = tx;
            this.ty = ty;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private int processFrame(BufferedImage cap) throws IOException {
        int W = cap.getWidth();
        int H = cap.getHeight();
        int[] cur = pixels(cap);
        int gw = (W + TILE - 1) / TILE;
        int gh = (H + TILE - 1) / TILE;
        if (hot == null || gw != gridW || gh != gridH || (prev != null && prev.length != cur.length)) {
            gridW = gw;
            gridH = gh;
            hot = new int[gw * gh];
            stable = new int[gw * gh];
            lossy = new boolean[gw * gh];
            prev = null;
        }
        boolean first = prev == null;
        Dimension stream = streamSize;
        boolean scaling = stream.width != W || stream.height != H;

        // 1) o que mudou desde o quadro anterior
        List<Tile> changed = new ArrayList<>();
        int[] ref = prev;
        if (!first) {
            collectChanged(cur, ref, W, H, changed);
        } else {
            for (int ty = 0; ty < gh; ty++) {
                for (int tx = 0; tx < gw; tx++) {
                    changed.add(tile(tx, ty, W, H));
                }
            }
        }

        // 2) CopyRect: rolagem/arraste vertical grande vira copia de regiao ja enviada
        List<int[]> copies = new ArrayList<>();
        if (!first && !scaling && changed.size() >= Math.max(8, gw * gh / 6)) {
            int[] copy = detectVerticalShift(cur, prev, W, H, changed);
            if (copy != null) {
                if (pred == null || pred.length != prev.length) {
                    pred = new int[prev.length];
                }
                System.arraycopy(prev, 0, pred, 0, prev.length);
                applyCopy(pred, W, copy); // {sx, sy, w, h, dx, dy}
                markCopiedLossy(copy);
                copies.add(copy);
                changed.clear();
                collectChanged(cur, pred, W, H, changed);
            }
        }

        // 3) classifica: PNG sem perdas (mudanca isolada) ou JPEG (mudanca continua / em massa)
        boolean bulk = changed.size() > BULK_TILES;
        boolean[] touched = new boolean[gw * gh];
        for (Tile t : changed) {
            int i = t.ty * gw + t.tx;
            touched[i] = true;
            hot[i] = Math.min(hot[i] + 1, 6);
            stable[i] = 0;
            t.useJpeg = (first || bulk || hot[i] >= HOT_THRESHOLD) && photographic(cur, W, t);
        }
        // tiles que nao mudaram esfriam e ficam "estaveis"
        for (int i = 0; i < hot.length; i++) {
            if (!touched[i]) {
                hot[i] = Math.max(0, hot[i] - 1);
                stable[i]++;
            }
        }
        // 4) refinamento: tiles em JPEG que pararam de mudar sao reenviados sem perdas
        int budget = changed.isEmpty() ? 24 : 4;
        List<Tile> all = new ArrayList<>(changed);
        for (int ty = 0; ty < gh && budget > 0; ty++) {
            for (int tx = 0; tx < gw && budget > 0; tx++) {
                int i = ty * gw + tx;
                if (lossy[i] && !touched[i] && stable[i] >= 2) {
                    Tile t = tile(tx, ty, W, H);
                    t.refine = true;
                    all.add(t);
                    budget--;
                }
            }
        }
        prev = cur.clone(); // referencia = exatamente o que acabamos de capturar

        if (all.isEmpty() && copies.isEmpty()) {
            return 0;
        }

        // 5) destino de cada tile e codificacao (paralela quando ha varios)
        double sx = (double) stream.width / W;
        double sy = (double) stream.height / H;
        for (Tile t : all) {
            if (!scaling) {
                t.dest = new int[] {t.x, t.y, t.w, t.h};
            } else {
                int dx0 = (int) Math.round(t.x * sx);
                int dy0 = (int) Math.round(t.y * sy);
                int dx1 = (int) Math.round((t.x + t.w) * sx);
                int dy1 = (int) Math.round((t.y + t.h) * sy);
                t.dest = new int[] {dx0, dy0, Math.max(1, dx1 - dx0), Math.max(1, dy1 - dy0)};
            }
        }
        float q = jpegQuality;
        if (all.size() >= PARALLEL_ENCODE_THRESHOLD) {
            all.parallelStream().forEach(t -> encode(cap, t, scaling, sx, sy, q));
        } else {
            for (Tile t : all) {
                encode(cap, t, scaling, sx, sy, q);
            }
        }

        // 6) envia o quadro inteiro de uma vez (um flush = um quadro no hub)
        int id = ++frameId;
        long bytes = 0;
        synchronized (writeLock) {
            for (int[] c : copies) {
                out.writeByte(MSG_COPY_RECT);
                for (int v : c) {
                    out.writeInt(v);
                }
                bytes += 25;
            }
            for (Tile t : all) {
                out.writeByte(t.useJpeg && !t.refine ? MSG_TILE_JPEG : MSG_TILE_PNG);
                for (int v : t.dest) {
                    out.writeInt(v);
                }
                out.writeInt(t.data.length);
                out.write(t.data);
                bytes += 21 + t.data.length;
                int i = t.ty * gridW + t.tx;
                lossy[i] = t.useJpeg && !t.refine;
                if (t.refine) {
                    statRefine++;
                } else if (t.useJpeg) {
                    statJpeg++;
                } else {
                    statPng++;
                }
            }
            out.writeByte(MSG_FRAME_END);
            out.writeInt(id);
            bytes += 5;
            sentAt.put(id, System.currentTimeMillis());
            inflight.incrementAndGet();
            out.flush();
        }
        windowBytes += bytes;
        statBytes += bytes;
        statFrames++;
        statCopy += copies.size();
        return all.size() + copies.size();
    }

    /**
     * O CopyRect replica no visualizador os pixels que ele tem na tela, inclusive os JPEG (com perdas).
     * Os blocos de destino que herdaram isso precisam ser refinados quando a imagem parar.
     */
    private void markCopiedLossy(int[] c) {
        boolean srcLossy = false;
        for (int ty = c[1] / TILE; ty <= (c[1] + c[3] - 1) / TILE && !srcLossy; ty++) {
            for (int tx = c[0] / TILE; tx <= (c[0] + c[2] - 1) / TILE; tx++) {
                if (ty < gridH && tx < gridW && lossy[ty * gridW + tx]) {
                    srcLossy = true;
                    break;
                }
            }
        }
        if (!srcLossy) {
            return;
        }
        for (int ty = c[5] / TILE; ty <= (c[5] + c[3] - 1) / TILE; ty++) {
            for (int tx = c[4] / TILE; tx <= (c[4] + c[2] - 1) / TILE; tx++) {
                if (ty < gridH && tx < gridW) {
                    lossy[ty * gridW + tx] = true;
                    stable[ty * gridW + tx] = 0;
                }
            }
        }
    }

    private Tile tile(int tx, int ty, int W, int H) {
        int x = tx * TILE;
        int y = ty * TILE;
        return new Tile(tx, ty, x, y, Math.min(TILE, W - x), Math.min(TILE, H - y));
    }

    private void collectChanged(int[] cur, int[] ref, int W, int H, List<Tile> out) {
        for (int ty = 0; ty < gridH; ty++) {
            for (int tx = 0; tx < gridW; tx++) {
                Tile t = tile(tx, ty, W, H);
                for (int row = 0; row < t.h; row++) {
                    int start = (t.y + row) * W + t.x;
                    if (!Arrays.equals(cur, start, start + t.w, ref, start, start + t.w)) {
                        out.add(t);
                        break;
                    }
                }
            }
        }
    }

    // ---------- CopyRect (rolagem vertical) ----------

    /**
     * Procura um deslocamento vertical dy tal que muitas linhas do quadro atual sejam iguais a linhas
     * do anterior deslocadas. Devolve {srcX, srcY, w, h, dstX, dstY} ou null.
     */
    int[] detectVerticalShift(int[] cur, int[] old, int W, int H, List<Tile> changed) {
        // Caixa EXATA dos pixels que mudaram: as colunas estaticas ao redor da regiao que rola nao se
        // deslocam junto e, se entrassem no hash da linha, impediriam qualquer casamento.
        int[] bounds = diffBounds(cur, old, W, changed);
        if (bounds == null) {
            return null;
        }
        int x0 = bounds[0];
        int y0 = bounds[1];
        int x1 = bounds[2];
        int y1 = bounds[3];
        int bw = x1 - x0;
        int bh = y1 - y0;
        if (bw < 64 || bh < 96) {
            return null;
        }
        long[] hc = new long[bh];
        long[] ho = new long[bh];
        for (int r = 0; r < bh; r++) {
            hc[r] = rowHash(cur, (y0 + r) * W + x0, bw);
            ho[r] = rowHash(old, (y0 + r) * W + x0, bw);
        }
        // linhas do quadro antigo -> indices (descarta hashes muito repetidos: linhas lisas sao ambiguas)
        Map<Long, int[]> index = new HashMap<>();
        for (int r = 0; r < bh; r++) {
            int[] slot = index.computeIfAbsent(ho[r], k -> new int[] {0, -1, -1, -1, -1});
            if (slot[0] < 4) {
                slot[1 + slot[0]] = r;
            }
            slot[0]++;
        }
        Map<Integer, Integer> votes = new HashMap<>();
        for (int r = 0; r < bh; r++) {
            int[] slot = index.get(hc[r]);
            if (slot == null || slot[0] > 4) {
                continue;
            }
            for (int k = 1; k <= slot[0] && k <= 4; k++) {
                int dy = r - slot[k];
                if (dy != 0) {
                    votes.merge(dy, 1, Integer::sum);
                }
            }
        }
        int bestDy = 0;
        int bestVotes = 0;
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            if (e.getValue() > bestVotes) {
                bestVotes = e.getValue();
                bestDy = e.getKey();
            }
        }
        if (bestVotes < Math.max(48, bh * 3 / 10)) {
            return null;
        }
        // maior trecho contiguo de linhas que realmente casam com o deslocamento
        int bestStart = -1;
        int bestLen = 0;
        int runStart = -1;
        for (int r = 0; r <= bh; r++) {
            int src = r - bestDy;
            boolean match = r < bh && src >= 0 && src < bh && hc[r] == ho[src];
            if (match && runStart < 0) {
                runStart = r;
            } else if (!match && runStart >= 0) {
                if (r - runStart > bestLen) {
                    bestLen = r - runStart;
                    bestStart = runStart;
                }
                runStart = -1;
            }
        }
        if (bestLen < 64) {
            return null;
        }
        int dstY = y0 + bestStart;
        int srcY = dstY - bestDy;
        return new int[] {x0, srcY, bw, bestLen, x0, dstY};
    }

    /** {x0, y0, x1, y1} (x1/y1 exclusivos) dos pixels que realmente diferem nos tiles alterados. */
    static int[] diffBounds(int[] cur, int[] old, int W, List<Tile> changed) {
        int x0 = Integer.MAX_VALUE;
        int y0 = Integer.MAX_VALUE;
        int x1 = -1;
        int y1 = -1;
        for (Tile t : changed) {
            for (int row = 0; row < t.h; row++) {
                int start = (t.y + row) * W + t.x;
                int end = start + t.w;
                int m = Arrays.mismatch(cur, start, end, old, start, end);
                if (m < 0) {
                    continue;
                }
                int last = end - 1;
                while (cur[last] == old[last]) {
                    last--;
                }
                x0 = Math.min(x0, t.x + m);
                x1 = Math.max(x1, t.x + (last - start) + 1);
                y0 = Math.min(y0, t.y + row);
                y1 = Math.max(y1, t.y + row + 1);
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1, y1};
    }

    /**
     * Conteudo fotografico/video (muitas cores) compensa JPEG; texto e interface (poucas cores) ficam
     * melhores e menores em PNG sem perdas - JPEG borra texto e ainda pesa mais.
     */
    static boolean photographic(int[] px, int W, Tile t) {
        int[] table = new int[128];
        int n = 0;
        for (int row = 0; row < t.h; row += 2) {
            int base = (t.y + row) * W + t.x;
            for (int col = 0; col < t.w; col += 2) {
                int c = (px[base + col] & 0xFFFFFF) | 0x1000000;
                int h = (c * 0x9E3779B1) >>> 25; // 7 bits
                while (true) {
                    if (table[h] == 0) {
                        table[h] = c;
                        if (++n > 48) {
                            return true;
                        }
                        break;
                    }
                    if (table[h] == c) {
                        break;
                    }
                    h = (h + 1) & 127;
                }
            }
        }
        return false;
    }

    private static long rowHash(int[] data, int start, int len) {
        long h = 0xcbf29ce484222325L;
        for (int i = start; i < start + len; i++) {
            h = (h ^ data[i]) * 0x100000001b3L;
        }
        return h;
    }

    /** Aplica o CopyRect sobre o mapa de predicao (preservando a ordem para regioes que se sobrepoem). */
    static void applyCopy(int[] data, int W, int[] c) {
        int sx = c[0];
        int sy = c[1];
        int w = c[2];
        int h = c[3];
        int dx = c[4];
        int dy = c[5];
        if (dy > sy) { // copia de baixo para cima
            for (int r = h - 1; r >= 0; r--) {
                System.arraycopy(data, (sy + r) * W + sx, data, (dy + r) * W + dx, w);
            }
        } else {
            for (int r = 0; r < h; r++) {
                System.arraycopy(data, (sy + r) * W + sx, data, (dy + r) * W + dx, w);
            }
        }
    }

    // ---------- codificacao ----------

    private void encode(BufferedImage cap, Tile t, boolean scaling, double sx, double sy, float quality) {
        try {
            BufferedImage img = scaling ? scaledTile(cap, t, sx, sy) : cap.getSubimage(t.x, t.y, t.w, t.h);
            if (t.refine) {
                t.data = png.get().encode(img, 0.5f); // refinamento: ocioso, vale gastar CPU para compactar
            } else if (t.useJpeg) {
                t.data = jpeg.get().encode(img, quality);
            } else {
                t.data = png.get().encode(img, 0.15f); // resposta rapida
            }
        } catch (IOException e) {
            t.data = new byte[0];
        }
    }

    /** Reduz um tile com algumas linhas de contexto dos vizinhos (sem costura entre tiles). */
    private BufferedImage scaledTile(BufferedImage cap, Tile t, double sx, double sy) {
        int m = 3;
        int ex0 = Math.max(0, t.x - m);
        int ey0 = Math.max(0, t.y - m);
        int ex1 = Math.min(cap.getWidth(), t.x + t.w + m);
        int ey1 = Math.min(cap.getHeight(), t.y + t.h + m);
        int dx0 = (int) Math.round(ex0 * sx);
        int dy0 = (int) Math.round(ey0 * sy);
        int dx1 = (int) Math.round(ex1 * sx);
        int dy1 = (int) Math.round(ey1 * sy);
        BufferedImage scaled = new BufferedImage(Math.max(1, dx1 - dx0), Math.max(1, dy1 - dy0), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(cap.getSubimage(ex0, ey0, ex1 - ex0, ey1 - ey0), 0, 0, scaled.getWidth(), scaled.getHeight(), null);
        g.dispose();
        int ox = t.dest[0] - dx0;
        int oy = t.dest[1] - dy0;
        int w = Math.min(t.dest[2], scaled.getWidth() - ox);
        int h = Math.min(t.dest[3], scaled.getHeight() - oy);
        return scaled.getSubimage(ox, oy, Math.max(1, w), Math.max(1, h));
    }

    private static BufferedImage normalize(BufferedImage image) {
        if (image.getRaster().getDataBuffer() instanceof DataBufferInt
                && image.getSampleModel() instanceof SinglePixelPackedSampleModel
                && ((SinglePixelPackedSampleModel) image.getSampleModel()).getScanlineStride() == image.getWidth()
                && image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static int[] pixels(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    // ---------- log ----------

    private void log(String message) {
        Consumer<String> l = errorListener;
        if (l != null) {
            l.accept(message);
        }
    }

    private void logStats() {
        long now = System.currentTimeMillis();
        if (now - statStart < 10_000) {
            return;
        }
        double secs = (now - statStart) / 1000.0;
        log(String.format("Modo compativel: %.0f KB/s, %.1f quadros/s | PNG %d, JPEG %d, refinados %d, CopyRect %d | JPEG q=%.2f",
                statBytes / 1024.0 / secs, statFrames / secs, statPng, statJpeg, statRefine, statCopy, jpegQuality));
        statStart = now;
        statBytes = 0;
        statFrames = 0;
        statCopy = 0;
        statJpeg = 0;
        statPng = 0;
        statRefine = 0;
    }

    // ---------- codificadores (um por thread) ----------

    private static final class PngEncoder {
        private final ImageWriter writer;
        private final ImageWriteParam param;

        private PngEncoder(ImageWriter writer, ImageWriteParam param) {
            this.writer = writer;
            this.param = param;
        }

        static PngEncoder create() {
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
            ImageWriter writer = writers.hasNext() ? writers.next() : null;
            ImageWriteParam param = writer != null ? writer.getDefaultWriteParam() : null;
            if (param != null && param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            }
            return new PngEncoder(writer, param);
        }

        byte[] encode(BufferedImage image, float compression) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (writer == null) {
                ImageIO.write(image, "png", baos);
                return baos.toByteArray();
            }
            if (param.canWriteCompressed()) {
                param.setCompressionQuality(compression);
            }
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return baos.toByteArray();
        }
    }

    private static final class JpegEncoder {
        private final ImageWriter writer;
        private final ImageWriteParam param;

        private JpegEncoder(ImageWriter writer, ImageWriteParam param) {
            this.writer = writer;
            this.param = param;
        }

        static JpegEncoder create() {
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            return new JpegEncoder(w, p);
        }

        byte[] encode(BufferedImage image, float quality) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            param.setCompressionQuality(quality);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return baos.toByteArray();
        }
    }
}
