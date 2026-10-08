package com.transacao.agent;

import java.util.Map;

/** Configuracoes desta maquina, ditadas pelo painel web (o client em si nao tem tela de configuracao). */
final class AgentSettings {

    volatile boolean allowRemoteControl = true;
    volatile boolean clipboardSync = true;
    volatile boolean keepAwake = false;
    volatile boolean teamsWatcher = false;
    volatile boolean startWithSystem = true;
    volatile boolean sharedFolderSync = true;
    volatile String quality = "auto";
    volatile boolean localOnly = false;
    volatile boolean webrtcEnabled = true;
    volatile boolean nativeKeyboardHelper = true;
    volatile boolean autoUpdate = true;

    AgentSettings() {
        if (RestrictedMachineProfile.isRestrictedMachine()) {
            localOnly = true;
            webrtcEnabled = false;
            nativeKeyboardHelper = false;
            startWithSystem = false;
            autoUpdate = false;
        }
    }

    /**
     * Preferencias que o agente precisa conhecer ANTES de falar com o hub (ex.: registrar a
     * autoinicializacao ja na partida). Guardadas localmente a cada atualizacao vinda do painel.
     */
    void loadLocal(java.io.File f) {
        if (!f.isFile()) {
            return;
        }
        java.util.Properties p = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(f.toPath())) {
            p.load(in);
            startWithSystem = Boolean.parseBoolean(p.getProperty("startWithSystem", String.valueOf(startWithSystem)));
            localOnly = Boolean.parseBoolean(p.getProperty("localOnly", String.valueOf(localOnly)));
            webrtcEnabled = Boolean.parseBoolean(p.getProperty("webrtcEnabled", String.valueOf(webrtcEnabled)));
            nativeKeyboardHelper = Boolean.parseBoolean(p.getProperty("nativeKeyboardHelper", String.valueOf(nativeKeyboardHelper)));
            autoUpdate = Boolean.parseBoolean(p.getProperty("autoUpdate", String.valueOf(autoUpdate)));
        } catch (java.io.IOException ignored) {
            // sem arquivo legivel: fica no padrao
        }
    }

    void saveLocal(java.io.File f) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("startWithSystem", String.valueOf(startWithSystem));
        p.setProperty("localOnly", String.valueOf(localOnly));
        p.setProperty("webrtcEnabled", String.valueOf(webrtcEnabled));
        p.setProperty("nativeKeyboardHelper", String.valueOf(nativeKeyboardHelper));
        p.setProperty("autoUpdate", String.valueOf(autoUpdate));
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(f.toPath())) {
            p.store(out, "Preferencias locais do agente (vindas do painel)");
        } catch (java.io.IOException ignored) {
            // melhor esforco
        }
    }

    void update(Map<String, Object> m) {
        allowRemoteControl = Json.bool(m, "allowRemoteControl", allowRemoteControl);
        clipboardSync = Json.bool(m, "clipboardSync", clipboardSync);
        keepAwake = Json.bool(m, "keepAwake", keepAwake);
        teamsWatcher = Json.bool(m, "teamsWatcher", teamsWatcher);
        startWithSystem = Json.bool(m, "startWithSystem", startWithSystem);
        sharedFolderSync = Json.bool(m, "sharedFolderSync", sharedFolderSync);
        localOnly = Json.bool(m, "localOnly", localOnly);
        webrtcEnabled = Json.bool(m, "webrtcEnabled", webrtcEnabled);
        nativeKeyboardHelper = Json.bool(m, "nativeKeyboardHelper", nativeKeyboardHelper);
        autoUpdate = Json.bool(m, "autoUpdate", autoUpdate);
        String q = Json.str(m, "quality");
        if (q != null && !q.isEmpty()) {
            quality = q;
        }
    }

    /** Teto de bitrate de video (bps); o controle de congestionamento do WebRTC reduz abaixo disso conforme a rede. */
    int maxBitrate() {
        switch (quality) {
            case "max": return 60_000_000;
            case "balanced": return 12_000_000;
            case "economy": return 4_000_000;
            default: return 40_000_000; // auto: alto, o WebRTC adapta a rede
        }
    }

    /** Piso de bitrate: sem ele o codificador pode ficar contido em taxas baixas mesmo com rede sobrando. */
    int minBitrate() {
        switch (quality) {
            case "max": return 8_000_000;
            case "balanced": return 3_000_000;
            case "economy": return 500_000;
            default: return 4_000_000;
        }
    }

    int maxFps() {
        switch (quality) {
            case "economy": return 15;
            case "balanced": return 24;
            default: return 30;
        }
    }
}
