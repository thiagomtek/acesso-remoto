package com.transacao.common.discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Fica ouvindo pedidos de descoberta por UDP broadcast na rede local e
 * responde com a porta TCP do servidor, para o Client nao precisar digitar
 * o IP manualmente.
 */
public class DiscoveryServer implements Runnable {

    public static final int DISCOVERY_PORT = 9445;
    private static final String REQUEST = "TRANSACAO_DISCOVER_V1";
    private static final String RESPONSE_PREFIX = "TRANSACAO_SERVER_V1|";

    private final int tcpPort;
    private volatile boolean running = true;
    private DatagramSocket socket;

    public DiscoveryServer(int tcpPort) {
        this.tcpPort = tcpPort;
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(DISCOVERY_PORT);
            socket.setBroadcast(true);
            byte[] buffer = new byte[256];
            while (running) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                if (REQUEST.equals(message)) {
                    String hostName;
                    try {
                        hostName = InetAddress.getLocalHost().getHostName();
                    } catch (Exception e) {
                        hostName = "servidor";
                    }
                    byte[] response = (RESPONSE_PREFIX + tcpPort + "|" + hostName).getBytes(StandardCharsets.UTF_8);
                    DatagramPacket reply = new DatagramPacket(
                            response, response.length, packet.getAddress(), packet.getPort());
                    socket.send(reply);
                }
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("Descoberta encerrada: " + e.getMessage());
            }
        }
    }
}
