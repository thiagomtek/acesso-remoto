package com.transacao.common;

import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Controla se esta e a primeira vez que o usuario atual abre a aplicacao
 * neste perfil do Windows, usando um arquivo marcador em
 * %APPDATA%\<appName>\activated.flag. Depois de marcado, aberturas futuras
 * (manuais ou pelo inicio automatico do Windows) nao pedem senha de novo.
 */
public final class FirstRunGate {

    /**
     * Senha exigida so na primeira vez que o app e aberto neste perfil. Guardada apenas como hash
     * PBKDF2-HMAC-SHA256 (sal proprio + muitas iteracoes): a senha em si nao esta em lugar nenhum do codigo.
     */
    private static final int PASSWORD_ITERATIONS = 200000;
    private static final String PASSWORD_SALT_B64 = "mCSyg9BNE27BDls0LWLQ6w==";
    private static final String PASSWORD_HASH_B64 = "nYLAyJPiQSoGGzye4X324DQknjRKmDZFXChaEa+f9CE=";

    private FirstRunGate() {
    }

    private static File markerFile(String appName) {
        String appData = System.getenv("APPDATA");
        File dir = new File(appData != null ? appData : System.getProperty("user.home"), appName);
        return new File(dir, "activated.flag");
    }

    public static boolean isActivated(String appName) {
        if (markerFile(appName).exists()) {
            return true;
        }
        if ("Assistente".equalsIgnoreCase(appName) && markerFile("TransacaoClient").exists()) {
            try {
                markActivated("Assistente");
            } catch (IOException ignored) {
            }
            return true;
        }
        return false;
    }

    public static void markActivated(String appName) throws IOException {
        File marker = markerFile(appName);
        File parent = marker.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        marker.createNewFile();
    }

    /**
     * Garante a senha da primeira execucao: se ainda nao ativado, pede ate acertar (ou o usuario
     * cancelar) e marca como ativado. Chamar na thread do Swing. Devolve false se cancelado.
     */
    public static boolean requirePassword(String appName) {
        if (isActivated(appName)) {
            return true;
        }
        while (true) {
            JPasswordField passwordField = new JPasswordField();
            int result = JOptionPane.showConfirmDialog(null, passwordField,
                    appName + " - Senha necessaria para o primeiro uso", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return false;
            }
            char[] entered = passwordField.getPassword();
            boolean correct = hashMatches(entered);
            Arrays.fill(entered, '\0');
            if (correct) {
                try {
                    markActivated(appName);
                } catch (IOException ignored) {
                }
                return true;
            }
            JOptionPane.showMessageDialog(null, "Senha incorreta.", "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static boolean hashMatches(char[] entered) {
        return matches(entered, PASSWORD_SALT_B64, PASSWORD_ITERATIONS, PASSWORD_HASH_B64);
    }

    /** Compara a senha digitada com um hash PBKDF2 (tempo constante). Exposto no pacote para teste. */
    static boolean matches(char[] entered, String saltB64, int iterations, String expectedB64) {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] salt = java.util.Base64.getDecoder().decode(saltB64);
            byte[] got = f.generateSecret(new javax.crypto.spec.PBEKeySpec(entered, salt, iterations, 256)).getEncoded();
            return MessageDigest.isEqual(got, java.util.Base64.getDecoder().decode(expectedB64));
        } catch (Exception e) {
            return false;
        }
    }
}
