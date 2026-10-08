package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/**
 * Configuracao do agente. Dois arquivos, ambos no perfil do usuario (nao precisa de admin):
 * - agent.properties (fornecido pelo instalador): URL do hub e Service Token da Cloudflare Access.
 * - identity.properties (gerado sozinho no primeiro uso): ID e segredo desta maquina.
 */
public final class AgentConfig {

    public static final String DEFAULT_HUB_URL = "wss://servidor.tail074692.ts.net:8787/agent";
    public static final String DEFAULT_LAN_HUB_URL = DEFAULT_HUB_URL;
    /** Dominios do Cloudflare (desligado): agent.properties antigos sao redirecionados ao Tailscale. */
    private static final String LEGACY_HOST_MARK = ".tththiago.com.br/";

    public final String hubUrl;
    /** Endpoint TLS interno. Falha rapido e o agente conserva a rota Cloudflare. */
    public final String lanHubUrl;
    /** IP do hub usado so se o DNS falhar para o host de hubUrl (ex.: DNS da VPN corporativa). Vazio = desligado. */
    public final String hubIp;
    /** Service Token da Cloudflare Access; vazio em desenvolvimento local (hub com INSECURE_DEV). */
    public final String accessClientId;
    public final String accessClientSecret;
    /** Matricula opaca do instalador; usada uma unica vez para vincular uma maquina nova a conta. */
    public final String enrollmentToken;
    public final String clientId;
    public final String clientSecret;
    public final File dir;
    public final boolean restricted;

    AgentConfig(String hubUrl, String lanHubUrl, String hubIp, String accessId, String accessSecret, String enrollmentToken,
            String clientId, String clientSecret, File dir) {
        this(hubUrl, lanHubUrl, hubIp, accessId, accessSecret, enrollmentToken, clientId, clientSecret, dir, false);
    }

    AgentConfig(String hubUrl, String lanHubUrl, String hubIp, String accessId, String accessSecret, String enrollmentToken,
            String clientId, String clientSecret, File dir, boolean restricted) {
        this.hubUrl = hubUrl;
        this.lanHubUrl = lanHubUrl;
        this.hubIp = restricted ? RestrictedMachineProfile.SERVER_INTERNAL_IP : hubIp;
        this.accessClientId = accessId;
        this.accessClientSecret = accessSecret;
        this.enrollmentToken = enrollmentToken;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.dir = dir;
        this.restricted = restricted;
    }

    public static File defaultDir() {
        String override = System.getenv("TRANSACAO_AGENT_HOME"); // usado em testes e instalacoes especiais
        if (override != null && !override.trim().isEmpty()) {
            return new File(override.trim());
        }
        return new File(System.getProperty("user.home"), ".transacao-agent");
    }

    /** Procura agent.properties ao lado do jar (instalador) e depois no diretorio do agente; variaveis de ambiente prevalecem. */
    public static AgentConfig load(File dir, File jarDir) throws IOException {
        dir.mkdirs();
        Properties p = new Properties();
        for (File f : new File[] {new File(jarDir, "agent.properties"), new File(dir, "agent.properties")}) {
            if (f.isFile()) {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    p.load(in);
                }
                break;
            }
        }
        String hub = firstNonEmpty(System.getenv("HUB_URL"), p.getProperty("hub.url"), DEFAULT_HUB_URL);
        String lanHub = firstNonEmpty(System.getenv("LAN_HUB_URL"), p.getProperty("lan.hubUrl"), DEFAULT_LAN_HUB_URL);
        if (hub.contains(LEGACY_HOST_MARK)) hub = DEFAULT_HUB_URL;
        if (lanHub.contains(LEGACY_HOST_MARK)) lanHub = DEFAULT_LAN_HUB_URL;
        String hubIp = firstNonEmpty(System.getenv("HUB_IP"), p.getProperty("hub.ip"), "");
        String accessId =firstNonEmpty(System.getenv("CF_ACCESS_CLIENT_ID"), p.getProperty("access.clientId"), "");
        String accessSecret = firstNonEmpty(System.getenv("CF_ACCESS_CLIENT_SECRET"), p.getProperty("access.clientSecret"), "");
        String enrollmentToken = firstNonEmpty(p.getProperty("account.enrollmentToken"), "");

        Properties id = new Properties();
        File idFile = new File(dir, "identity.properties");
        if (idFile.isFile()) {
            try (InputStream in = Files.newInputStream(idFile.toPath())) {
                id.load(in);
            }
        }
        if (id.getProperty("id") == null || id.getProperty("secret") == null || id.getProperty("secret").length() < 32) {
            id.setProperty("id", UUID.randomUUID().toString());
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            id.setProperty("secret", Base64.getUrlEncoder().withoutPadding().encodeToString(raw));
            try (OutputStream out = Files.newOutputStream(idFile.toPath())) {
                id.store(out, "Identidade desta maquina no hub. Nao compartilhe.");
            }
            restrictToOwner(idFile);
        }
        boolean isRestricted = RestrictedMachineProfile.isRestrictedMachine();
        if (isRestricted) {
            hubIp = RestrictedMachineProfile.SERVER_INTERNAL_IP;
        }
        return new AgentConfig(hub, lanHub, hubIp, accessId, accessSecret, enrollmentToken,
                id.getProperty("id"), id.getProperty("secret"), dir, isRestricted);
    }

    private static void restrictToOwner(File f) {
        try {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows: o arquivo ja fica no perfil do usuario (ACL herdada da pasta pessoal).
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return "";
    }
}
