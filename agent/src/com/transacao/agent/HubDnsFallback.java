package com.transacao.agent;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.stream.Stream;

/**
 * Resolvedor de DNS que so atua quando o DNS do sistema falha para o host do hub (VPN corporativa
 * troca o DNS e o nome interno deixa de existir). Nesse caso devolve o IP configurado em hub.ip,
 * mantendo a URL com o nome: SNI, Host e validacao do certificado continuam corretos.
 * Exige Java 18+ (InetAddressResolverProvider); em versoes anteriores o SPI e ignorado.
 */
public final class HubDnsFallback extends InetAddressResolverProvider {

    private static volatile String host = "";
    private static volatile InetAddress address;

    /** Chamado no inicio do agente, antes de qualquer conexao. IP invalido ou vazio desativa o fallback. */
    public static void configure(String hubHost, String hubIp) {
        address = null;
        host = "";
        if (hubHost == null || hubHost.isBlank() || hubIp == null || hubIp.isBlank()) return;
        String ip = hubIp.trim();
        // So literal de IP: nunca dispara uma resolucao de nome a partir da configuracao.
        if (!ip.matches("[0-9.]+") && !ip.contains(":")) return;
        try {
            address = InetAddress.getByName(ip);
            host = hubHost.trim().toLowerCase();
        } catch (UnknownHostException e) {
            address = null;
        }
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String name, LookupPolicy policy) throws UnknownHostException {
                try {
                    return builtin.lookupByName(name, policy);
                } catch (UnknownHostException e) {
                    InetAddress fallback = address;
                    if (fallback != null && name.equalsIgnoreCase(host)) {
                        return Stream.of(InetAddress.getByAddress(name, fallback.getAddress()));
                    }
                    throw e;
                }
            }

            @Override
            public String lookupByAddress(byte[] addr) throws UnknownHostException {
                return builtin.lookupByAddress(addr);
            }
        };
    }

    @Override
    public String name() {
        return "hub-dns-fallback";
    }
}
