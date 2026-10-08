package com.transacao.agent;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Testes (sem rede, sem tela) da seguranca da auto-atualizacao. java -cp out;test-out ...AgentUpdaterTest */
public final class AgentUpdaterTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        KeyPair good = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] data = "pacote de atualizacao".getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(data, good);

        check("assinatura valida e aceita", AgentUpdater.verifySignature(data, sig, good.getPublic()));
        byte[] tampered = data.clone();
        tampered[0] ^= 1;
        check("pacote adulterado e recusado", !AgentUpdater.verifySignature(tampered, sig, good.getPublic()));
        check("assinatura de OUTRA chave e recusada", !AgentUpdater.verifySignature(data, sig, other.getPublic()));
        check("assinatura truncada/lixo e recusada", !AgentUpdater.verifySignature(data, new byte[] {1, 2, 3}, good.getPublic()));
        check("chave publica embutida carrega", AgentUpdater.loadPublicKey(AgentUpdater.PUBLIC_KEY_B64) != null);

        Map<String, byte[]> ok = AgentUpdater.readBundle(zip("transacao-agent.jar", "jar", "lib/x.jar", "lib"));
        check("zip normal e lido (2 arquivos)", ok.size() == 2 && ok.containsKey("lib/x.jar"));
        check("zip-slip '../' e recusado", throwsOnBundle(zip("../fora.txt", "x")));
        check("zip-slip '..\\' e recusado", throwsOnBundle(zip("..\\fora.txt", "x")));
        check("caminho absoluto e recusado", throwsOnBundle(zip("/etc/passwd", "x")));
        check("caminho com unidade (C:) e recusado", throwsOnBundle(zip("C:/Windows/x.dll", "x")));

        check("wss -> https", AgentUpdater.httpBase("wss://hub.tththiago.com.br/agent").equals("https://hub.tththiago.com.br"));
        check("ws local -> http com porta", AgentUpdater.httpBase("ws://127.0.0.1:8099/agent").equals("http://127.0.0.1:8099"));
        check("sha256 conhecido", AgentUpdater.sha256("abc".getBytes(StandardCharsets.UTF_8))
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));

        java.io.File tmp = java.nio.file.Files.createTempDirectory("upd").toFile();
        java.nio.file.Files.writeString(new java.io.File(tmp, AgentUpdater.BAD_VERSIONS).toPath(), "deadbeef\r\nfeedface\r\n");
        check("versao marcada como ruim e reconhecida", AgentUpdater.isBadVersion(tmp, "feedface") && AgentUpdater.isBadVersion(tmp, "deadbeef"));
        check("outra versao nao e ruim", !AgentUpdater.isBadVersion(tmp, "cafebabe"));
        new java.io.File(tmp, AgentUpdater.BAD_VERSIONS).delete();
        tmp.delete();

        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static byte[] sign(byte[] data, KeyPair kp) throws Exception {
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    private static byte[] zip(String... namesAndContents) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                z.putNextEntry(new ZipEntry(namesAndContents[i]));
                z.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static boolean throwsOnBundle(byte[] zip) {
        try {
            AgentUpdater.readBundle(zip);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
