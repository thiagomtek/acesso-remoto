package com.transacao.common;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * Monta um SSLContext com autenticacao mutua (client + server), a partir de
 * um keystore (contendo o par de chaves proprio) e um truststore (contendo o
 * certificado publico da outra ponta).
 */
public final class SSLContextFactory {

    private SSLContextFactory() {
    }

    public static SSLContext create(String keystorePath, char[] keystorePassword,
                                     String truststorePath, char[] truststorePassword) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream is = new FileInputStream(keystorePath)) {
            keyStore.load(is, keystorePassword);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword);

        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream is = new FileInputStream(truststorePath)) {
            trustStore.load(is, truststorePassword);
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);

        SSLContext context = SSLContext.getInstance("TLSv1.2");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
        return context;
    }
}
