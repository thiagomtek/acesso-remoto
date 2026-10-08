package com.transacao.agent;

/** Testes (sem tocar na autoinicializacao real) do registro de inicio automatico. */
public final class AutostartTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        String cmd = Autostart.windowsCommand("C:\\Program Files\\Java\\bin\\javaw.exe", "C:\\Users\\Ana Maria\\app\\transacao-agent.jar");
        check("Windows: javaw direto, aspas por causa de espacos, modo em segundo plano",
                cmd.equals("\"C:\\Program Files\\Java\\bin\\javaw.exe\" -jar \"C:\\Users\\Ana Maria\\app\\transacao-agent.jar\" --background"));
        check("Windows: nenhum script envolvido (.vbs/.bat/wscript/cmd)", !cmd.contains(".vbs") && !cmd.contains(".bat") && !cmd.toLowerCase().contains("wscript") && !cmd.toLowerCase().contains("cmd /c"));

        String out = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\r\n    Assistente    REG_SZ    " + cmd + "\r\n\r\n";
        check("le o valor REG_SZ do 'reg query' (com espacos e aspas)", cmd.equals(Autostart.parseRegValue(out, null)));
        check("sem o valor: null (nao registrado)", Autostart.parseRegValue("ERRO: O sistema nao pode encontrar a chave ou valor especificado.", null) == null);

        String reg = new String(Autostart.regFileContent("Assistente", cmd), java.nio.charset.StandardCharsets.UTF_16LE);
        check(".reg: UTF-16 com BOM e cabecalho do registro", reg.charAt(0) == '\uFEFF' && reg.contains("Windows Registry Editor Version 5.00"));
        check(".reg: chave Run do usuario atual (HKCU)", reg.contains("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]"));
        check(".reg: barras dobradas e aspas escapadas", reg.contains("\"Assistente\"=\"\\\"C:\\\\Program Files\\\\Java\\\\bin\\\\javaw.exe\\\" -jar"));

        String plist = Autostart.macPlist("/Library/Java/bin/java", "/Users/a&b/app/transacao-agent.jar");
        check("macOS: LaunchAgent com RunAtLoad e sem KeepAlive", plist.contains("<key>RunAtLoad</key><true/>") && plist.contains("<key>KeepAlive</key><false/>"));
        check("macOS: argumentos do java e escape de &", plist.contains("<string>-jar</string>") && plist.contains("a&amp;b") && plist.contains("--background"));
        String desktop = Autostart.linuxDesktop("/usr/bin/java", "/home/x/transacao-agent.jar");
        check("Linux: .desktop de autostart", desktop.contains("Exec=\"/usr/bin/java\" -jar \"/home/x/transacao-agent.jar\" --background") && desktop.contains("Type=Application") && desktop.contains("Name=Assistente"));

        // ida e volta REAL na chave Run do usuario, com um nome proprio e descartavel (apagado no fim)
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String name = "AssistenteTeste" + System.nanoTime();
            try {
                StringBuilder log = new StringBuilder();
                boolean ok = Autostart.ensureWindows(name, cmd, m -> log.append(m));
                check("Windows: registra na chave Run do usuario atual (HKCU) e le de volta igual", ok && cmd.equals(Autostart.queryWindowsValue(name)));
                log.setLength(0);
                Autostart.ensureWindows(name, cmd, m -> log.append(m));
                check("Windows: idempotente (segunda vez nao reescreve)", log.length() == 0);
                String novo = Autostart.windowsCommand("C:\\Java\\javaw.exe", "D:\\outra pasta\\transacao-agent.jar");
                Autostart.ensureWindows(name, novo, m -> { });
                check("Windows: se o jar mudou de pasta, atualiza o registro", novo.equals(Autostart.queryWindowsValue(name)));
            } finally {
                Autostart.removeWindowsValue(name);
            }
            check("Windows: valor de teste removido (nada sobra no usuario)", Autostart.queryWindowsValue(name) == null);
        }

        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
