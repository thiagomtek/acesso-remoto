package com.transacao.agent;

import java.util.Locale;

/** Interpretacao do diagnostico de entrada (pura, sem Windows nem PowerShell). */
public final class InputDiagnosticsTest {

    public static void main(String[] args) {
        testParse();
        testExplain();
        testMessagesReachHub();
        testCursor();
        testCleanName();
        System.out.println("InputDiagnosticsTest OK");
    }

    private static void testParse() {
        InputDiagnostics.Probe p = InputDiagnostics.parse("1:1:0:erp.exe\r\n");
        check("parse: desktop acessivel", p != null && p.desktopAccessible);
        check("parse: foreground elevado", p != null && p.foreground == 1);
        check("parse: agente nao elevado", p != null && p.self == 0);
        check("parse: processo", p != null && "erp.exe".equals(p.process));

        InputDiagnostics.Probe colon = InputDiagnostics.parse("1:0:0:a:b");
        check("parse: ':' no nome do processo nao quebra", colon != null && "a_b".equals(colon.process));

        check("parse: nulo", InputDiagnostics.parse(null) == null);
        check("parse: lixo", InputDiagnostics.parse("ERR") == null);
        check("parse: numero invalido", InputDiagnostics.parse("x:y:z:w") == null);
    }

    private static void testExplain() {
        check("normal nao gera aviso", InputDiagnostics.explain(new InputDiagnostics.Probe(true, 0, 0, "notepad")) == null);
        check("sem consulta nao gera aviso", InputDiagnostics.explain(null) == null);
        check("agente elevado nao gera aviso (mesmo com foreground elevado)",
                InputDiagnostics.explain(new InputDiagnostics.Probe(true, 1, 1, "erp")) == null);
        check("foreground desconhecido nao gera aviso", InputDiagnostics.explain(new InputDiagnostics.Probe(true, -1, 0, "-")) == null);

        String desktop = InputDiagnostics.explain(new InputDiagnostics.Probe(false, -1, 0, "-"));
        check("desktop inacessivel gera aviso", desktop != null && desktop.contains("bloqueada"));

        String uipi = InputDiagnostics.explain(new InputDiagnostics.Probe(true, 1, 0, "erp"));
        check("janela elevada gera aviso com o processo", uipi != null && uipi.contains("(erp)") && uipi.contains("UIPI"));

        String denied = InputDiagnostics.explain(new InputDiagnostics.Probe(true, 2, 0, "caixa"));
        check("acesso negado gera aviso 'provavelmente'", denied != null && denied.contains("provavelmente") && denied.contains("(caixa)"));
    }

    /** O AgentApp so envia ao hub o que contem bloquead/falha/recus, e descarta teclado/tecla ou termos sensiveis. */
    private static void testMessagesReachHub() {
        String[] messages = {
                InputDiagnostics.explain(new InputDiagnostics.Probe(false, -1, 0, "-")),
                InputDiagnostics.explain(new InputDiagnostics.Probe(true, 1, 0, "erp")),
                InputDiagnostics.explain(new InputDiagnostics.Probe(true, 2, 0, "erp")),
                InputDiagnostics.explainCursor(10, 10, 500, 500),
        };
        for (String message : messages) {
            String lower = message.toLowerCase(Locale.ROOT);
            check("chega ao hub como aviso: " + message, lower.contains("bloquead"));
            check("nao e descartada (teclado/tecla): " + message, !lower.contains("teclado") && !lower.contains("tecla"));
            check("nao vira 'evento sensivel': " + message,
                    !(lower.contains("clipboard") || lower.contains("senha") || lower.contains("token")
                            || lower.contains("secret") || lower.contains("authorization") || lower.contains("arquivo")));
            check("nao vira 'error' por engano: " + message,
                    !(lower.contains("falha") || lower.contains("erro") || lower.contains("recus")));
            check("codigo 'agent' (sem hub/conexao/atualiza/webrtc/driver): " + message,
                    !(lower.contains("hub") || lower.contains("conexao") || lower.contains("atualiza")
                            || lower.contains("webrtc") || lower.contains("driver") || lower.contains("winsw")
                            || lower.contains("modo servico")));
            check("cabe no limite do hub (240): " + message, message.length() <= 240);
        }
    }

    private static void testCursor() {
        check("cursor no lugar", InputDiagnostics.explainCursor(100, 200, 100, 200) == null);
        check("cursor dentro da tolerancia", InputDiagnostics.explainCursor(100, 200, 105, 195) == null);
        check("cursor fora do lugar", InputDiagnostics.explainCursor(100, 200, 640, 360) != null);
        check("cursor fora so no eixo y", InputDiagnostics.explainCursor(100, 200, 100, 260) != null);
    }

    private static void testCleanName() {
        check("nome simples", "erp.exe".equals(InputDiagnostics.cleanName("erp.exe")));
        check("caracteres estranhos viram _", "a_b_c".equals(InputDiagnostics.cleanName("a\nb\tc")));
        check("nome vazio", "?".equals(InputDiagnostics.cleanName("")));
        check("nome nulo", "?".equals(InputDiagnostics.cleanName(null)));
        check("nome longo e cortado", InputDiagnostics.cleanName("x".repeat(100)).length() == 40);
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            throw new AssertionError("FALHOU: " + name);
        }
    }
}
