package com.transacao.agent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Teste sem tela e sem tocar no clipboard real: valida somente PNG, limites e integridade. */
public final class ClipboardImageTest {
    public static void main(String[] args) throws Exception {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(1, 1, 0xff123456);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(source, "png", out);
        byte[] png = out.toByteArray();

        BufferedImage decoded = ClipboardImage.decode(png);
        check(decoded.getWidth() == 3 && decoded.getHeight() == 2, "decodifica dimensoes do PNG");
        check(ClipboardImage.sha256(png).matches("[a-f0-9]{64}"), "calcula SHA-256 canonico");
        check(!ClipboardImage.apply(png, "0".repeat(64)), "hash incorreto e recusado antes de tocar no clipboard");
        expectInvalid(new byte[]{1, 2, 3}, "conteudo que nao e PNG");
        byte[] huge = png.clone();
        huge[16] = 0x7f; huge[17] = (byte) 0xff; huge[18] = (byte) 0xff; huge[19] = (byte) 0xff;
        expectInvalid(huge, "dimensao declarada enorme antes da decodificacao");
        System.out.println("ClipboardImageTest OK");
    }

    private static void expectInvalid(byte[] bytes, String label) {
        try {
            ClipboardImage.decode(bytes);
            throw new AssertionError(label + " deveria ser recusado");
        } catch (IOException expected) {
            System.out.println("  ok " + label);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
