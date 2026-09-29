package com.transacao.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;

/**
 * Garante que o volume mestre do Windows esteja audivel antes de tocar a
 * sirene: desmuta se estiver mudo e sobe para 100% se estiver baixo. Usa um
 * script PowerShell temporario que fala com a API COM de audio do Windows
 * (IAudioEndpointVolume) - nao depende de nenhuma ferramenta externa.
 *
 * So faz sentido no Windows; em outros sistemas operacionais e um no-op.
 */
final class SystemVolume {

    /** Abaixo disso (escala 0..1) o volume e considerado "baixo" e sobe pra 100%. */
    private static final double LOW_VOLUME_THRESHOLD = 0.85;

    private SystemVolume() {
    }

    /** Assincrono: dispara em thread separada e nao bloqueia quem chamou. */
    static void ensureMaxVolume(Consumer<String> log) {
        if (!isWindows()) {
            return;
        }
        Thread t = new Thread(() -> runBoostScript(log), "system-volume-boost");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void runBoostScript(Consumer<String> log) {
        File script = null;
        try {
            script = File.createTempFile("transacao-volume-boost", ".ps1");
            Files.write(script.toPath(), PS_SCRIPT.getBytes(StandardCharsets.UTF_8));

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-File", script.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                if (log != null) log.accept("Ajuste de volume: script demorou demais, cancelado.");
                return;
            }
            if (log != null && !output.isEmpty()) {
                log.accept("Volume do sistema: " + output);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (log != null) {
                log.accept("Nao foi possivel ajustar o volume do sistema (" + e.getMessage() + ").");
            }
        } finally {
            if (script != null) {
                script.delete();
            }
        }
    }

    // Interop classico com a API COM IAudioEndpointVolume do Windows (sem
    // dependencias externas), exposto via Add-Type em C# dentro do PowerShell.
    private static final String PS_SCRIPT = "$ErrorActionPreference = 'Stop'\n"
            + "$code = @'\n"
            + "using System;\n"
            + "using System.Runtime.InteropServices;\n"
            + "[Guid(\"5CDF2C82-841E-4546-9722-0CF74078229A\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
            + "interface IAudioEndpointVolume {\n"
            + "    int NotImpl1();\n"
            + "    int NotImpl2();\n"
            + "    int GetChannelCount();\n"
            + "    int SetMasterVolumeLevel(float fLevelDB, Guid pguidEventContext);\n"
            + "    int SetMasterVolumeLevelScalar(float fLevel, Guid pguidEventContext);\n"
            + "    int GetMasterVolumeLevel(out float pfLevelDB);\n"
            + "    int GetMasterVolumeLevelScalar(out float pfLevel);\n"
            + "    int SetChannelVolumeLevel(uint nChannel, float fLevelDB, Guid pguidEventContext);\n"
            + "    int SetChannelVolumeLevelScalar(uint nChannel, float fLevel, Guid pguidEventContext);\n"
            + "    int GetChannelVolumeLevel(uint nChannel, out float pfLevelDB);\n"
            + "    int GetChannelVolumeLevelScalar(uint nChannel, out float pfLevel);\n"
            + "    int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, Guid pguidEventContext);\n"
            + "    int GetMute(out bool pbMute);\n"
            + "    int GetVolumeStepInfo(out uint pnStep, out uint pnStepCount);\n"
            + "    int VolumeStepUp(Guid pguidEventContext);\n"
            + "    int VolumeStepDown(Guid pguidEventContext);\n"
            + "    int QueryHardwareSupport(out uint pdwHardwareSupportMask);\n"
            + "    int GetVolumeRange(out float pflVolumeMindB, out float pflVolumeMaxdB, out float pflVolumeIncrementdB);\n"
            + "}\n"
            + "[Guid(\"D666063F-1587-4E43-81F1-B948E807363F\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
            + "interface IMMDevice {\n"
            + "    int Activate(ref Guid id, int clsCtx, IntPtr activationParams, out IAudioEndpointVolume aev);\n"
            + "}\n"
            + "[Guid(\"A95664D2-9614-4F35-A746-DE8DB63617E6\"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]\n"
            + "interface IMMDeviceEnumerator {\n"
            + "    int NotImpl1();\n"
            + "    int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);\n"
            + "}\n"
            + "[ComImport, Guid(\"BCDE0395-E52F-467C-8E3D-C4579291692E\")]\n"
            + "class MMDeviceEnumeratorComObject { }\n"
            + "public class TransacaoAudio {\n"
            + "    static IAudioEndpointVolume Vol() {\n"
            + "        var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());\n"
            + "        IMMDevice dev;\n"
            + "        Marshal.ThrowExceptionForHR(enumerator.GetDefaultAudioEndpoint(0, 1, out dev));\n"
            + "        IAudioEndpointVolume epv;\n"
            + "        Guid epvid = typeof(IAudioEndpointVolume).GUID;\n"
            + "        Marshal.ThrowExceptionForHR(dev.Activate(ref epvid, 23, IntPtr.Zero, out epv));\n"
            + "        return epv;\n"
            + "    }\n"
            + "    public static float GetVolume() { float v; Marshal.ThrowExceptionForHR(Vol().GetMasterVolumeLevelScalar(out v)); return v; }\n"
            + "    public static void SetVolume(float v) { Marshal.ThrowExceptionForHR(Vol().SetMasterVolumeLevelScalar(v, Guid.Empty)); }\n"
            + "    public static bool GetMute() { bool m; Marshal.ThrowExceptionForHR(Vol().GetMute(out m)); return m; }\n"
            + "    public static void SetMute(bool m) { Marshal.ThrowExceptionForHR(Vol().SetMute(m, Guid.Empty)); }\n"
            + "}\n"
            + "'@\n"
            + "Add-Type -TypeDefinition $code -Language CSharp\n"
            + "$changed = @()\n"
            + "if ([TransacaoAudio]::GetMute()) {\n"
            + "    [TransacaoAudio]::SetMute($false)\n"
            + "    $changed += 'desmutado'\n"
            + "}\n"
            + "$vol = [TransacaoAudio]::GetVolume()\n"
            + "if ($vol -lt " + LOW_VOLUME_THRESHOLD + ") {\n"
            + "    [TransacaoAudio]::SetVolume(1.0)\n"
            + "    $changed += 'volume subido para 100% (estava ' + [math]::Round($vol * 100) + '%)'\n"
            + "}\n"
            + "if ($changed.Count -gt 0) { Write-Output ($changed -join ', ') }\n";
}
