package com.transacao.common;

import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Testa o MECANISMO da senha da primeira execucao (PBKDF2 salgado) sem gravar a senha real em lugar
 * nenhum: usa uma senha de teste propria e confere que as constantes embutidas tem a forma de um hash.
 */
public final class FirstRunGateTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        int iter = 20_000;
        char[] pw = "senha-de-teste".toCharArray();
        byte[] hash = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new javax.crypto.spec.PBEKeySpec(pw, salt, iter, 256)).getEncoded();
        String s64 = Base64.getEncoder().encodeToString(salt);
        String h64 = Base64.getEncoder().encodeToString(hash);

        check("senha correta e aceita", FirstRunGate.matches("senha-de-teste".toCharArray(), s64, iter, h64));
        check("senha errada e recusada", !FirstRunGate.matches("senha-de-testE".toCharArray(), s64, iter, h64));
        check("senha com 1 caractere a mais e recusada", !FirstRunGate.matches("senha-de-teste!".toCharArray(), s64, iter, h64));
        check("senha vazia e recusada", !FirstRunGate.matches(new char[0], s64, iter, h64));
        check("sal diferente nao valida", !FirstRunGate.matches("senha-de-teste".toCharArray(), Base64.getEncoder().encodeToString(new byte[16]), iter, h64));
        check("hash corrompido nao quebra (recusa)", !FirstRunGate.matches("x".toCharArray(), s64, iter, "nao-e-base64!!"));

        // as constantes REAIS embutidas: formato de hash PBKDF2 (nao texto da senha) e custo minimo
        Field fi = FirstRunGate.class.getDeclaredField("PASSWORD_ITERATIONS");
        Field fs = FirstRunGate.class.getDeclaredField("PASSWORD_SALT_B64");
        Field fh = FirstRunGate.class.getDeclaredField("PASSWORD_HASH_B64");
        fi.setAccessible(true);
        fs.setAccessible(true);
        fh.setAccessible(true);
        check("embutido: 100 mil iteracoes ou mais", fi.getInt(null) >= 100_000);
        check("embutido: sal de 16 bytes", Base64.getDecoder().decode((String) fs.get(null)).length == 16);
        check("embutido: hash de 32 bytes", Base64.getDecoder().decode((String) fh.get(null)).length == 32);

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
