package com.transacao.agent;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Imagem PNG do clipboard remoto. Nao grava pixels em log nem em disco. */
final class ClipboardImage {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    static final long MAX_PIXELS = 40_000_000L;

    private ClipboardImage() { }

    static BufferedImage decode(byte[] png) throws IOException {
        if (png == null || png.length < 24 || png.length > MAX_BYTES
                || (png[0] & 0xff) != 0x89 || png[1] != 0x50 || png[2] != 0x4e || png[3] != 0x47) {
            throw new IOException("PNG invalido ou fora do limite");
        }
        // O IHDR vem antes dos pixels. Valide as dimensoes antes de ImageIO alocar a imagem para
        // impedir que um PNG pequeno declare um bitmap gigantesco e pressione a memoria do agente.
        if (png[12] != 0x49 || png[13] != 0x48 || png[14] != 0x44 || png[15] != 0x52) {
            throw new IOException("IHDR ausente");
        }
        long width = readUInt32(png, 16);
        long height = readUInt32(png, 20);
        if (width < 1 || height < 1 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE
                || width * height > MAX_PIXELS) {
            throw new IOException("dimensoes invalidas");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1
                || (long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
            throw new IOException("dimensoes invalidas");
        }
        return image;
    }

    private static long readUInt32(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xff) << 24) | ((long) (bytes[offset + 1] & 0xff) << 16)
                | ((long) (bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xffL);
    }

    static boolean apply(byte[] png, String expectedSha256) {
        try {
            if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(sha256(png))) return false;
            BufferedImage image = decode(png);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransferable(image), null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static final class ImageTransferable implements Transferable {
        private final Image image;
        ImageTransferable(Image image) { this.image = image; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}
