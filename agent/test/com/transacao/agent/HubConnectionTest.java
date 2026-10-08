package com.transacao.agent;

import java.net.Proxy;
import java.net.URI;
import java.util.List;

/** Teste puro: a rota LAN jamais pode herdar PAC/proxy configurado no sistema. */
public final class HubConnectionTest {
    public static void main(String[] args) throws Exception {
        List<Proxy> selected = HubConnection.LAN_DIRECT_PROXY.select(new URI("wss://hub-int.tththiago.com.br/agent"));
        boolean direct = selected.size() == 1 && selected.get(0).type() == Proxy.Type.DIRECT;
        System.out.println((direct ? "  ok  " : "  FALHOU  ") + "LAN ignora proxy/PAC e disca diretamente");
        System.exit(direct ? 0 : 1);
    }
}
