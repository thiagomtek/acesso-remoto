package com.transacao.common;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/**
 * Registra (ou remove) a aplicacao para iniciar automaticamente quando o
 * USUARIO ATUAL fizer login no Windows - coloca um pequeno launcher (.vbs)
 * na pasta de Inicializacao pessoal do usuario
 * (%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup), que roda o
 * jar em segundo plano (sem janela de console). Nao mexe em nada global da
 * maquina (registro HKLM, Servicos do Windows, outros perfis) - so afeta
 * quem estiver logado, e nao precisa de privilegio de administrador.
 * Funciona independente da pasta onde o .jar estiver, porque descobre o
 * caminho absoluto do proprio jar em tempo de execucao.
 */
public final class WindowsStartup {

    private WindowsStartup() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static File startupScriptFile(String appName) {
        String appData = System.getenv("APPDATA");
        File startupDir = new File(appData, "Microsoft\\Windows\\Start Menu\\Programs\\Startup");
        return new File(startupDir, appName + ".vbs");
    }

    public static boolean isEnabled(String appName) {
        return startupScriptFile(appName).exists();
    }

    /** @throws IllegalStateException se a aplicacao nao estiver rodando a partir de um .jar (ex: rodando de dentro de uma IDE). */
    public static void enable(String appName, Class<?> mainClass) throws IOException, URISyntaxException {
        File jarFile = new File(mainClass.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!jarFile.getName().toLowerCase().endsWith(".jar")) {
            throw new IllegalStateException("So funciona quando executado a partir do arquivo .jar (nao de dentro de uma IDE).");
        }

        String javaHome = System.getProperty("java.home");
        File javawExe = new File(javaHome, "bin\\javaw.exe");
        String javaExecutable = javawExe.exists() ? javawExe.getAbsolutePath() : "java";

        String command = "\"" + javaExecutable + "\" -jar \"" + jarFile.getAbsolutePath() + "\"";
        String escapedCommand = command.replace("\"", "\"\"");
        String vbs = "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                + "shell.CurrentDirectory = \"" + jarFile.getParentFile().getAbsolutePath().replace("\"", "\"\"") + "\"\r\n"
                + "shell.Run \"" + escapedCommand + "\", 0, False\r\n";

        File script = startupScriptFile(appName);
        script.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(script, StandardCharsets.UTF_8.name())) {
            writer.print(vbs);
        }
    }

    public static void disable(String appName) {
        File script = startupScriptFile(appName);
        if (script.exists()) {
            script.delete();
        }
    }
}
