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
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
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

    private final DataOutputStream out;
    private final Object writeLock;
    private final Robot robot;
    private final Rectangle screenRect;
    private final double dpiScale;
    private volatile boolean running = true;
    private volatile Consumer<String> errorListener;
    private BufferedImage previousFrame;
    private final ImageWriter pngWriter;
    private final ImageWriteParam pngWriteParam;

    public ScreenStreamer(DataOutputStream out, Object writeLock) throws AWTException {
        this.out = out;
        this.writeLock = writeLock;
        this.robot = new Robot();
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        java.awt.GraphicsConfiguration gc = device.getDefaultConfiguration();
        this.screenRect = gc.getBounds();
        this.dpiScale = gc.getDefaultTransform().getScaleX();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        this.pngWriter = writers.hasNext() ? writers.next() : null;
        ImageWriteParam param = pngWriter != null ? pngWriter.getDefaultWriteParam() : null;
        if (param != null && param.canWriteCompressed()) {
            // PNG e sempre sem perdas - a "qualidade" aqui so controla o nivel de
            // compressao (deflate). Como banda nao e o gargalo, usamos o nivel mais
            // rapido para nao travar a transmissao esperando a compressao terminar.
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.0f);
        }
        this.pngWriteParam = param;
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

    @Override
    public void run() {
        while (running) {
            try {
                BufferedImage capture = robot.createScreenCapture(screenRect);
                sendChangedTiles(capture);
                previousFrame = capture;
            } catch (Exception e) {
                // Uma falha isolada (ex: hiccup de rede num frame) nao deve matar a
                // transmissao para sempre - registra e tenta de novo no proximo frame.
                if (errorListener != null) {
                    errorListener.accept("Falha ao capturar/enviar frame de tela (tentando de novo): " + e.getMessage());
                } else {
                    System.err.println("Falha ao capturar/enviar frame de tela: " + e.getMessage());
                }
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void sendChangedTiles(BufferedImage capture) throws IOException {
        int width = capture.getWidth();
        int height = capture.getHeight();

        for (int ty = 0; ty < height; ty += TILE_SIZE) {
            int th = Math.min(TILE_SIZE, height - ty);
            for (int tx = 0; tx < width; tx += TILE_SIZE) {
                int tw = Math.min(TILE_SIZE, width - tx);
                if (!tileChanged(capture, tx, ty, tw, th)) {
                    continue;
                }

                BufferedImage tile = capture.getSubimage(tx, ty, tw, th);
                byte[] pngBytes = encodePng(tile);

                synchronized (writeLock) {
                    out.writeByte(Protocol.REMOTE_FRAME_TILE);
                    out.writeInt(tx);
                    out.writeInt(ty);
                    out.writeInt(tw);
                    out.writeInt(th);
                    out.writeInt(pngBytes.length);
                    out.write(pngBytes);
                    out.flush();
                }
            }
        }
    }

    private boolean tileChanged(BufferedImage capture, int x, int y, int w, int h) {
        if (previousFrame == null) {
            return true;
        }
        int[] current = capture.getRGB(x, y, w, h, null, 0, w);
        int[] previous = previousFrame.getRGB(x, y, w, h, null, 0, w);
        return !Arrays.equals(current, previous);
    }

    private byte[] encodePng(BufferedImage image) throws IOException {
        if (pngWriter == null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            pngWriter.setOutput(ios);
            pngWriter.write(null, new IIOImage(image, null, null), pngWriteParam);
        }
        return baos.toByteArray();
    }
}
