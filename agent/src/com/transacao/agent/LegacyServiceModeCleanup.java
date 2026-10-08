package com.transacao.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Migracao de REMOCAO do experimento legado de modo servico. Nao instala nem baixa nada: so remove
 * o WinSW, driver virtual, certificado e test-signing que versoes anteriores deste agente criaram.
 */
final class LegacyServiceModeCleanup {
    private static final String SERVICE_ID = "TransacaoAgentService";

    private LegacyServiceModeCleanup() { }

    static boolean isPresent() {
        if (!isWindows()) return false;
        File workDir = new File(System.getenv("LOCALAPPDATA"), "TransacaoAgent\\service-mode");
        if (hasLegacyArtifacts(workDir)) return true;
        try {
            Process p = new ProcessBuilder("sc", "query", SERVICE_ID).redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void applyAsync(Consumer<String> log) {
        Thread t = new Thread(() -> {
            try {
                remove(log);
            } catch (Exception e) {
                log.accept("Migracao do modo servico: falhou ao reverter (" + e.getMessage() + ")");
            }
        }, "legacy-service-mode-cleanup");
        t.setDaemon(true);
        t.start();
    }

    private static void remove(Consumer<String> log) throws Exception {
        File workDir = new File(System.getenv("LOCALAPPDATA"), "TransacaoAgent\\service-mode");
        File exe = new File(workDir, SERVICE_ID + ".exe");
        File cert = new File(new File(workDir, "vdd"), "DriverCertificate.cer");
        File msi = firstMsi(new File(workDir, "vdd"));
        workDir.mkdirs();
        File script = new File(workDir, "remove-legacy-service-mode.ps1");
        File result = new File(workDir, "remove-legacy-service-mode-result.txt");
        Files.writeString(script.toPath(), script(exe, cert, msi), StandardCharsets.UTF_8);
        Files.deleteIfExists(result.toPath());
        log.accept("Migracao do modo servico: aguardando confirmacao de administrador (UAC).");
        List<String> cmd = List.of("powershell", "-NoProfile", "-Command",
                "Start-Process powershell -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','"
                        + script.getAbsolutePath().replace("'", "''") + "') -Verb RunAs -Wait");
        new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.MINUTES);
        String text = result.isFile() ? Files.readString(result.toPath(), StandardCharsets.UTF_8).trim() : "";
        log.accept("Migracao do modo servico: resultado:\n" + text);
    }

    static String script(File exe, File cert, File msi) {
        StringBuilder out = new StringBuilder()
                .append("$ErrorActionPreference = 'Continue'\n")
                .append("$log = Join-Path (Split-Path $PSCommandPath) 'remove-legacy-service-mode-result.txt'\n")
                .append("'' | Out-File -FilePath $log -Encoding utf8\n")
                .append("function Log($m) { $m | Out-File -FilePath $log -Append -Encoding utf8 }\n")
                .append("$ok = $true\n")
                .append("Log '== remover servico legado == '\n");
        if (exe.isFile()) {
            out.append("& ").append(q(exe.getAbsolutePath())).append(" stop 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("& ").append(q(exe.getAbsolutePath())).append(" uninstall 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("if ($LASTEXITCODE -ne 0) { $ok = $false; Log 'Falhou ao desinstalar o servico legado.' }\n");
        } else {
            out.append("sc.exe stop ").append(SERVICE_ID).append(" 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("sc.exe delete ").append(SERVICE_ID).append(" 2>&1 | ForEach-Object { Log $_ }\n");
        }
        if (msi != null && msi.isFile()) {
            out.append("Log '== remover driver virtual == '\n")
                    .append("& msiexec.exe '/x' ").append(q(msi.getAbsolutePath()))
                    .append(" '/qn' '/norestart' 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("$msiExit = $LASTEXITCODE; Log ('msiexec exit code: ' + $msiExit)\n")
                    .append("if ($msiExit -notin @(0, 1605, 3010)) { $ok = $false }\n");
        }
        if (cert.isFile()) {
            out.append("$thumb = (New-Object System.Security.Cryptography.X509Certificates.X509Certificate2 ")
                    .append(q(cert.getAbsolutePath())).append(").Thumbprint\n")
                    .append("certutil -delstore root $thumb 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("if ($LASTEXITCODE -ne 0) { $ok = $false }\n")
                    .append("certutil -delstore TrustedPublisher $thumb 2>&1 | ForEach-Object { Log $_ }\n")
                    .append("if ($LASTEXITCODE -ne 0) { $ok = $false }\n");
        }
        return out.append("bcdedit /set testsigning off 2>&1 | ForEach-Object { Log $_ }\n")
                .append("if ($LASTEXITCODE -ne 0) { $ok = $false }\n")
                .append("if ($ok) { Remove-Item -LiteralPath ").append(q(exe.getAbsolutePath())).append(", ")
                .append(q(cert.getAbsolutePath())).append(", ")
                .append(msi == null ? "''" : q(msi.getAbsolutePath()))
                .append(" -Force -ErrorAction SilentlyContinue } else { Log 'Artefatos preservados para nova tentativa.' }\n")
                .append("Log '== FIM == '\n").toString();
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    private static File firstMsi(File dir) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".msi"));
        return files != null && files.length > 0 ? files[0] : null;
    }
    static boolean hasLegacyArtifacts(File workDir) {
        return new File(workDir, SERVICE_ID + ".exe").isFile()
                || new File(new File(workDir, "vdd"), "DriverCertificate.cer").isFile()
                || firstMsi(new File(workDir, "vdd")) != null;
    }
    private static String q(String value) { return "'" + value.replace("'", "''") + "'"; }
}
