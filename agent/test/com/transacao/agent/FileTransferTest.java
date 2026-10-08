package com.transacao.agent;

import java.io.File;

/** Testes sem rede/tela do destino fixo de transferencias recebidas. */
public final class FileTransferTest {
    public static void main(String[] args) {
        expect(new File("C:\\Users\\ThiagoMaglioniMagalh", "recebimentos"),
                FileTransfer.receiptsDirectory("C:\\Users\\ThiagoMaglioniMagalh", "C:\\fallback"),
                "Windows usa a pasta do usuario, sem depender do OneDrive");
        expect(new File("/Users/thiago", "recebimentos"), FileTransfer.receiptsDirectory("", "/Users/thiago"),
                "fallback usa user.home");
        System.out.println("TODOS OS TESTES PASSARAM");
    }

    private static void expect(File expected, File actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": esperado " + expected + ", recebido " + actual);
        }
        System.out.println("  ok " + label);
    }
}
