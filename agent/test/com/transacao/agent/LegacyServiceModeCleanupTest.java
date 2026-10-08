package com.transacao.agent;

import java.io.File;
import java.nio.file.Files;

/** Teste puro: nao executa UAC, servico, MSI, certificado ou bcdedit. */
public final class LegacyServiceModeCleanupTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("legacy-service").toFile();
        File exe = new File(dir, "TransacaoAgentService.exe");
        File cert = new File(dir, "DriverCertificate.cer");
        File msi = new File(dir, "virtual-display.msi");
        Files.writeString(exe.toPath(), "x"); Files.writeString(cert.toPath(), "x"); Files.writeString(msi.toPath(), "x");
        String s = LegacyServiceModeCleanup.script(exe, cert, msi);
        if (!s.contains(" uninstall") || !s.contains("'/x'") || !s.contains("testsigning off") || !s.contains("Remove-Item")) throw new AssertionError("script de reversao incompleto");
        if (!LegacyServiceModeCleanup.hasLegacyArtifacts(dir)) throw new AssertionError("artefatos legados devem disparar a limpeza");
        exe.delete(); cert.delete(); msi.delete(); dir.delete();
        System.out.println("TODOS OS TESTES PASSARAM");
    }
}
