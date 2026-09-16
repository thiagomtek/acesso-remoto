package com.transacao.common;

import java.io.File;
import java.io.IOException;

/**
 * Controla se esta e a primeira vez que o usuario atual abre a aplicacao
 * neste perfil do Windows, usando um arquivo marcador em
 * %APPDATA%\<appName>\activated.flag. Depois de marcado, aberturas futuras
 * (manuais ou pelo inicio automatico do Windows) nao pedem senha de novo.
 */
public final class FirstRunGate {

    private FirstRunGate() {
    }

    private static File markerFile(String appName) {
        String appData = System.getenv("APPDATA");
        File dir = new File(appData != null ? appData : System.getProperty("user.home"), appName);
        return new File(dir, "activated.flag");
    }

    public static boolean isActivated(String appName) {
        return markerFile(appName).exists();
    }

    public static void markActivated(String appName) throws IOException {
        File marker = markerFile(appName);
        File parent = marker.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        marker.createNewFile();
    }
}
