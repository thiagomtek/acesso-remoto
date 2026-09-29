package com.transacao.common.remote;

import com.transacao.common.Protocol;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.DisplayMode;
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
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Captura a tela local (java.awt.Robot) e envia, pela conexao ja
 * estabelecida, apenas os blocos (tiles) que mudaram desde o ultimo frame,
 * comprimidos sem perdas (PNG) - a mesma ideia usada por VNC/RDP para dar
 * imagem nitida com pouco trafego, em vez de recomprimir a tela inteira
 * como JPEG a cada frame.
 */
public class ScreenStreamer implements Runnable {

    private static final int TILE_SIZE = 256;

    // Taxa de frames adaptativa: enquanto a tela remota esta mudando (mouse,
    // digitacao, video), captura ate ~30fps para ficar tao responsivo quanto a
    // Area de Trabalho Remota nativa; quando fica parada, volta para ~4fps para
    // nao gastar CPU/rede a toa - o mesmo trade-off que RDP/VNC fazem.
    private static final int ACTIVE_INTERVAL_MS = 33;
    private static final int IDLE_INTERVAL_MS = 250;

    // Frame que passar disso loga um detalhamento (captura/diff/encode/envio)
    // para dar pra ver ONDE o tempo esta indo num caso lento real, em vez de
    // so "esta lento" sem mais informacao.
    private static final long SLOW_FRAME_LOG_THRESHOLD_MS = 150;

    // Abaixo disso, codificar em paralelo custa mais (overhead de coordenar o
    // fork-join pool) do que so codificar sequencial na propria thread.
    private static final int PARALLEL_ENCODE_THRESHOLD = 6;

    // Se a resolucao de destino (viewport de quem esta vendo) for pelo menos
    // isso da nativa, nao vale a pena reduzir - a diferenca seria imperceptivel
    // e so pagariamos o custo do redimensionamento a toa.
    private static final double MIN_DOWNSCALE_TRIGGER = 0.97;

    private final DataOutputStream out;
    private final Object writeLock;
    private final Robot robot;
    private final Rectangle screenRect;
    private final double dpiScale;
    private volatile boolean running = true;
    private volatile Consumer<String> errorListener;
    private BufferedImage previousFrame;
    private final Object frameSignal = new Object();

    // Tamanho maximo em que a imagem deve caber (normalmente a resolucao de
    // quem esta vendo, em tela cheia) e o tamanho efetivamente capturado/
    // codificado/enviado agora - reduzidos proporcionalmente a partir da
    // resolucao nativa quando ela e maior que o necessario (ex: client 4K
    // visto num notebook 1080p). So a thread de captura (run()) le/atualiza
    // "streamSize" e realoca os buffers, entao nao precisa de lock: o setter
    // so guarda a intencao (targetViewport) e acorda o loop.
    private volatile Dimension targetViewport;
    private Dimension appliedViewport;
    private boolean streamSizeAnnounced;
    private volatile Dimension streamSize;
    private volatile Consumer<Dimension> streamSizeListener;

    // ImageWriter do ImageIO nao e thread-safe; ao paralelizar a codificacao
    // dos tiles (abaixo) cada thread usa a sua propria instancia.
    private final ThreadLocal<PngEncoder> pngEncoder = ThreadLocal.withInitial(PngEncoder::create);

    public ScreenStreamer(DataOutputStream out, Object writeLock) throws AWTException {
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

    /**
     * Forca o carregamento/JIT do codificador PNG e "acorda" o pool de
     * threads paralelas ANTES do primeiro frame real, para os primeiros
     * frames de cada sessao nao pagarem esse custo de aquecimento (e o que
     * fazia os primeiros frames depois de conectar/reconectar parecerem
     * lentos mesmo com poucos tiles alterados).
     */
    private void warmUp() {
        try {
            BufferedImage dummy = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
            pngEncoder.get().encode(dummy);
            int[] dummyPixels = new int[TILE_SIZE * TILE_SIZE];
            tileChangedFast(dummyPixels, dummyPixels, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE);
            java.util.stream.IntStream.range(0, 4).parallel().forEach(i -> { });
        } catch (IOException ignored) {
        }
    }

    public Dimension getScreenSize() {
        return new Dimension(screenRect.width, screenRect.height);
    }

    /**
     * Aplica, se necessario, uma mudanca pendente de targetViewport: so roda
     * na thread de captura (chamada no topo do loop em run()) para nao
     * mexer nos buffers de frame concorrentemente com o resto do processamento.
     */
    private void applyPendingViewport() {
        Dimension viewport = targetViewport;
        boolean viewportChanged = !java.util.Objects.equals(viewport, appliedViewport);
        if (!viewportChanged && streamSizeAnnounced) {
            return;
        }
        appliedViewport = viewport;
        Dimension newSize = computeStreamSize(viewport);
        boolean sizeChanged = !newSize.equals(streamSize);
        if (sizeChanged) {
            streamSize = newSize;
            // Frame anterior era de outro tamanho - forca reenvio completo
            // no tamanho novo em vez de comparar tiles incompativeis.
            previousFrame = null;
            if (errorListener != null) {
                errorListener.accept("Resolucao de transmissao ajustada para " + newSize.width + "x" + newSize.height
                        + " (nativa: " + screenRect.width + "x" + screenRect.height + ").");
            }
        }
        // Anuncia sempre na primeira vez (mesmo sem mudanca, pro outro lado
        // criar o canvas no tamanho certo) e depois so quando mudar de fato.
        if (sizeChanged || !streamSizeAnnounced) {
            streamSizeAnnounced = true;
            Consumer<Dimension> listener = streamSizeListener;
            if (listener != null) {
                listener.accept(streamSize);
            }
        }
    }

    private Dimension computeStreamSize(Dimension viewport) {
        if (viewport == null || viewport.width <= 0 || viewport.height <= 0) {
            return new Dimension(screenRect.width, screenRect.height);
        }
        double scale = Math.min(
                (double) viewport.width / screenRect.width,
                (double) viewport.height / screenRect.height);
        if (scale >= MIN_DOWNSCALE_TRIGGER) {
            return new Dimension(screenRect.width, screenRect.height);
        }
        scale = Math.min(1.0, scale); // nunca aumenta acima da nativa
        int w = Math.max(1, (int) Math.round(screenRect.width * scale));
        int h = Math.max(1, (int) Math.round(screenRect.height * scale));
        return new Dimension(w, h);
    }

    public int getScreenOriginX() {
        return screenRect.x;
    }

    public int getScreenOriginY() {
        return screenRect.y;
    }

    /** Fator de escala do Windows detectado (1.0 = 100%, 1.25 = 125%, etc). */
    public double getDpiScale() {
        return dpiScale;
    }

    /** Tamanho efetivamente capturado/codificado/enviado agora (<= tamanho nativo). */
    public Dimension getStreamSize() {
        return streamSize;
    }

    /** Chamado (de qualquer thread) quando o tamanho de transmissao muda, para avisar o outro lado. */
    public void setStreamSizeListener(Consumer<Dimension> listener) {
        this.streamSizeListener = listener;
    }

    /**
     * Define o tamanho maximo em que a imagem deve caber - tipicamente a
     * resolucao de quem esta vendo (em tela cheia). Quando a tela nativa e
     * maior, os proximos frames passam a ser reduzidos proporcionalmente
     * antes de comparar/codificar, cortando trafego e CPU sem perda
     * perceptivel (a tela de destino nao mostraria os pixels extras de
     * qualquer jeito). Nunca aumenta acima da resolucao nativa. null remove
     * o limite (volta a nativa). Seguro de chamar de qualquer thread.
     */
    public void setTargetViewport(Dimension viewport) {
        this.targetViewport = viewport;
        requestImmediateCapture();
    }

    public void stop() {
        running = false;
    }

    /** Chamado quando um frame falha ao capturar/enviar; nao para o streaming, so avisa (ex: para logar na UI). */
    public void setErrorListener(Consumer<String> errorListener) {
        this.errorListener = errorListener;
    }

    /**
     * Acorda a captura imediatamente em vez de esperar o intervalo atual
     * terminar - chamado logo apos aplicar um comando remoto (mouse/teclado)
     * para o lado que esta vendo a tela nao ficar esperando ate 250ms (o
     * intervalo ocioso) so para o resultado de um clique aparecer.
     */
    public void requestImmediateCapture() {
        synchronized (frameSignal) {
            frameSignal.notifyAll();
        }
    }

    @Override
    public void run() {
        int nextIntervalMs = ACTIVE_INTERVAL_MS;
        while (running) {
            try {
                applyPendingViewport();

                long t0 = System.nanoTime();
                // Sempre captura/compara em resolucao NATIVA - o redimensionamento
                // para caber no viewport (quando precisa) e feito so por tile
                // alterado, la na frente (ver encodeTileInto). Reescalar o frame
                // inteiro aqui, a cada captura, custava tempo fixo proporcional a
                // tela inteira mesmo quando quase nada mudou - foi a causa de um
                // lag perceptivel (cursor "andando na frente" da imagem) testado
                // em maquina real.
                BufferedImage capture = robot.createScreenCapture(screenRect);
                long t1 = System.nanoTime();
                FrameResult result = sendChangedTiles(capture);
                long t2 = System.nanoTime();
                previousFrame = capture;
                nextIntervalMs = result.anyChanged ? ACTIVE_INTERVAL_MS : IDLE_INTERVAL_MS;

                long totalMs = (t2 - t0) / 1_000_000;
                if (totalMs >= SLOW_FRAME_LOG_THRESHOLD_MS && errorListener != null) {
                    long captureMs = (t1 - t0) / 1_000_000;
                    long processMs = (t2 - t1) / 1_000_000;
                    errorListener.accept(String.format(
                            "Frame lento: %dms total (captura=%dms, diff+encode+envio=%dms, %d tile(s) alterado(s))",
                            totalMs, captureMs, processMs, result.tileCount));
                }
            } catch (Exception e) {
                // Uma falha isolada (ex: hiccup de rede num frame) nao deve matar a
                // transmissao para sempre - registra e tenta de novo no proximo frame.
                if (errorListener != null) {
                    errorListener.accept("Falha ao capturar/enviar frame de tela (tentando de novo): " + e.getMessage());
                } else {
                    System.err.println("Falha ao capturar/enviar frame de tela: " + e.getMessage());
                }
                nextIntervalMs = ACTIVE_INTERVAL_MS;
            }

            try {
                synchronized (frameSignal) {
                    frameSignal.wait(nextIntervalMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class FrameResult {
        final boolean anyChanged;
        final int tileCount;

        FrameResult(boolean anyChanged, int tileCount) {
            this.anyChanged = anyChanged;
            this.tileCount = tileCount;
        }
    }

    private FrameResult sendChangedTiles(BufferedImage capture) throws IOException {
        int width = capture.getWidth();
        int height = capture.getHeight();

        // Acesso direto ao array de pixels da captura (sem getRGB(), que aloca
        // um int[] novo por tile a cada frame - com uma tela 1080p a ~30fps isso
        // e centenas de MB/s de lixo e o GC resultante era uma das causas da
        // demora entre frames). Quando o formato bate com o esperado (o comum
        // no Robot.createScreenCapture no Windows) comparamos os pixels crus;
        // senao caimos no getRGB() como antes, so mais lento.
        int[] currentData = rawPixels(capture);
        int[] previousData = previousFrame != null ? rawPixels(previousFrame) : null;

        // Tiles alterados, sempre em coordenadas NATIVAS (grade de TILE_SIZE),
        // e a caixa envolvente (uniao) de todos eles.
        List<int[]> changedTiles = new ArrayList<>();
        int unionX0 = Integer.MAX_VALUE, unionY0 = Integer.MAX_VALUE, unionX1 = 0, unionY1 = 0;
        for (int ty = 0; ty < height; ty += TILE_SIZE) {
            int th = Math.min(TILE_SIZE, height - ty);
            for (int tx = 0; tx < width; tx += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, width - tx);
                boolean changed = currentData != null
                        ? tileChangedFast(currentData, previousData, width, tx, ty, tw, th)
                        : tileChangedSlow(capture, tx, ty, tw, th);
                if (changed) {
                    changedTiles.add(new int[]{tx, ty, tw, th});
                    unionX0 = Math.min(unionX0, tx);
                    unionY0 = Math.min(unionY0, ty);
                    unionX1 = Math.max(unionX1, tx + tw);
                    unionY1 = Math.max(unionY1, ty + th);
                }
            }
        }

        if (changedTiles.isEmpty()) {
            return new FrameResult(false, 0);
        }

        // Retangulo de DESTINO (coordenadas de streamSize) de cada tile - igual
        // ao nativo quando nao ha adaptacao de resolucao. Os limites x0/x1/y0/y1
        // sao arredondados a partir das bordas ABSOLUTAS do tile (nao da
        // largura/altura isoladas), pra tiles vizinhos continuarem encostados
        // sem frestas nem sobreposicao entre si.
        Dimension stream = streamSize;
        boolean scaling = stream.width != width || stream.height != height;
        double scaleX = (double) stream.width / width;
        double scaleY = (double) stream.height / height;
        int[][] destRects = new int[changedTiles.size()][];
        for (int i = 0; i < changedTiles.size(); i++) {
            int[] c = changedTiles.get(i);
            if (!scaling) {
                destRects[i] = c;
                continue;
            }
            int dx0 = (int) Math.round(c[0] * scaleX);
            int dy0 = (int) Math.round(c[1] * scaleY);
            int dx1 = (int) Math.round((c[0] + c[2]) * scaleX);
            int dy1 = (int) Math.round((c[1] + c[3]) * scaleY);
            destRects[i] = new int[]{dx0, dy0, Math.max(1, dx1 - dx0), Math.max(1, dy1 - dy0)};
        }

        // Redimensiona a uniao de todos os tiles alterados DE UMA VEZ (nao tile
        // a tile isolado) e cada tile recorta seu pedaco dela na hora de
        // codificar - assim a interpolacao bilinear enxerga os pixels vizinhos
        // de verdade nas bordas de cada tile, em vez de so os do proprio tile
        // (o que deixava uma costura/serrilhado sutil onde dois tiles vizinhos
        // se encontravam, ja que cada um era escalado sem saber do outro).
        BufferedImage scaledUnion = null;
        int unionDestX0 = unionX0;
        int unionDestY0 = unionY0;
        if (scaling) {
            unionDestX0 = (int) Math.round(unionX0 * scaleX);
            unionDestY0 = (int) Math.round(unionY0 * scaleY);
            int unionDestX1 = (int) Math.round(unionX1 * scaleX);
            int unionDestY1 = (int) Math.round(unionY1 * scaleY);
            BufferedImage unionSrc = capture.getSubimage(unionX0, unionY0, unionX1 - unionX0, unionY1 - unionY0);
            scaledUnion = scaleTile(unionSrc,
                    Math.max(1, unionDestX1 - unionDestX0), Math.max(1, unionDestY1 - unionDestY0));
        }

        byte[][] encoded = new byte[changedTiles.size()][];
        BufferedImage finalScaledUnion = scaledUnion;
        int finalUnionDestX0 = unionDestX0;
        int finalUnionDestY0 = unionDestY0;
        if (changedTiles.size() >= PARALLEL_ENCODE_THRESHOLD) {
            // Codifica em paralelo (PNG e CPU-bound e cada tile e independente)
            // - vale a pena quando varias regioes mudam de uma vez (arrastar
            // janela, video, scroll), usando todos os nucleos disponiveis.
            java.util.stream.IntStream.range(0, changedTiles.size()).parallel().forEach(i ->
                    encodeTileInto(capture, changedTiles, destRects, finalScaledUnion, finalUnionDestX0, finalUnionDestY0, encoded, i));
        } else {
            // Poucos tiles: o overhead de coordenar o pool de threads paralelas
            // custa mais do que economiza, entao codifica sequencial mesmo.
            for (int i = 0; i < changedTiles.size(); i++) {
                encodeTileInto(capture, changedTiles, destRects, finalScaledUnion, finalUnionDestX0, finalUnionDestY0, encoded, i);
            }
        }

        synchronized (writeLock) {
            for (int i = 0; i < changedTiles.size(); i++) {
                int[] d = destRects[i];
                byte[] pngBytes = encoded[i];
                out.writeByte(Protocol.REMOTE_FRAME_TILE);
                out.writeInt(d[0]);
                out.writeInt(d[1]);
                out.writeInt(d[2]);
                out.writeInt(d[3]);
                out.writeInt(pngBytes.length);
                out.write(pngBytes);
            }
            out.flush();
        }
        return new FrameResult(true, changedTiles.size());
    }

    /**
     * Codifica um tile alterado. Sem adaptacao de resolucao, recorta direto
     * da captura nativa (caminho identico ao de antes). Com adaptacao ativa,
     * recorta do scaledUnion (ja redimensionado, com contexto dos vizinhos -
     * ver comentario em sendChangedTiles) na posicao equivalente.
     */
    private void encodeTileInto(BufferedImage capture, List<int[]> changedTiles, int[][] destRects,
            BufferedImage scaledUnion, int unionDestX0, int unionDestY0, byte[][] encoded, int i) {
        int[] c = changedTiles.get(i);
        int[] d = destRects[i];
        BufferedImage tile;
        if (scaledUnion == null) {
            tile = capture.getSubimage(c[0], c[1], c[2], c[3]);
        } else {
            tile = scaledUnion.getSubimage(d[0] - unionDestX0, d[1] - unionDestY0, d[2], d[3]);
        }
        try {
            encoded[i] = pngEncoder.get().encode(tile);
        } catch (IOException e) {
            encoded[i] = new byte[0];
        }
    }

    private BufferedImage scaleTile(BufferedImage src, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    /**
     * Devolve o array de pixels cru da imagem (sem copiar) quando ela usa o
     * layout simples de 1 int por pixel com stride igual a largura (o caso
     * comum de Robot.createScreenCapture); null se o formato for outro, para
     * o chamador cair no caminho mais lento (getRGB) com seguranca.
     */
    private int[] rawPixels(BufferedImage image) {
        if (!(image.getRaster().getDataBuffer() instanceof DataBufferInt)) {
            return null;
        }
        if (!(image.getSampleModel() instanceof SinglePixelPackedSampleModel)) {
            return null;
        }
        SinglePixelPackedSampleModel sm = (SinglePixelPackedSampleModel) image.getSampleModel();
        if (sm.getScanlineStride() != image.getWidth()) {
            return null;
        }
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private boolean tileChangedFast(int[] current, int[] previous, int stride, int x, int y, int w, int h) {
        if (previous == null) {
            return true;
        }
        for (int row = 0; row < h; row++) {
            int rowStart = (y + row) * stride + x;
            if (!Arrays.equals(current, rowStart, rowStart + w, previous, rowStart, rowStart + w)) {
                return true;
            }
        }
        return false;
    }

    private boolean tileChangedSlow(BufferedImage capture, int x, int y, int w, int h) {
        if (previousFrame == null) {
            return true;
        }
        int[] current = capture.getRGB(x, y, w, h, null, 0, w);
        int[] previous = previousFrame.getRGB(x, y, w, h, null, 0, w);
        return !Arrays.equals(current, previous);
    }

    /** Encoder PNG "mais rapido" (nivel de compressao minimo) - uma instancia por thread. */
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
                // PNG e sempre sem perdas - a "qualidade" aqui so controla o nivel de
                // compressao (deflate). Como banda nao e o gargalo, usamos o nivel mais
                // rapido para nao travar a transmissao esperando a compressao terminar.
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.0f);
            }
            return new PngEncoder(writer, param);
        }

        byte[] encode(BufferedImage image) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (writer == null) {
                ImageIO.write(image, "png", baos);
                return baos.toByteArray();
            }
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return baos.toByteArray();
        }
    }
}
