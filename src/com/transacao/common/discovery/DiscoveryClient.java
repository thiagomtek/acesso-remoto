package com.transacao.common.discovery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Varre a rede local por broadcast UDP procurando um Transacao - Servidor,
 * repetindo indefinidamente ate encontrar um ou ate ser interrompido com
 * stop(). Assim o usuario nao precisa digitar o IP do servidor manualmente.
 */
public class DiscoveryClient {

    private static final String REQUEST = "TRANSACAO_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "TRANSACAO_SERVER_V1|";
    private static final long RETRY_INTERVAL_MILLIS = 30_000;

    public static class Found {
        public final String host;
        public final int port;
        public final String name;

        public Found(String host, int port, String name) {
            this.host = host;
            this.port = port;
            this.name = name;
        }
    }

    public interface Callback {
        void onAttempt();

        void onFound(Found found);
    }

    private volatile boolean running = true;

    public void stop() {
        running = false;
    }

    /** Bloqueia (rode em uma thread separada) ate encontrar um servidor ou stop() ser chamado. */
    public void searchUntilFound(Callback callback) {
        byte[] requestBytes = REQUEST.getBytes(StandardCharsets.UTF_8);
        while (running) {
            if (callback != null) {
                callback.onAttempt();
            }
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.setSoTimeout(1500);

                Set<InetAddress> targets = new LinkedHashSet<>();
                targets.addAll(listBroadcastAddresses());
                targets.addAll(listTailscalePeerAddresses());

                for (InetAddress target : targets) {
                    try {
                        DatagramPacket request = new DatagramPacket(
                                requestBytes, requestBytes.length, target, DiscoveryServer.DISCOVERY_PORT);
                        socket.send(request);
                    } catch (IOException ignored) {
                    }
                }

                byte[] buffer = new byte[256];
                long deadline = System.currentTimeMillis() + 1500;
                while (running && System.currentTimeMillis() < deadline) {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    try {
                        socket.receive(response);
                    } catch (SocketTimeoutException timeout) {
                        break;
                    }
                    String message = new String(response.getData(), 0, response.getLength(), StandardCharsets.UTF_8);
                    if (message.startsWith(RESPONSE_PREFIX)) {
                        String[] parts = message.substring(RESPONSE_PREFIX.length()).split("\\|", 2);
                        int port = Integer.parseInt(parts[0]);
                        String name = parts.length > 1 ? parts[1] : "";
                        if (callback != null) {
                            callback.onFound(new Found(response.getAddress().getHostAddress(), port, name));
                        }
                        return;
                    }
                }
            } catch (IOException ignored) {
            }

            waitBeforeRetry(RETRY_INTERVAL_MILLIS);
        }
    }

    /** Espera ate o intervalo passar, mas verifica running() em passos curtos para responder rapido a stop(). */
    private void waitBeforeRetry(long millis) {
        long remaining = millis;
        while (running && remaining > 0) {
            long step = Math.min(200, remaining);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            remaining -= step;
        }
    }

    private List<InetAddress> listBroadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                for (InterfaceAddress ifAddr : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = ifAddr.getBroadcast();
                    if (broadcast != null) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        if (result.isEmpty()) {
            try {
                result.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * O Tailscale (e VPNs mesh parecidas) nao propaga broadcast entre os
     * peers, entao a busca por broadcast normal nao alcanca maquinas
     * conectadas so por ele. Aqui perguntamos ao cliente Tailscale local
     * (comando "tailscale status") o IP de cada peer conhecido e mandamos
     * o pedido de descoberta diretamente (unicast) para cada um.
     * Se o Tailscale nao estiver instalado, isso simplesmente nao encontra
     * nada e a busca continua normalmente pela rede local.
     */
    private List<InetAddress> listTailscalePeerAddresses() {
        List<InetAddress> result = new ArrayList<>();
        Pattern tailscaleIpPattern = Pattern.compile("\\b100\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
        try {
            Process process = new ProcessBuilder("tailscale", "status")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = tailscaleIpPattern.matcher(line);
                    if (matcher.find()) {
                        try {
                            result.add(InetAddress.getByName(matcher.group()));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // tailscale nao instalado ou nao disponivel no PATH; ignora silenciosamente
        }
        return result;
    }
}
