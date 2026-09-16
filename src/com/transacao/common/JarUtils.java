package com.transacao.common;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utilitarios para identificar/comparar versoes de jar por hash SHA-256 -
 * usado pela auto-atualizacao do client (evita precisar manter um numero
 * de versao manualmente: o proprio conteudo do arquivo e a "versao").
 */
public final class JarUtils {

    private JarUtils() {
    }

    public static File findRunningJar(Class<?> mainClass) throws URISyntaxException {
        return new File(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    public static String sha256(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
