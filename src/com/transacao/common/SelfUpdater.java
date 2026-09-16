package com.transacao.common;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Aplica uma nova versao do proprio .jar recebida do servidor: grava os
 * bytes num arquivo temporario ao lado do jar atual e dispara um pequeno
 * script (.bat) independente que espera este processo terminar, substitui
 * o jar antigo pelo novo e reabre a aplicacao. Isso e necessario porque um
 * processo Java no Windows nao consegue apagar/sobrescrever o proprio
 * arquivo .jar enquanto esta rodando a partir dele (fica travado).
 */
public final class SelfUpdater {

    private SelfUpdater() {
    }

    public static void applyAndRestart(File currentJar, byte[] newJarBytes) throws IOException {
        File dir = currentJar.getParentFile();
        File newJarTemp = new File(dir, currentJar.getName() + ".new");
        try (FileOutputStream fos = new FileOutputStream(newJarTemp)) {
            fos.write(newJarBytes);
        }

        String javaHome = System.getProperty("java.home");
        File javawExe = new File(javaHome, "bin\\javaw.exe");
        String javaExecutable = javawExe.exists() ? javawExe.getAbsolutePath() : "java";

        File script = new File(dir, "auto-update.bat");
        try (PrintWriter writer = new PrintWriter(script, StandardCharsets.UTF_8.name())) {
            writer.println("@echo off");
            writer.println("timeout /t 2 /nobreak >nul");
            writer.println(":retry");
            writer.println("del \"" + currentJar.getAbsolutePath() + "\" 2>nul");
            writer.println("if exist \"" + currentJar.getAbsolutePath() + "\" goto retry");
            writer.println("move /y \"" + newJarTemp.getAbsolutePath() + "\" \"" + currentJar.getAbsolutePath() + "\"");
            writer.println("start \"\" \"" + javaExecutable + "\" -jar \"" + currentJar.getAbsolutePath() + "\"");
            // Auto-apagar o proprio .bat sem o erro "nao e possivel encontrar o
            // arquivo em lotes": o "(goto) 2>nul" forca o cmd a terminar de ler
            // o script inteiro antes de tentar apagar o arquivo.
            writer.println("(goto) 2>nul & del \"%~f0\"");
        }

        // Roda o .bat escondido (sem janela de console) via um .vbs auxiliar,
        // do mesmo jeito que o "iniciar com o Windows" ja faz.
        File vbsScript = new File(dir, "auto-update.vbs");
        String vbs = "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                + "shell.CurrentDirectory = \"" + dir.getAbsolutePath().replace("\"", "\"\"") + "\"\r\n"
                + "shell.Run \"cmd /c \"\"" + script.getAbsolutePath().replace("\"", "\"\"") + "\"\"\", 0, False\r\n";
        try (PrintWriter writer = new PrintWriter(vbsScript, StandardCharsets.UTF_8.name())) {
            writer.print(vbs);
        }

        new ProcessBuilder("wscript.exe", vbsScript.getAbsolutePath())
                .directory(dir)
                .start();
    }
}
