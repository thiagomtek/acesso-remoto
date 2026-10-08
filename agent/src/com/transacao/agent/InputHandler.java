package com.transacao.agent;

import com.transacao.common.remote.InputInjector;

import java.awt.Rectangle;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Aplica os comandos do operador (mouse, teclado, viewport). Compartilhado pelos dois modos de
 * transporte (WebRTC pelo canal de dados e modo compativel pelo hub), para as regras de entrada
 * serem exatamente as mesmas: posicao normalizada, clique com posicao junto, recusa configuravel.
 */
final class InputHandler {

    interface InputSupplier {
        InputInjector get() throws Exception;
    }

    interface ViewportListener {
        void onViewport(int w, int h);
    }

    interface TraceListener {
        void onResult(long seq, String trace, String command, String result);
    }

    private final AgentSettings settings;
    private final InputSupplier input;
    private final ViewportListener viewport;
    private final Runnable afterInput;
    private final Consumer<String> log;
    private final TraceListener trace;
    /** Codigos que efetivamente foram pressionados pelo caminho nativo de scancode. */
    private final Set<String> nativePhysicalDown = new HashSet<>();
    private boolean unicodeFallbackLogged;

    /** @param afterInput chamado apos cada comando aplicado (ex.: acordar a captura para o resultado aparecer logo). */
    InputHandler(AgentSettings settings, InputSupplier input, ViewportListener viewport, Runnable afterInput,
                 Consumer<String> log, TraceListener trace) {
        this.settings = settings;
        this.input = input;
        this.viewport = viewport;
        this.afterInput = afterInput;
        this.log = log;
        this.trace = trace;
    }

    /** Posicao do mouse normalizada (0..1) vinda do canal "mouse" ("x,y"). */
    void handleMouseText(String text) {
        try {
            if (!settings.allowRemoteControl) {
                return;
            }
            int comma = text.indexOf(',');
            moveTo(Double.parseDouble(text.substring(0, comma)), Double.parseDouble(text.substring(comma + 1)));
            afterInput.run();
        } catch (Exception e) {
            log.accept("Comando de mouse ignorado: " + e.getMessage());
        }
    }

    void handleControl(Map<String, Object> m) {
        String command = Json.str(m, "t");
        String traceId = Json.str(m, "trace");
        long seq = (long) Json.num(m, "q", 0);
        try {
            String result = apply(m);
            report(seq, traceId, command, result);
        } catch (Exception e) {
            log.accept("Comando remoto ignorado: " + e.getMessage());
            report(seq, traceId, command, "error");
        }
    }

    private String apply(Map<String, Object> m) throws Exception {
        String t = Json.str(m, "t");
        if (t == null) {
            return "ignored";
        }
        if ("vp".equals(t)) { // tamanho da area de visualizacao do operador
            viewport.onViewport((int) Json.num(m, "w", 0), (int) Json.num(m, "h", 0));
            return "applied";
        }
        if (!settings.allowRemoteControl) {
            return "blocked"; // recusa configurada para esta maquina
        }
        InputInjector in = input.get();
        switch (t) {
            case "mm":
                moveTo(Json.num(m, "x", 0), Json.num(m, "y", 0));
                break;
            case "md": // clique: posicao junto, para nunca cair atrasado num lugar errado
                moveTo(Json.num(m, "x", 0), Json.num(m, "y", 0));
                in.mousePress(button(m));
                break;
            case "mu":
                moveTo(Json.num(m, "x", 0), Json.num(m, "y", 0));
                in.mouseRelease(button(m));
                break;
            case "mw":
                in.mouseWheel((int) Json.num(m, "d", 0));
                break;
            case "kd": {
                String code = Json.str(m, "c");
                KeyMap.PhysicalKey physical = KeyMap.toPhysical(code);
                int vk = KeyMap.toVk(code);
                if (physical != null && in.keyPressPhysical(physical.scan, physical.extended)) {
                    nativePhysicalDown.add(code);
                    break;
                }
                if (vk >= 0) {
                    in.keyPress(vk);
                } else {
                    log.accept("Telemetria teclado: codigo fisico sem mapeamento; entrada especial recusada.");
                    return "unmapped";
                }
                break;
            }
            case "ku": {
                String code = Json.str(m, "c");
                KeyMap.PhysicalKey physical = KeyMap.toPhysical(code);
                int vk = KeyMap.toVk(code);
                if (nativePhysicalDown.remove(code) && physical != null) {
                    in.keyReleasePhysical(physical.scan, physical.extended);
                } else if (vk >= 0) {
                    in.keyRelease(vk);
                } else {
                    return "unmapped";
                }
                break;
            }
            case "text": // protocolo atual: texto Unicode (inclui pares substitutos) sem depender do layout
            case "kt": { // protocolo legado: mantido para agentes/visualizadores em atualizacao gradual
                String text = "text".equals(t) ? Json.str(m, "text") : Json.str(m, "ch");
                if (text != null && !text.isEmpty() && text.codePointCount(0, text.length()) <= 4096) {
                    if (!in.typeText(text) && !unicodeFallbackLogged) {
                        unicodeFallbackLogged = true;
                        log.accept("Telemetria teclado: injeção Unicode nativa indisponível; fallback de compatibilidade aplicado.");
                    }
                } else {
                    log.accept("Telemetria teclado: entrada Unicode vazia ou longa demais recusada.");
                    return "invalid";
                }
                break;
            }
            default:
                return "ignored";
        }
        afterInput.run();
        return "applied";
    }

    private void report(long seq, String traceId, String command, String result) {
        if (trace != null && seq > 0 && traceId != null && command != null) {
            trace.onResult(seq, traceId, command, result);
        }
    }

    private void moveTo(double nx, double ny) throws Exception {
        Rectangle lb = ScreenGeometry.logicalBounds();
        int x = lb.x + (int) Math.round(Math.max(0, Math.min(1, nx)) * (lb.width - 1));
        int y = lb.y + (int) Math.round(Math.max(0, Math.min(1, ny)) * (lb.height - 1));
        input.get().moveMouse(x, y);
    }

    /** Botao do navegador (0 esquerdo, 1 meio, 2 direito) para o do InputInjector (1, 2, 3). */
    private static int button(Map<String, Object> m) {
        int b = (int) Json.num(m, "b", 0);
        return b == 1 ? 2 : b == 2 ? 3 : 1;
    }
}
