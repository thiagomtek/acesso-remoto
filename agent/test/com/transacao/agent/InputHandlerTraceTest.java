package com.transacao.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Teste sem tela e sem injecao real: valida somente a confirmacao tecnica sanitizada. */
public final class InputHandlerTraceTest {
    public static void main(String[] args) {
        AgentSettings settings = new AgentSettings();
        settings.allowRemoteControl = false;
        String[] result = {null};
        InputHandler handler = new InputHandler(settings,
                () -> { throw new AssertionError("nao deve tocar no SO com controle bloqueado"); },
                (w, h) -> { }, () -> { }, message -> { },
                (seq, trace, command, status) -> result[0] = seq + ":" + trace + ":" + command + ":" + status);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("t", "mw"); command.put("d", 1); command.put("q", 7); command.put("trace", "sessao:7");
        handler.handleControl(command);
        check("7:sessao:7:mw:blocked".equals(result[0]), "resultado bloqueado deve ser correlacionado");

        result[0] = null;
        handler.handleControl(Map.of("t", "mw", "d", 1));
        check(result[0] == null, "comando legado sem trace continua compativel e nao inventa confirmacao");
        System.out.println("InputHandlerTraceTest OK");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
