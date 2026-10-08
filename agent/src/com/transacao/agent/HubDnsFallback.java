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
    private static volatile boolean exclusive = false;

    /** Chamado no inicio do agente, antes de qualquer conexao. IP invalido ou vazio desativa o fallback. */
    public static void configure(String hubHost, String hubIp) {
        configure(hubHost, hubIp, false);
    }

    /** Quando forceExclusive e true, o host informado resolve sempre para o IP fixo sem consultar DNS externo. */
    public static void configure(String hubHost, String hubIp, boolean forceExclusive) {
        address = null;
        host = "";
        exclusive = forceExclusive;
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

    public static boolean isExclusive() {
        return exclusive;
    }

    public static void setForceExclusive(boolean forceExclusive, String hubHost, String hubIp) {
        configure(hubHost, hubIp, forceExclusive);
    }

    @Override
    public InetAddressResolver get(Configuration configuration) {
        return resolver(configuration.builtinResolver());
    }

    static InetAddressResolver resolver(InetAddressResolver builtin) {
        return new InetAddressResolver() {
            @Override
            public Stream<InetAddress> lookupByName(String name, LookupPolicy policy) throws UnknownHostException {
                InetAddress target = address;
                if (exclusive && target != null && (host.isEmpty() || name.equalsIgnoreCase(host))) {
                    return Stream.of(InetAddress.getByAddress(name, target.getAddress()));
                }
                try {
                    return builtin.lookupByName(name, policy);
                } catch (UnknownHostException e) {
                    if (target != null && name.equalsIgnoreCase(host)) {
                        return Stream.of(InetAddress.getByAddress(name, target.getAddress()));
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
