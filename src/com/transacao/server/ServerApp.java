package com.transacao.server;

import com.transacao.common.FileTransferReceiver;
import com.transacao.common.FileTransferSender;
import com.transacao.common.SSLContextFactory;
import com.transacao.common.TransferListener;
import com.transacao.common.JarUtils;
import com.transacao.common.discovery.DiscoveryServer;
import com.transacao.common.remote.AudioChannelListener;
import com.transacao.common.remote.AudioDevices;
import com.transacao.common.remote.AudioFormats;
import com.transacao.common.remote.AudioPlayer;
import com.transacao.common.remote.AudioStreamer;
import com.transacao.common.remote.ClipboardSync;
import com.transacao.common.remote.RemoteControlListener;
import com.transacao.common.remote.RemoteFrameListener;
import com.transacao.common.remote.RemoteInputSender;
import com.transacao.common.remote.RemoteMessageSender;
import com.transacao.common.remote.RemoteViewerPanel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import com.transacao.common.Protocol;

/**
 * Aplicacao servidor: fica ouvindo em uma porta SSL, aceita a conexao do
 * client e, a partir dai, pode tanto enviar quanto receber arquivos/pastas
 * pela mesma conexao (comunicacao de mao dupla).
 */
public class ServerApp extends JFrame {

    private final JTextField portField = new JTextField("9444", 6);
    private final JTextField keystoreField = new JTextField("certs/server.jks", 22);
    private final JPasswordField keystorePassField = new JPasswordField("changeit", 10);
    private final JTextField truststoreField = new JTextField("certs/server-truststore.jks", 22);
    private final JPasswordField truststorePassField = new JPasswordField("changeit", 10);
    private final JTextField outputDirField = new JTextField("recebidos_server", 22);
    private final JTextField updateJarField = new JTextField(resolveDefaultUpdateJar(), 22);

    private final JButton startButton = new JButton("Iniciar servidor");
    private final JButton stopButton = new JButton("Parar servidor");
    private final JButton chooseButton = new JButton("Selecionar arquivo .zip ou pasta...");
    private final JButton sendButton = new JButton("Enviar");
    private final JLabel selectedLabel = new JLabel("Nada selecionado");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Parado");

    private final DefaultComboBoxModel<ClientSession> clientListModel = new DefaultComboBoxModel<>();
    private final JComboBox<ClientSession> clientSelector = new JComboBox<>(clientListModel);
    private final JButton disconnectClientButton = new JButton("Desconectar client selecionado");
    private final JLabel clientListLabel = new JLabel("0 client(s) conectado(s)");
    private final List<ClientSession> sessions = new ArrayList<>();
    private ClientSession activeSession;

    private Object writeLock = new Object();
    private final RemoteViewerPanel remoteViewerPanel = new RemoteViewerPanel();
    private final JButton remoteStartButton = new JButton("Iniciar controle remoto");
    private final JButton remoteStopButton = new JButton("Parar controle remoto");
    private final JLabel remoteStatusLabel = new JLabel("Sem client conectado");

    private final JComboBox<Mixer.Info> micDeviceCombo = new JComboBox<>(
            AudioDevices.listCaptureDevices().toArray(new Mixer.Info[0]));
    private final JButton micStartButton = new JButton("Compartilhar microfone");
    private final JButton micStopButton = new JButton("Parar microfone");
    private final JLabel micStatusLabel = new JLabel("Microfone parado");

    private final JComboBox<Mixer.Info> remoteAudioOutputCombo = new JComboBox<>(
            AudioDevices.listPlaybackDevices().toArray(new Mixer.Info[0]));
    private final JLabel remoteAudioStatusLabel = new JLabel("Sem audio remoto");
    private final AudioPlayer remoteAudioPlayer = new AudioPlayer();

    private ServerSocket serverSocket;
    private DiscoveryServer discoveryServer;
    private Thread discoveryServerThread;
    private DataOutputStream out;
    private ClipboardSync clipboardSync;
    private AudioStreamer micStreamer;
    private Thread micStreamerThread;

    public ServerApp() {
        super("Transacao - Servidor");
        buildUi();
        wireActions();
        refreshTransferUi();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel clientBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        clientBar.setBorder(BorderFactory.createTitledBorder("Client ativo"));
        clientSelector.setPrototypeDisplayValue(new ClientSession(null, null, null, "000.000.000.000:00000 (nenhum)"));
        clientBar.add(clientSelector);
        clientBar.add(disconnectClientButton);
        clientBar.add(clientListLabel);
        add(clientBar, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Transferencia de Arquivos", buildTransferTab());
        tabs.addTab("Acesso Remoto", buildRemoteTab());
        tabs.addTab("Audio", buildAudioTab());
        add(tabs, BorderLayout.CENTER);

        // Usa a resolucao FISICA do monitor (igual ao ScreenStreamer), em vez de
        // setExtendedState(MAXIMIZED_BOTH) - em maquinas com escala do Windows
        // diferente de 100%, o "maximizado" do Swing pode calcular o tamanho em
        // pixels logicos (menores), deixando sobra de tela nao coberta pela janela.
        GraphicsDevice device = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        DisplayMode mode = device.getDisplayMode();
        setBounds(0, 0, mode.getWidth(), mode.getHeight());
    }

    private JPanel buildTransferTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("Configuracao"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        addRow(configPanel, c, row++, "Porta:", portField);
        addRow(configPanel, c, row++, "Keystore (server):", keystoreField);
        addRow(configPanel, c, row++, "Senha keystore:", keystorePassField);
        addRow(configPanel, c, row++, "Truststore (confia no client):", truststoreField);
        addRow(configPanel, c, row++, "Senha truststore:", truststorePassField);
        addRow(configPanel, c, row++, "Pasta de saida (recebidos):", outputDirField);
        addRow(configPanel, c, row++, "Jar do client mais recente (auto-update):", updateJarField);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(startButton);
        controlPanel.add(stopButton);
        controlPanel.add(statusLabel);

        JPanel sendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sendPanel.add(chooseButton);
        sendPanel.add(selectedLabel);
        sendPanel.add(sendButton);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(configPanel);
        topPanel.add(controlPanel);
        topPanel.add(sendPanel);
        topPanel.add(progressBar);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildRemoteTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(remoteStartButton);
        controlPanel.add(remoteStopButton);
        controlPanel.add(remoteStatusLabel);

        panel.add(controlPanel, BorderLayout.NORTH);
        panel.add(remoteViewerPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAudioTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel micPanel = new JPanel(new GridBagLayout());
        micPanel.setBorder(BorderFactory.createTitledBorder("Meu microfone (compartilhar com o client)"));
        GridBagConstraints mc = new GridBagConstraints();
        mc.insets = new Insets(4, 4, 4, 4);
        mc.anchor = GridBagConstraints.WEST;
        mc.gridx = 0;
        mc.gridy = 0;
        micPanel.add(new JLabel("Dispositivo:"), mc);
        mc.gridx = 1;
        mc.fill = GridBagConstraints.HORIZONTAL;
        mc.weightx = 1;
        micPanel.add(micDeviceCombo, mc);
        mc.gridx = 0;
        mc.gridy = 1;
        mc.gridwidth = 2;
        mc.fill = GridBagConstraints.NONE;
        mc.weightx = 0;
        JPanel micButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        micButtons.add(micStartButton);
        micButtons.add(micStopButton);
        micButtons.add(micStatusLabel);
        micPanel.add(micButtons, mc);

        JPanel remoteAudioPanel = new JPanel(new GridBagLayout());
        remoteAudioPanel.setBorder(BorderFactory.createTitledBorder("Audio do sistema do client (ouvir aqui)"));
        GridBagConstraints rac = new GridBagConstraints();
        rac.insets = new Insets(4, 4, 4, 4);
        rac.anchor = GridBagConstraints.WEST;
        rac.gridx = 0;
        rac.gridy = 0;
        remoteAudioPanel.add(new JLabel("Tocar em:"), rac);
        rac.gridx = 1;
        rac.fill = GridBagConstraints.HORIZONTAL;
        rac.weightx = 1;
        remoteAudioPanel.add(remoteAudioOutputCombo, rac);
        rac.gridx = 2;
        rac.fill = GridBagConstraints.NONE;
        rac.weightx = 0;
        remoteAudioPanel.add(remoteAudioStatusLabel, rac);

        panel.add(micPanel);
        panel.add(remoteAudioPanel);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        panel.add(field, c);
    }

    private void wireActions() {
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        chooseButton.addActionListener(e -> chooseFile());
        sendButton.addActionListener(e -> sendSelected());
        remoteStartButton.addActionListener(e -> startRemoteControl());
        remoteStopButton.addActionListener(e -> stopRemoteControl());
        remoteStartButton.setEnabled(false);
        remoteStopButton.setEnabled(false);

        micStartButton.addActionListener(e -> startMic());
        micStopButton.addActionListener(e -> stopMic());
        micStartButton.setEnabled(false);
        micStopButton.setEnabled(false);

        clientSelector.addActionListener(e -> {
            ClientSession selected = (ClientSession) clientSelector.getSelectedItem();
            // O combo fica momentaneamente com selecao nula durante um refresh da lista
            // (removeAllElements + addElement, disparado por ex. quando um client manda o hello);
            // ignora esse "null" transitorio para nao desconectar o client ativo por engano.
            if (selected != null && selected != activeSession) {
                selectClient(selected);
            }
        });
        disconnectClientButton.addActionListener(e -> disconnectActiveClient());
        disconnectClientButton.setEnabled(false);

        remoteViewerPanel.setInputSender(new RemoteInputSender() {
            @Override
            public void sendMouseMove(int x, int y) {
                trySendRemote(o -> RemoteMessageSender.sendMouseMove(out, writeLock, x, y));
            }

            @Override
            public void sendMousePress(int button) {
                trySendRemote(o -> RemoteMessageSender.sendMousePress(out, writeLock, button));
            }

            @Override
            public void sendMouseRelease(int button) {
                trySendRemote(o -> RemoteMessageSender.sendMouseRelease(out, writeLock, button));
            }

            @Override
            public void sendMouseWheel(int rotation) {
                trySendRemote(o -> RemoteMessageSender.sendMouseWheel(out, writeLock, rotation));
            }

            @Override
            public void sendKeyPress(int keyCode) {
                trySendRemote(o -> RemoteMessageSender.sendKeyPress(out, writeLock, keyCode));
            }

            @Override
            public void sendKeyRelease(int keyCode) {
                trySendRemote(o -> RemoteMessageSender.sendKeyRelease(out, writeLock, keyCode));
            }

            @Override
            public void sendKeyTyped(char c) {
                trySendRemote(o -> RemoteMessageSender.sendKeyTyped(out, writeLock, c));
            }
        });
    }

    private interface RemoteIoAction {
        void run(Object unused) throws Exception;
    }

    private void trySendRemote(RemoteIoAction action) {
        if (out == null) {
            return;
        }
        try {
            action.run(null);
        } catch (Exception ex) {
            log("Erro ao enviar comando remoto: " + ex.getMessage());
        }
    }

    private void startRemoteControl() {
        trySendRemote(o -> {
            RemoteMessageSender.sendStart(out, writeLock);
            log("Pedido de controle remoto enviado ao client.");
        });
    }

    private void stopRemoteControl() {
        trySendRemote(o -> {
            RemoteMessageSender.sendStop(out, writeLock);
            log("Controle remoto encerrado.");
        });
        remoteViewerPanel.clear();
    }

    private void startMic() {
        if (out == null) {
            JOptionPane.showMessageDialog(this, "Nenhum client conectado.");
            return;
        }
        Mixer.Info selected = (Mixer.Info) micDeviceCombo.getSelectedItem();
        if (selected == null) {
            log("Nenhum microfone disponivel nesta maquina.");
            return;
        }
        try {
            AudioFormat format = AudioFormats.pickForCapture(selected);
            micStreamer = new AudioStreamer(out, writeLock, Protocol.REMOTE_MIC_CHUNK, selected, format);
            RemoteMessageSender.sendMicStart(out, writeLock, format);
            micStreamer.start();
            micStreamerThread = new Thread(micStreamer, "mic-streamer");
            micStreamerThread.setDaemon(true);
            micStreamerThread.start();
            micStatusLabel.setText("Compartilhando microfone");
            micStartButton.setEnabled(false);
            micStopButton.setEnabled(true);
            log("Compartilhamento de microfone iniciado.");
        } catch (Exception ex) {
            log("Erro ao iniciar microfone: " + ex.getMessage());
        }
    }

    private void stopMic() {
        if (micStreamer != null) {
            micStreamer.stop();
            micStreamer = null;
            micStreamerThread = null;
        }
        trySendRemote(o -> RemoteMessageSender.sendMicStop(out, writeLock));
        micStatusLabel.setText("Microfone parado");
        micStartButton.setEnabled(out != null);
        micStopButton.setEnabled(false);
        log("Compartilhamento de microfone parado.");
    }

    private void startServer() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            SSLContext context = SSLContextFactory.create(
                    keystoreField.getText().trim(), keystorePassField.getPassword(),
                    truststoreField.getText().trim(), truststorePassField.getPassword());

            SSLServerSocketFactory factory = context.getServerSocketFactory();
            SSLServerSocket sslServerSocket = (SSLServerSocket) factory.createServerSocket(port);
            sslServerSocket.setNeedClientAuth(true);
            serverSocket = sslServerSocket;

            log("Servidor ouvindo na porta " + port + "...");
            statusLabel.setText("Ouvindo na porta " + port);
            startButton.setEnabled(false);
            stopButton.setEnabled(true);

            Thread acceptThread = new Thread(this::acceptLoop, "accept-loop");
            acceptThread.setDaemon(true);
            acceptThread.start();

            try {
                discoveryServer = new DiscoveryServer(port);
                discoveryServerThread = new Thread(discoveryServer, "discovery-server");
                discoveryServerThread.setDaemon(true);
                discoveryServerThread.start();
                log("Anunciando servidor na rede local para descoberta automatica.");
            } catch (Exception discoveryEx) {
                log("Aviso: descoberta automatica indisponivel (" + discoveryEx.getMessage() + ").");
            }
        } catch (Exception ex) {
            log("Erro ao iniciar servidor: " + ex.getMessage());
            JOptionPane.showMessageDialog(this, "Erro ao iniciar servidor:\n" + ex.getMessage(),
                    "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void acceptLoop() {
        while (serverSocket != null && !serverSocket.isClosed()) {
            try {
                SSLSocket socket = (SSLSocket) serverSocket.accept();
                SwingUtilities.invokeLater(() -> log("Client conectado: " + socket.getRemoteSocketAddress()));
                createSession(socket);
            } catch (Exception ex) {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    SwingUtilities.invokeLater(() -> log("Erro ao aceitar conexao: " + ex.getMessage()));
                }
            }
        }
    }

    /**
     * Aceita a conexao de um novo client SEM derrubar os que ja estavam
     * conectados - varios clients podem ficar conectados ao mesmo tempo, e
     * o usuario escolhe no combo box "Client ativo" qual deles esta sendo
     * usado no momento.
     */
    private void createSession(SSLSocket socket) {
        try {
            DataOutputStream sessionOut = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            TransferListener listener = new SwingTransferListener();
            FileTransferReceiver sessionReceiver = new FileTransferReceiver(
                    in, new File(outputDirField.getText().trim()), listener);

            String initialName = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            ClientSession session = new ClientSession(socket, sessionOut, sessionReceiver, initialName);

            sessionReceiver.setHelloListener((name, jarHash) -> {
                session.displayName = name + " (" + socket.getInetAddress().getHostAddress() + ")";
                session.jarHash = jarHash;
                SwingUtilities.invokeLater(() -> {
                    refreshClientList();
                    checkForClientUpdate(session);
                });
            });
            sessionReceiver.setDisconnectListener(() -> SwingUtilities.invokeLater(() -> removeSession(session)));

            session.receiverThread = new Thread(sessionReceiver, "receiver-" + initialName);
            session.receiverThread.setDaemon(true);
            session.receiverThread.start();

            SwingUtilities.invokeLater(() -> {
                sessions.add(session);
                clientListModel.addElement(session);
                clientListLabel.setText(sessions.size() + " client(s) conectado(s)");
                if (activeSession == null) {
                    clientSelector.setSelectedItem(session);
                    selectClient(session);
                }
            });
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> log("Erro ao anexar client: " + ex.getMessage()));
        }
    }

    private void refreshClientList() {
        int selectedIndex = clientListModel.getIndexOf(activeSession);
        clientListModel.removeAllElements();
        for (ClientSession s : sessions) {
            clientListModel.addElement(s);
        }
        if (selectedIndex >= 0 && selectedIndex < clientListModel.getSize()) {
            clientSelector.setSelectedItem(activeSession);
        }
    }

    /**
     * Compara o hash do jar que o client reportou no hello com o hash do
     * jar configurado em "Jar do client mais recente"; se forem diferentes,
     * envia a nova versao automaticamente e o client se auto-atualiza e
     * reinicia sozinho.
     */
    private void checkForClientUpdate(ClientSession session) {
        if (session.jarHash == null || "dev".equals(session.jarHash) || "unknown".equals(session.jarHash)) {
            return;
        }
        File updateJar = new File(updateJarField.getText().trim());
        if (!updateJar.isFile()) {
            return;
        }
        try {
            String latestHash = JarUtils.sha256(updateJar);
            if (latestHash.equals(session.jarHash)) {
                return;
            }
            byte[] jarBytes = Files.readAllBytes(updateJar.toPath());
            RemoteMessageSender.sendUpdatePush(session.out, session.writeLock, jarBytes);
            log("Client " + session.displayName + " esta desatualizado - nova versao enviada ("
                    + jarBytes.length + " bytes). Ele vai se atualizar e reiniciar sozinho.");
        } catch (Exception ex) {
            log("Erro ao verificar/enviar atualizacao para " + session.displayName + ": " + ex.getMessage());
        }
    }

    /** Troca qual client esta "ativo" - a Transferencia, o Acesso Remoto e o Audio passam a operar nele. */
    private void selectClient(ClientSession session) {
        if (session == activeSession) {
            return;
        }

        if (activeSession != null) {
            activeSession.receiver.setRemoteFrameListener(null);
            activeSession.receiver.setClipboardListener(null);
            activeSession.receiver.setSystemAudioListener(null);
            if (clipboardSync != null) {
                clipboardSync.stop();
                clipboardSync = null;
            }
            if (micStreamer != null) {
                DataOutputStream prevOut = activeSession.out;
                Object prevLock = activeSession.writeLock;
                micStreamer.stop();
                micStreamer = null;
                micStreamerThread = null;
                try {
                    RemoteMessageSender.sendMicStop(prevOut, prevLock);
                } catch (Exception ignored) {
                }
            }
            remoteAudioPlayer.close();
            try {
                RemoteMessageSender.sendStop(activeSession.out, activeSession.writeLock);
            } catch (Exception ignored) {
            }
        }

        activeSession = session;
        out = session == null ? null : session.out;
        writeLock = session == null ? new Object() : session.writeLock;
        remoteViewerPanel.clear();
        micStatusLabel.setText("Microfone parado");
        remoteAudioStatusLabel.setText("Sem audio remoto");
        remoteStatusLabel.setText(session == null ? "Sem client conectado" : "Client ativo: " + session.displayName);

        boolean hasSession = session != null;
        remoteStartButton.setEnabled(hasSession);
        remoteStopButton.setEnabled(hasSession);
        micStartButton.setEnabled(hasSession);
        micStopButton.setEnabled(false);
        disconnectClientButton.setEnabled(hasSession);
        refreshTransferUi();

        if (session == null) {
            return;
        }

        session.receiver.setRemoteFrameListener(new RemoteFrameListener() {
            @Override
            public void onScreenSize(Dimension size, double dpiScale) {
                SwingUtilities.invokeLater(() -> remoteViewerPanel.setRemoteScreenSize(size));
                log("Tela remota: " + size.width + "x" + size.height
                        + " (escala do Windows no client: " + Math.round(dpiScale * 100) + "%)");
            }

            @Override
            public void onTile(int x, int y, BufferedImage tileImage) {
                remoteViewerPanel.applyTile(x, y, tileImage);
            }
        });

        File outputDir = new File(outputDirField.getText().trim());
        clipboardSync = new ClipboardSync(new ClipboardSync.Sender() {
            @Override
            public void sendText(String text) {
                trySendRemote(o -> {
                    RemoteMessageSender.sendClipboardText(out, writeLock, text);
                    log("Area de transferencia enviada ao client.");
                });
            }

            @Override
            public void sendFiles(byte[] zipBytes) {
                trySendRemote(o -> {
                    RemoteMessageSender.sendClipboardFiles(out, writeLock, zipBytes);
                    log("Arquivos copiados enviados ao client (" + zipBytes.length + " bytes).");
                });
            }
        }, outputDir);
        session.receiver.setClipboardListener(new com.transacao.common.remote.ClipboardListener() {
            @Override
            public void onClipboardText(String text) {
                clipboardSync.applyRemoteText(text);
                log("Area de transferencia recebida do client.");
            }

            @Override
            public void onClipboardFiles(byte[] zipBytes) {
                clipboardSync.applyRemoteFiles(zipBytes);
                log("Arquivos copiados recebidos do client (" + zipBytes.length + " bytes).");
            }
        });
        session.receiver.setSystemAudioListener(new AudioChannelListener() {
            @Override
            public void onStart(AudioFormat format) {
                SwingUtilities.invokeLater(() -> {
                    Mixer.Info selected = (Mixer.Info) remoteAudioOutputCombo.getSelectedItem();
                    if (selected == null) {
                        log("Nenhuma saida de audio disponivel para tocar o audio remoto.");
                        return;
                    }
                    try {
                        remoteAudioPlayer.open(selected, format);
                        remoteAudioStatusLabel.setText("Ouvindo audio do client");
                    } catch (Exception ex) {
                        log("Erro ao abrir saida de audio: " + ex.getMessage());
                    }
                });
            }

            @Override
            public void onStop() {
                remoteAudioPlayer.close();
                SwingUtilities.invokeLater(() -> remoteAudioStatusLabel.setText("Sem audio remoto"));
            }

            @Override
            public void onChunk(byte[] pcmData) {
                remoteAudioPlayer.play(pcmData);
            }
        });

        log("Client ativo agora: " + session.displayName);
    }

    private void disconnectActiveClient() {
        if (activeSession == null) {
            return;
        }
        try {
            activeSession.socket.close();
        } catch (Exception ignored) {
        }
        // a remocao da lista acontece via setDisconnectListener quando a thread de recebimento perceber o fechamento
    }

    /** Chamado (na EDT) quando a conexao de um client cai ou e fechada manualmente. */
    private void removeSession(ClientSession session) {
        sessions.remove(session);
        clientListModel.removeElement(session);
        clientListLabel.setText(sessions.size() + " client(s) conectado(s)");
        log("Client desconectado: " + session.displayName);

        if (session == activeSession) {
            ClientSession next = sessions.isEmpty() ? null : sessions.get(0);
            selectClient(next);
            if (next != null) {
                clientSelector.setSelectedItem(next);
            }
        }
    }

    private void stopServer() {
        for (ClientSession session : new ArrayList<>(sessions)) {
            try {
                session.receiver.stop();
                session.socket.close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        clientListModel.removeAllElements();
        clientListLabel.setText("0 client(s) conectado(s)");
        selectClient(null);

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
        serverSocket = null;
        if (discoveryServer != null) {
            discoveryServer.stop();
            discoveryServer = null;
            discoveryServerThread = null;
        }
        log("Servidor parado.");
        statusLabel.setText("Parado");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void chooseFile() {
        if (activeSession == null) {
            JOptionPane.showMessageDialog(this, "Selecione um client ativo primeiro.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("Selecione um arquivo .zip ou uma pasta de codigo fonte");
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if (f.isFile() && !f.getName().toLowerCase().endsWith(".zip")) {
                JOptionPane.showMessageDialog(this,
                        "Somente arquivos .zip podem ser enviados como arquivo unico.\n" +
                                "Para outros tipos de arquivo, selecione a pasta que os contem.",
                        "Selecao invalida", JOptionPane.WARNING_MESSAGE);
                return;
            }
            activeSession.selectedFile = f;
            selectedLabel.setText(shorten(f.getAbsolutePath()));
            selectedLabel.setToolTipText(f.getAbsolutePath());
            refreshTransferUi();
        }
    }

    private void sendSelected() {
        if (activeSession == null || activeSession.selectedFile == null) {
            JOptionPane.showMessageDialog(this, "Selecione um arquivo .zip ou uma pasta primeiro.");
            return;
        }

        sendButton.setEnabled(false);
        progressBar.setValue(0);
        File fileToSend = activeSession.selectedFile;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                TransferListener listener = new SwingTransferListener();
                try {
                    if (fileToSend.isFile()) {
                        FileTransferSender.sendZip(out, writeLock, fileToSend, listener);
                    } else {
                        FileTransferSender.sendDirectoryAsText(out, writeLock, fileToSend, listener);
                    }
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> log("Erro ao enviar: " + ex.getMessage()));
                }
                return null;
            }

            @Override
            protected void done() {
                sendButton.setEnabled(true);
            }
        }.execute();
    }

    private String shorten(String path) {
        int max = 55;
        if (path.length() <= max) {
            return path;
        }
        return "..." + path.substring(path.length() - max);
    }

    private void refreshTransferUi() {
        boolean hasFile = activeSession != null && activeSession.selectedFile != null;
        sendButton.setEnabled(hasFile);
        chooseButton.setEnabled(activeSession != null);
        if (activeSession != null && activeSession.selectedFile != null) {
            selectedLabel.setText(shorten(activeSession.selectedFile.getAbsolutePath()));
        } else {
            selectedLabel.setText("Nada selecionado");
        }
        statusLabel.setText(activeSession != null ? "Client ativo conectado"
                : (serverSocket != null ? "Ouvindo, aguardando client" : "Parado"));
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> logArea.append(message + "\n"));
    }

    private class SwingTransferListener implements TransferListener {
        @Override
        public void onLog(String message) {
            log(message);
        }

        @Override
        public void onProgress(long done, long total) {
            int pct = total <= 0 ? 0 : (int) Math.min(100, (done * 100) / total);
            SwingUtilities.invokeLater(() -> progressBar.setValue(pct));
        }

        @Override
        public void onTransferComplete(String description) {
            SwingUtilities.invokeLater(() -> {
                progressBar.setValue(100);
                log("Transferencia concluida: " + description);
            });
        }
    }

    private static String resolveDefaultUpdateJar() {
        String[] candidates = {
                "updates/transacao-client.jar",
                "../Client/transacao-client.jar",
                "dist/Server/updates/transacao-client.jar",
                "dist/Client/transacao-client.jar",
                "dist/updates/transacao-client.jar",
                "dist/transacao-client.jar",
                "transacao-client.jar",
                "../dist/updates/transacao-client.jar",
                "../dist/transacao-client.jar"
        };
        for (String c : candidates) {
            if (new File(c).isFile()) {
                return c;
            }
        }
        return "updates/transacao-client.jar";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ServerApp app = new ServerApp();
            app.stopButton.setEnabled(false);
            app.setVisible(true);
        });
    }
}
