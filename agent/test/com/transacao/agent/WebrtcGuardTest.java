package com.transacao.agent;

import java.io.File;
import java.nio.file.Files;

/** Testes (sem tocar em nada do sistema) do detector de bloqueio do WebRTC. */
public final class WebrtcGuardTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        String on = "\r\nHKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\CI\\Policy\r\n    VerifiedAndReputablePolicyState    REG_DWORD    0x1\r\n\r\n";
        String off = "    VerifiedAndReputablePolicyState    REG_DWORD    0x0";
        String eval = "    VerifiedAndReputablePolicyState    REG_DWORD    0x2";
        check("estado 1 (bloqueio) e lido", WebrtcGuard.parseState(on) == 1);
        check("estado 0 (desligado) e lido", WebrtcGuard.parseState(off) == 0);
        check("estado 2 (avaliacao) e lido", WebrtcGuard.parseState(eval) == 2);
        check("saida de erro/vazia vira -1 (nao assume bloqueio)", WebrtcGuard.parseState("ERRO: nao foi possivel localizar") == -1 && WebrtcGuard.parseState(null) == -1);

        File dir = Files.createTempDirectory("guard").toFile();
        check("sem marcador: nao ha bloqueio anterior", !WebrtcGuard.blockedBefore(dir, "abc"));
        WebrtcGuard.markBlocked(dir, "abc");
        check("bloqueio lembrado para a mesma versao", WebrtcGuard.blockedBefore(dir, "abc"));
        check("versao nova tenta de novo", !WebrtcGuard.blockedBefore(dir, "def"));
        check("hash vazio (dev) nunca bloqueia", !WebrtcGuard.blockedBefore(dir, ""));
        new File(dir, "webrtc-blocked.txt").delete();
        dir.delete();

        System.out.println("smartAppControlEnforced() nesta maquina: " + WebrtcGuard.smartAppControlEnforced());
        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
