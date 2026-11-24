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

    private final AgentSettings settings;
    private final InputSupplier input;
    private final ViewportListener viewport;
    private final Runnable afterInput;
    private final Consumer<String> log;
    /** Codigos que efetivamente foram pressionados pelo caminho nativo de scancode. */
    private final Set<String> nativePhysicalDown = new HashSet<>();
    private boolean unicodeFallbackLogged;

    // Diagnostico de entrada descartada pelo Windows (tela bloqueada, UAC, janela elevada). Compartilhado por
    // todas as sessoes (um unico auxiliar PowerShell por agente) e executado fora da thread de entrada, para
    // nunca atrasar um clique: no maximo uma consulta a cada PROBE_INTERVAL_MS.
    private static final long PROBE_INTERVAL_MS = 5_000;
    private static final long SAME_MESSAGE_MS = 2 * 60_000;
    private static final long HELPER_FAILURE_MS = 30 * 60_000;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    private static final InputDiagnostics DIAGNOSTICS = new InputDiagnostics();
    private static final java.util.concurrent.ExecutorService DIAGNOSTICS_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "input-diagnostics");
                t.setDaemon(true);
                return t;
            });
    private static final java.util.concurrent.atomic.AtomicBoolean PROBING = new java.util.concurrent.atomic.AtomicBoolean();
    private static final Map<String, Long> LAST_REPORT_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long lastProbeAt;
    private static volatile long lastHelperFailureAt;

    /** @param afterInput chamado apos cada comando aplicado (ex.: acordar a captura para o resultado aparecer logo). */
    InputHandler(AgentSettings settings, InputSupplier input, ViewportListener viewport, Runnable afterInput,
                 Consumer<String> log) {
        this.settings = settings;
        this.input = input;
        this.viewport = viewport;
        this.afterInput = afterInput;
        this.log = log;
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
        try {
            apply(m);
        } catch (Exception e) {
            log.accept("Comando remoto ignorado: " + e.getMessage());
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
            case "md": { // clique: posicao junto, para nunca cair atrasado num lugar errado
                java.awt.Point target = moveTo(Json.num(m, "x", 0), Json.num(m, "y", 0));
                in.mousePress(button(m));
                checkClickEffect(target);
                break;
            }
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

    private java.awt.Point moveTo(double nx, double ny) throws Exception {
        Rectangle lb = ScreenGeometry.logicalBounds();
        int x = lb.x + (int) Math.round(Math.max(0, Math.min(1, nx)) * (lb.width - 1));
        int y = lb.y + (int) Math.round(Math.max(0, Math.min(1, ny)) * (lb.height - 1));
        input.get().moveMouse(x, y);
        return new java.awt.Point(x, y);
    }

    /**
     * Apos um clique, procura sinais de que o Windows descartou a entrada. O {@code Robot} nao lanca erro
     * nesse caso, entao sem isto a falha ficava invisivel. Nunca derruba nem atrasa o clique.
     */
    private void checkClickEffect(java.awt.Point target) {
        try {
            java.awt.PointerInfo pi = java.awt.MouseInfo.getPointerInfo();
            if (pi != null && target != null) {
                java.awt.Point now = pi.getLocation();
                report("cursor", InputDiagnostics.explainCursor(target.x, target.y, now.x, now.y));
            }
        } catch (Throwable ignored) {
            // melhor esforco
        }
        if (!WINDOWS || !settings.nativeKeyboardHelper) {
            return; // sem PowerShell (ou maquina restrita): fica so a checagem do cursor
        }
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_INTERVAL_MS || !PROBING.compareAndSet(false, true)) {
            return;
        }
        lastProbeAt = now;
        try {
            DIAGNOSTICS_EXECUTOR.execute(() -> {
                try {
                    InputDiagnostics.Probe probe = DIAGNOSTICS.query();
                    String message = InputDiagnostics.explain(probe);
                    if (message != null) {
                        report(probe.desktopAccessible ? "uipi:" + probe.process : "desktop", message);
                    }
                    String failure = DIAGNOSTICS.takeFailure();
                    if (failure != null && System.currentTimeMillis() - lastHelperFailureAt > HELPER_FAILURE_MS) {
                        lastHelperFailureAt = System.currentTimeMillis();
                        log.accept("Diagnostico de entrada indisponivel: falha ao iniciar o auxiliar (" + failure + ").");
                    }
                } catch (Throwable ignored) {
                    // melhor esforco
                } finally {
                    PROBING.set(false);
                }
            });
        } catch (RuntimeException e) {
            PROBING.set(false);
        }
    }

    /**
     * Registra a mensagem no maximo uma vez a cada SAME_MESSAGE_MS por causa ({@code key}): a mesma causa se
     * repete a cada clique e a mensagem pode trazer valores que variam (coordenadas).
     */
    private void report(String key, String message) {
        if (message == null || key == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = LAST_REPORT_AT.get(key);
        if (previous == null || now - previous >= SAME_MESSAGE_MS) {
            LAST_REPORT_AT.put(key, now);
            log.accept(message);
        }
    }

    /** Botao do navegador (0 esquerdo, 1 meio, 2 direito) para o do InputInjector (1, 2, 3). */
    private static int button(Map<String, Object> m) {
        int b = (int) Json.num(m, "b", 0);
        return b == 1 ? 2 : b == 2 ? 3 : 1;
    }
}
