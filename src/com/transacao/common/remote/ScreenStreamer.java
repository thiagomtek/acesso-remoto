package com.transacao.common.remote;

import com.transacao.common.Protocol;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
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

    private final DataOutputStream out;
    private final Object writeLock;
    private final Robot robot;
    private final Rectangle screenRect;
    private final double dpiScale;
    private volatile boolean running = true;
    private volatile Consumer<String> errorListener;
    private BufferedImage previousFrame;
    private final Object frameSignal = new Object();

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
                long t0 = System.nanoTime();
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

        List<int[]> changedTiles = new ArrayList<>();
        for (int ty = 0; ty < height; ty += TILE_SIZE) {
            int th = Math.min(TILE_SIZE, height - ty);
            for (int tx = 0; tx < width; tx += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, width - tx);
                boolean changed = currentData != null
                        ? tileChangedFast(currentData, previousData, width, tx, ty, tw, th)
                        : tileChangedSlow(capture, tx, ty, tw, th);
                if (changed) {
                    changedTiles.add(new int[]{tx, ty, tw, th});
                }
            }
        }

        if (changedTiles.isEmpty()) {
            return new FrameResult(false, 0);
        }

        byte[][] encoded = new byte[changedTiles.size()][];
        if (changedTiles.size() >= PARALLEL_ENCODE_THRESHOLD) {
            // Codifica em paralelo (PNG e CPU-bound e cada tile e independente)
            // - vale a pena quando varias regioes mudam de uma vez (arrastar
            // janela, video, scroll), usando todos os nucleos disponiveis.
            java.util.stream.IntStream.range(0, changedTiles.size()).parallel().forEach(i -> encodeTileInto(capture, changedTiles, encoded, i));
        } else {
            // Poucos tiles: o overhead de coordenar o pool de threads paralelas
            // custa mais do que economiza, entao codifica sequencial mesmo.
            for (int i = 0; i < changedTiles.size(); i++) {
                encodeTileInto(capture, changedTiles, encoded, i);
            }
        }

        synchronized (writeLock) {
            for (int i = 0; i < changedTiles.size(); i++) {
                int[] c = changedTiles.get(i);
                byte[] pngBytes = encoded[i];
                out.writeByte(Protocol.REMOTE_FRAME_TILE);
                out.writeInt(c[0]);
                out.writeInt(c[1]);
                out.writeInt(c[2]);
                out.writeInt(c[3]);
                out.writeInt(pngBytes.length);
                out.write(pngBytes);
            }
            out.flush();
        }
        return new FrameResult(true, changedTiles.size());
    }

    private void encodeTileInto(BufferedImage capture, List<int[]> changedTiles, byte[][] encoded, int i) {
        int[] c = changedTiles.get(i);
        BufferedImage tile = capture.getSubimage(c[0], c[1], c[2], c[3]);
        try {
            encoded[i] = pngEncoder.get().encode(tile);
        } catch (IOException e) {
            encoded[i] = new byte[0];
        }
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
