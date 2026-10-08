package com.transacao.client;

import com.transacao.common.FileTransferReceiver;
import com.transacao.common.FileTransferSender;
import com.transacao.common.SSLContextFactory;
import com.transacao.common.TransferListener;
import com.transacao.common.Protocol;
import com.transacao.common.FirstRunGate;
import com.transacao.common.JarUtils;
import com.transacao.common.SelfUpdater;
import com.transacao.common.WindowsStartup;
import com.transacao.common.discovery.DiscoveryClient;

import java.security.MessageDigest;
import com.transacao.common.remote.AudioChannelListener;
import com.transacao.common.remote.AudioDevices;
import com.transacao.common.remote.AudioFormats;
import com.transacao.common.remote.AudioPlayer;
import com.transacao.common.remote.AudioStreamer;
import com.transacao.common.remote.ClipboardListener;
import com.transacao.common.remote.ClipboardSync;
import com.transacao.common.remote.InputInjector;
import com.transacao.common.remote.RemoteControlListener;
import com.transacao.common.remote.RemoteMessageSender;
import com.transacao.common.remote.ScreenKeepAlive;
import com.transacao.common.remote.TeamsActivityWatcher;
import com.transacao.common.remote.ScreenStreamer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Aplicacao client: conecta via SSL ao servidor e, a partir dai, pode tanto
 * enviar quanto receber arquivos/pastas pela mesma conexao (mao dupla).
 */
public class ClientApp extends JFrame {

    private final JTextField hostField = new JTextField("localhost", 14);
    private final JTextField portField = new JTextField("9444", 6);
    private final JTextField keystoreField = new JTextField("certs/client.jks", 22);
    private final JPasswordField keystorePassField = new JPasswordField("changeit", 10);
    private final JTextField truststoreField = new JTextField("certs/client-truststore.jks", 22);
    private final JPasswordField truststorePassField = new JPasswordField("changeit", 10);
    private final JTextField outputDirField = new JTextField("recebidos_client", 22);

    private final JButton connectButton = new JButton("Conectar");
    private final JButton disconnectButton = new JButton("Desconectar");
    private final JButton discoverButton = new JButton("Buscar servidor na rede");
    private final JCheckBox startWithWindowsCheck = new JCheckBox("Iniciar com o Windows (neste usuario)");
    private final JCheckBox keepAliveCheck = new JCheckBox("Manter computador ativo (anti-suspensao com mouse+CapsLock a cada 1 min)", false);
    private final JCheckBox teamsWatcherCheck = new JCheckBox("Avisar sobre atividade no Teams (bandeja do Windows)", false);
    private final JButton chooseButton = new JButton("Selecionar arquivo .zip ou pasta...");
    private final JButton sendButton = new JButton("Enviar");
    private final JLabel selectedLabel = new JLabel("Nada selecionado");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Desconectado");

    private final Object writeLock = new Object();
    private final JCheckBox allowRemoteControlCheck = new JCheckBox("Permitir controle remoto", true);
    private final JLabel remoteStatusLabel = new JLabel("Aguardando conexao");

    private final JComboBox<Mixer.Info> sysAudioDeviceCombo = new JComboBox<>(
            AudioDevices.listCaptureDevices().toArray(new Mixer.Info[0]));
    private final JButton sysAudioStartButton = new JButton("Compartilhar audio do sistema");
    private final JButton sysAudioStopButton = new JButton("Parar audio do sistema");
    private final JLabel sysAudioStatusLabel = new JLabel("Audio do sistema parado");

    private final JComboBox<Mixer.Info> micOutputCombo = new JComboBox<>(
            AudioDevices.listPlaybackDevices().toArray(new Mixer.Info[0]));
    private final JLabel micPlaybackStatusLabel = new JLabel("Sem microfone remoto");
    private final AudioPlayer micPlayer = new AudioPlayer();

    private SSLSocket socket;
    private DataOutputStream out;
    private FileTransferReceiver receiver;
    private Thread receiverThread;
    private File selectedFile;
    private ClipboardSync clipboardSync;
    private volatile boolean clipboardSyncEnabledByServer = true;
    private InputInjector inputInjector;
    private ScreenStreamer screenStreamer;
    private Thread screenStreamerThread;
    /** Ultimo viewport de quem controla (ex: tela cheia) recebido - aplicado ao iniciar/ja em andamento. */
    private volatile Dimension pendingViewport;
    private AudioStreamer sysAudioStreamer;
    private Thread sysAudioStreamerThread;
    private DiscoveryClient discoveryClient;
    private Thread discoveryThread;
    private ScreenKeepAlive keepAlive;
    private Thread keepAliveThread;
    private TeamsActivityWatcher teamsWatcher;
    private Thread teamsWatcherThread;

    private static final String STARTUP_APP_NAME = "TransacaoClient";

    // Preferencias persistidas do usuario atual (registro do Windows via
    // java.util.prefs - nao precisa de privilegio de administrador, mesma
    // ideia ja usada pelo WindowsStartup para a pasta Inicializacao).
    private final Preferences prefs = Preferences.userNodeForPackage(ClientApp.class);
    private static final String PREF_KEEP_ALIVE = "keepComputerActive";
    private static final String PREF_TEAMS_WATCHER = "teamsWatcherEnabled";
    private static final String PREF_ALLOW_REMOTE_CONTROL = "allowRemoteControl";
    private static final String PREF_HOST = "host";
    private static final String PREF_PORT = "port";
    private static final String PREF_KEYSTORE = "keystorePath";
    private static final String PREF_KEYSTORE_PASS = "keystorePass";
    private static final String PREF_TRUSTSTORE = "truststorePath";
    private static final String PREF_TRUSTSTORE_PASS = "truststorePass";
    private static final String PREF_OUTPUT_DIR = "outputDir";

    /** Carrega o valor salvo (se houver) e passa a gravar toda mudanca futura - assim o campo volta do jeito que o usuario deixou, mesmo depois do Client reiniciar (ex: auto-update). */
    private void bindPersistedText(JTextField field, String key, String defaultValue) {
        field.setText(prefs.get(key, defaultValue));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                prefs.put(key, field.getText());
            }
        });
    }

    private void bindPersistedPassword(JPasswordField field, String key, String defaultValue) {
        field.setText(prefs.get(key, defaultValue));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                prefs.put(key, new String(field.getPassword()));
            }
        });
    }

    private void bindPersistedCheck(JCheckBox box, String key, boolean defaultValue) {
        box.setSelected(prefs.getBoolean(key, defaultValue));
        box.addActionListener(e -> prefs.putBoolean(key, box.isSelected()));
    }

    public ClientApp() {
        super("Transacao - Client");
        buildUi();
        // Todo campo de configuracao volta do jeito que o usuario deixou da
        // ultima vez, mesmo depois do Client reiniciar (ex: auto-update).
        bindPersistedText(hostField, PREF_HOST, hostField.getText());
        bindPersistedText(portField, PREF_PORT, portField.getText());
        bindPersistedText(keystoreField, PREF_KEYSTORE, keystoreField.getText());
        bindPersistedPassword(keystorePassField, PREF_KEYSTORE_PASS, new String(keystorePassField.getPassword()));
        bindPersistedText(truststoreField, PREF_TRUSTSTORE, truststoreField.getText());
        bindPersistedPassword(truststorePassField, PREF_TRUSTSTORE_PASS, new String(truststorePassField.getPassword()));
        bindPersistedText(outputDirField, PREF_OUTPUT_DIR, outputDirField.getText());
        bindPersistedCheck(allowRemoteControlCheck, PREF_ALLOW_REMOTE_CONTROL, true);
        initSystemTray();
        wireActions();
        updateConnectionState(false);
        initStartupCheckbox();
        keepAliveCheck.setSelected(prefs.getBoolean(PREF_KEEP_ALIVE, false));
        if (keepAliveCheck.isSelected()) {
            startKeepAlive();
        }
        teamsWatcherCheck.setSelected(prefs.getBoolean(PREF_TEAMS_WATCHER, false));
        if (teamsWatcherCheck.isSelected()) {
            startTeamsWatcher();
        }
        startDiscovery();
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Transferencia de Arquivos", buildTransferTab());
        tabs.addTab("Acesso Remoto", buildRemoteTab());
        tabs.addTab("Audio", buildAudioTab());
        add(tabs, BorderLayout.CENTER);

        setSize(960, 640);
        setLocationRelativeTo(null);
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
        addRow(configPanel, c, row++, "Host do servidor:", hostField);
        addRow(configPanel, c, row++, "Porta:", portField);
        addRow(configPanel, c, row++, "Keystore (client):", keystoreField);
        addRow(configPanel, c, row++, "Senha keystore:", keystorePassField);
        addRow(configPanel, c, row++, "Truststore (confia no server):", truststoreField);
        addRow(configPanel, c, row++, "Senha truststore:", truststorePassField);
        addRow(configPanel, c, row++, "Pasta de saida (recebidos):", outputDirField);

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(connectButton);
        controlPanel.add(disconnectButton);
        controlPanel.add(discoverButton);
        controlPanel.add(statusLabel);

        JPanel startupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        startupPanel.add(startWithWindowsCheck);
        startupPanel.add(keepAliveCheck);

        JPanel sendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sendPanel.add(chooseButton);
        sendPanel.add(selectedLabel);
        sendPanel.add(sendButton);

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(configPanel);
        topPanel.add(controlPanel);
        topPanel.add(startupPanel);
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
        controlPanel.add(allowRemoteControlCheck);
        controlPanel.add(remoteStatusLabel);

        JPanel teamsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        teamsPanel.add(teamsWatcherCheck);

        JTextArea infoArea = new JTextArea(
                "Esta maquina compartilha a propria tela quando o Servidor pede controle remoto.\n" +
                "Desmarque \"Permitir controle remoto\" para recusar pedidos futuros.\n\n" +
                "\"Avisar sobre atividade no Teams\": fica de olho no icone do Microsoft Teams na " +
                "bandeja do Windows e avisa o Servidor quando detectar atividade nova (ex: mensagem " +
                "nao lida) - so avisa que algo aconteceu, nao le o conteudo da mensagem. Exige que o " +
                "icone do Teams esteja visivel na bandeja (nao escondido atras da seta \"mostrar " +
                "icones ocultos\").");
        infoArea.setEditable(false);
        infoArea.setOpaque(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.add(controlPanel);
        northPanel.add(teamsPanel);

        panel.add(northPanel, BorderLayout.NORTH);
        panel.add(infoArea, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildAudioTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel sysAudioPanel = new JPanel(new GridBagLayout());
        sysAudioPanel.setBorder(BorderFactory.createTitledBorder("Audio do meu sistema (compartilhar com o servidor)"));
        GridBagConstraints sac = new GridBagConstraints();
        sac.insets = new Insets(4, 4, 4, 4);
        sac.anchor = GridBagConstraints.WEST;
        sac.gridx = 0;
        sac.gridy = 0;
        sysAudioPanel.add(new JLabel("Dispositivo (ex: Stereo Mix):"), sac);
        sac.gridx = 1;
        sac.fill = GridBagConstraints.HORIZONTAL;
        sac.weightx = 1;
        sysAudioPanel.add(sysAudioDeviceCombo, sac);
        sac.gridx = 0;
        sac.gridy = 1;
        sac.gridwidth = 2;
        sac.fill = GridBagConstraints.NONE;
        sac.weightx = 0;
        JPanel sysAudioButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sysAudioButtons.add(sysAudioStartButton);
        sysAudioButtons.add(sysAudioStopButton);
        sysAudioButtons.add(sysAudioStatusLabel);
        sysAudioPanel.add(sysAudioButtons, sac);

        JPanel micPanel = new JPanel(new GridBagLayout());
        micPanel.setBorder(BorderFactory.createTitledBorder("Microfone do servidor (tocar aqui como dispositivo virtual)"));
        GridBagConstraints mc = new GridBagConstraints();
        mc.insets = new Insets(4, 4, 4, 4);
        mc.anchor = GridBagConstraints.WEST;
        mc.gridx = 0;
        mc.gridy = 0;
        micPanel.add(new JLabel("Tocar em:"), mc);
        mc.gridx = 1;
        mc.fill = GridBagConstraints.HORIZONTAL;
        mc.weightx = 1;
        micPanel.add(micOutputCombo, mc);
        mc.gridx = 2;
        mc.fill = GridBagConstraints.NONE;
        mc.weightx = 0;
        micPanel.add(micPlaybackStatusLabel, mc);

        JTextArea infoArea = new JTextArea(
                "Para o microfone do servidor aparecer como dispositivo de entrada para outros\n" +
                "programas nesta maquina (Zoom, Teams, etc.), instale o VB-Audio Virtual Cable\n" +
                "e selecione \"CABLE Input\" na lista acima.");
        infoArea.setEditable(false);
        infoArea.setOpaque(false);
        infoArea.setLineWrap(true);
        infoArea.setWrapStyleWord(true);

        panel.add(sysAudioPanel);
        panel.add(micPanel);
        panel.add(infoArea);
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
        connectButton.addActionListener(e -> connect());
        disconnectButton.addActionListener(e -> {
            disconnect();
            startDiscovery();
        });
        discoverButton.addActionListener(e -> {
            if (discoveryClient != null) {
                stopDiscovery("Busca por servidor cancelada.");
            } else {
                startDiscovery();
            }
        });
        chooseButton.addActionListener(e -> chooseFile());
        sendButton.addActionListener(e -> sendSelected());
        sysAudioStartButton.addActionListener(e -> startSysAudio());
        sysAudioStopButton.addActionListener(e -> stopSysAudio());
        sysAudioStartButton.setEnabled(false);
        sysAudioStopButton.setEnabled(false);

        keepAliveCheck.addActionListener(e -> {
            prefs.putBoolean(PREF_KEEP_ALIVE, keepAliveCheck.isSelected());
            if (keepAliveCheck.isSelected()) {
                startKeepAlive();
            } else {
                stopKeepAlive();
            }
        });

        teamsWatcherCheck.addActionListener(e -> {
            prefs.putBoolean(PREF_TEAMS_WATCHER, teamsWatcherCheck.isSelected());
            if (teamsWatcherCheck.isSelected()) {
                startTeamsWatcher();
            } else {
                stopTeamsWatcher();
            }
        });
    }

    private void stopDiscovery(String logMessage) {
        if (discoveryClient == null) {
            return;
        }
        discoveryClient.stop();
        discoveryClient = null;
        discoveryThread = null;
        discoverButton.setText("Buscar servidor na rede");
        if (logMessage != null) {
            log(logMessage);
        }
    }

    private void startDiscovery() {
        if (discoveryClient != null) {
            return;
        }

        discoveryClient = new DiscoveryClient();
        discoverButton.setText("Cancelar busca");
        log("Procurando servidor na rede local...");

        discoveryThread = new Thread(() -> discoveryClient.searchUntilFound(new DiscoveryClient.Callback() {
            @Override
            public void onAttempt() {
                SwingUtilities.invokeLater(() -> statusLabel.setText("Procurando servidor..."));
            }

            @Override
            public void onFound(DiscoveryClient.Found found) {
                SwingUtilities.invokeLater(() -> {
                    discoveryClient = null;
                    discoveryThread = null;
                    discoverButton.setText("Buscar servidor na rede");
                    if (socket != null) {
                        return;
                    }
                    hostField.setText(found.host);
                    portField.setText(String.valueOf(found.port));
                    log("Servidor encontrado: " + found.name + " (" + found.host + ":" + found.port + "). Conectando...");
                    connect();
                });
            }
        }), "discovery-client");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private void initStartupCheckbox() {
        if (!WindowsStartup.isWindows()) {
            startWithWindowsCheck.setEnabled(false);
            startWithWindowsCheck.setToolTipText("Disponivel apenas no Windows.");
            return;
        }
        startWithWindowsCheck.setSelected(WindowsStartup.isEnabled(STARTUP_APP_NAME));
        startWithWindowsCheck.addActionListener(e -> {
            if (startWithWindowsCheck.isSelected()) {
                try {
                    WindowsStartup.enable(STARTUP_APP_NAME, ClientApp.class);
                    log("Configurado para iniciar automaticamente com o Windows (usuario atual).");
                } catch (Exception ex) {
                    startWithWindowsCheck.setSelected(false);
                    log("Nao foi possivel configurar o inicio automatico: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this,
                            "Nao foi possivel configurar o inicio automatico:\n" + ex.getMessage(),
                            "Erro", JOptionPane.ERROR_MESSAGE);
                }
            } else {
                WindowsStartup.disable(STARTUP_APP_NAME);
                log("Inicio automatico com o Windows desativado.");
            }
        });
    }

    private void startKeepAlive() {
        if (keepAlive != null) {
            return;
        }
        keepAlive = new ScreenKeepAlive(this::log);
        keepAliveThread = new Thread(keepAlive, "screen-keepalive");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
        log("Anti-suspensao ativo: simulando CapsLock a cada 5 minutos para evitar suspensao/inatividade.");
    }

    private void stopKeepAlive() {
        if (keepAlive != null) {
            keepAlive.stop();
            if (keepAliveThread != null) {
                keepAliveThread.interrupt();
            }
            keepAlive = null;
            keepAliveThread = null;
            log("Anti-suspensao desativado.");
        }
    }

    private void startTeamsWatcher() {
        if (teamsWatcher != null) {
            return;
        }
        teamsWatcher = new TeamsActivityWatcher(detected -> {
            if (detected) {
                log("Atividade nova detectada no Teams.");
                trySendRemote(() -> RemoteMessageSender.sendTeamsActivityDetected(out, writeLock));
            } else {
                log("Atividade do Teams voltou ao normal.");
                trySendRemote(() -> RemoteMessageSender.sendTeamsActivityCleared(out, writeLock));
            }
        });
        teamsWatcher.setErrorListener(this::log);
        teamsWatcherThread = new Thread(teamsWatcher, "teams-activity-watcher");
        teamsWatcherThread.setDaemon(true);
        teamsWatcherThread.start();
        log("Monitoramento de atividade do Teams ativo.");
    }

    private void stopTeamsWatcher() {
        if (teamsWatcher != null) {
            teamsWatcher.stop();
            if (teamsWatcherThread != null) {
                teamsWatcherThread.interrupt();
            }
            teamsWatcher = null;
            teamsWatcherThread = null;
            log("Monitoramento de atividade do Teams desativado.");
        }
    }

    private String currentJarHash() {
        try {
            File jar = JarUtils.findRunningJar(ClientApp.class);
            if (!jar.getName().toLowerCase().endsWith(".jar")) {
                return "dev";
            }
            return JarUtils.sha256(jar);
        } catch (Exception ex) {
            return "unknown";
        }
    }

    /**
     * Extrai certificados/scripts atualizados (zip) por cima do proprio
     * diretorio de instalacao, ANTES do jar em si ser trocado - diferente do
     * jar, esses arquivos nao estao travados/em uso, entao dá pra sobrescrever
     * na hora, sem o script auxiliar de troca-e-reinicia. Sem isso, um
     * auto-update so trocava o .jar e deixava .bat/.vbs/certificados antigos
     * parados na maquina do client, mesmo quando essenciais (ex: o lancador
     * da inicializacao automatica) mudavam numa versao nova.
     */
    private void applyUpdateExtras(byte[] zipBytes) {
        try {
            File jar = JarUtils.findRunningJar(ClientApp.class);
            File installDir = jar.getParentFile();
            if (installDir == null) {
                return;
            }
            Path installRoot = installDir.toPath().normalize();
            int count = 0;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    File target = new File(installDir, entry.getName());
                    Path targetPath = target.toPath().normalize();
                    if (!targetPath.startsWith(installRoot)) {
                        // Entrada de zip tentando escapar do diretorio de instalacao
                        // (ex: "../"); ignora por seguranca.
                        continue;
                    }
                    if (entry.isDirectory()) {
                        target.mkdirs();
                        continue;
                    }
                    target.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        zis.transferTo(fos);
                    }
                    count++;
                }
            }
            log("Certificados/scripts atualizados (" + count + " arquivo(s)).");
        } catch (Exception ex) {
            log("Erro ao aplicar certificados/scripts atualizados: " + ex.getMessage());
        }
    }

    private void applyUpdate(byte[] newJarBytes) {
        try {
            File jar = JarUtils.findRunningJar(ClientApp.class);
            if (!jar.getName().toLowerCase().endsWith(".jar")) {
                log("Atualizacao recebida, mas ignorada (aplicacao nao esta rodando a partir de um .jar).");
                return;
            }
            log("Nova versao recebida do servidor (" + newJarBytes.length + " bytes). Atualizando e reiniciando...");
            SelfUpdater.applyAndRestart(jar, newJarBytes);
            System.exit(0);
        } catch (Exception ex) {
            log("Erro ao aplicar atualizacao: " + ex.getMessage());
        }
    }

    private void cancelDiscoveryQuietly() {
        if (discoveryClient != null) {
            discoveryClient.stop();
            discoveryClient = null;
            discoveryThread = null;
            discoverButton.setText("Buscar servidor na rede");
        }
    }

    private void startSysAudio() {
        if (out == null) {
            JOptionPane.showMessageDialog(this, "Nao conectado ao servidor.");
            return;
        }
        Mixer.Info selected = (Mixer.Info) sysAudioDeviceCombo.getSelectedItem();
        if (selected == null) {
            log("Nenhum dispositivo de captura de audio disponivel nesta maquina.");
            return;
        }
        try {
            AudioFormat format = AudioFormats.pickForCapture(selected);
            sysAudioStreamer = new AudioStreamer(out, writeLock, Protocol.REMOTE_SYSAUDIO_CHUNK, selected, format);
            RemoteMessageSender.sendSysAudioStart(out, writeLock, format);
            sysAudioStreamer.start();
            sysAudioStreamerThread = new Thread(sysAudioStreamer, "sysaudio-streamer");
            sysAudioStreamerThread.setDaemon(true);
            sysAudioStreamerThread.start();
            sysAudioStatusLabel.setText("Compartilhando audio do sistema");
            sysAudioStartButton.setEnabled(false);
            sysAudioStopButton.setEnabled(true);
            log("Compartilhamento de audio do sistema iniciado.");
        } catch (Exception ex) {
            log("Erro ao iniciar audio do sistema: " + ex.getMessage());
        }
    }

    private void stopSysAudio() {
        if (sysAudioStreamer != null) {
            sysAudioStreamer.stop();
            sysAudioStreamer = null;
            sysAudioStreamerThread = null;
        }
        trySendRemote(() -> RemoteMessageSender.sendSysAudioStop(out, writeLock));
        sysAudioStatusLabel.setText("Audio do sistema parado");
        sysAudioStartButton.setEnabled(out != null);
        sysAudioStopButton.setEnabled(false);
        log("Compartilhamento de audio do sistema parado.");
    }

    private void connect() {
        cancelDiscoveryQuietly();
        new SwingWorker<Void, Void>() {
            Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    SSLContext context = SSLContextFactory.create(
                            keystoreField.getText().trim(), keystorePassField.getPassword(),
                            truststoreField.getText().trim(), truststorePassField.getPassword());

                    SSLSocketFactory factory = context.getSocketFactory();
                    int port = Integer.parseInt(portField.getText().trim());
                    String host = hostField.getText().trim();
                    java.net.Socket plainSocket = new java.net.Socket();
                    plainSocket.connect(new java.net.InetSocketAddress(host, port), 5000);
                    SSLSocket s = (SSLSocket) factory.createSocket(plainSocket, host, port, true);
                    s.setTcpNoDelay(true);
                    s.startHandshake();
                    socket = s;
                    out = new DataOutputStream(s.getOutputStream());
                    DataInputStream in = new DataInputStream(s.getInputStream());

                    String myName;
                    try {
                        myName = java.net.InetAddress.getLocalHost().getHostName();
                    } catch (Exception ex) {
                        myName = "client";
                    }
                    String myJarHash = currentJarHash();
                    out.writeByte(Protocol.CLIENT_HELLO);
                    out.writeUTF(myName);
                    out.writeUTF(myJarHash);
                    out.flush();

                    TransferListener listener = new SwingTransferListener();
                    File outputDir = new File(outputDirField.getText().trim());
                    receiver = new FileTransferReceiver(in, outputDir, listener);
                    receiver.setDisconnectListener(() -> SwingUtilities.invokeLater(() -> {
                        if (socket != null) {
                            log("Conexao com o servidor perdida. Buscando novamente em "
                                    + (RECONNECT_BACKOFF_MS / 1000) + "s...");
                            disconnect();
                            scheduleReconnectSearch();
                        }
                    }));
                    receiver.setUpdateExtrasListener(zipBytes -> SwingUtilities.invokeLater(() -> applyUpdateExtras(zipBytes)));
                    receiver.setUpdateListener(newJarBytes -> SwingUtilities.invokeLater(() -> applyUpdate(newJarBytes)));
                    receiver.setClipboardSyncEnabledListener(enabled ->
                            SwingUtilities.invokeLater(() -> setClipboardSyncEnabledByServer(enabled)));
                    receiver.setRemoteControlListener(new RemoteControlListener() {
                        @Override
                        public void onStartRequested() {
                            startSharingScreen();
                        }

                        @Override
                        public void onStopRequested() {
                            stopSharingScreen();
                        }

                        @Override
                        public void onViewportSize(Dimension size) {
                            pendingViewport = size;
                            if (screenStreamer != null) {
                                screenStreamer.setTargetViewport(size);
                            }
                        }

                        @Override
                        public void onMouseMove(int x, int y) {
                            int originX = screenStreamer != null ? screenStreamer.getScreenOriginX() : 0;
                            int originY = screenStreamer != null ? screenStreamer.getScreenOriginY() : 0;
                            withInjector(injector -> injector.moveMouse(originX + x, originY + y));
                        }

                        @Override
                        public void onMousePress(int button) {
                            withInjector(injector -> injector.mousePress(button));
                        }

                        @Override
                        public void onMouseRelease(int button) {
                            withInjector(injector -> injector.mouseRelease(button));
                        }

                        @Override
                        public void onMouseWheel(int rotation) {
                            withInjector(injector -> injector.mouseWheel(rotation));
                        }

                        @Override
                        public void onKeyPress(int keyCode) {
                            withInjector(injector -> injector.keyPress(keyCode));
                        }

                        @Override
                        public void onKeyRelease(int keyCode) {
                            withInjector(injector -> injector.keyRelease(keyCode));
                        }

                        @Override
                        public void onKeyTyped(char c) {
                            withInjector(injector -> injector.typeChar(c));
                        }
                    });
                    clipboardSyncEnabledByServer = true;
                    refreshClipboardSync();
                    receiver.setMicAudioListener(new AudioChannelListener() {
                        @Override
                        public void onStart(AudioFormat format) {
                            SwingUtilities.invokeLater(() -> {
                                Mixer.Info selected = (Mixer.Info) micOutputCombo.getSelectedItem();
                                if (selected == null) {
                                    log("Nenhuma saida de audio disponivel para tocar o microfone remoto.");
                                    return;
                                }
                                try {
                                    micPlayer.open(selected, format);
                                    micPlaybackStatusLabel.setText("Tocando microfone do servidor");
                                } catch (Exception ex) {
                                    log("Erro ao abrir saida para o microfone remoto: " + ex.getMessage());
                                }
                            });
                        }

                        @Override
                        public void onStop() {
                            micPlayer.close();
                            SwingUtilities.invokeLater(() -> micPlaybackStatusLabel.setText("Sem microfone remoto"));
                        }

                        @Override
                        public void onChunk(byte[] pcmData) {
                            micPlayer.play(pcmData);
                        }
                    });
                    receiverThread = new Thread(receiver, "receiver");
                    receiverThread.setDaemon(true);
                    receiverThread.start();
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    log("Erro ao conectar: " + error.getMessage());
                    JOptionPane.showMessageDialog(ClientApp.this, "Erro ao conectar:\n" + error.getMessage(),
                            "Erro", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                log("Conectado ao servidor " + hostField.getText().trim() + ":" + portField.getText().trim());
                updateConnectionState(true);
            }
        }.execute();
    }

    private void disconnect() {
        stopSharingScreen();
        if (sysAudioStreamer != null) {
            sysAudioStreamer.stop();
            sysAudioStreamer = null;
            sysAudioStreamerThread = null;
        }
        micPlayer.close();
        if (receiver != null) {
            receiver.stop();
        }
        if (clipboardSync != null) {
            clipboardSync.stop();
            clipboardSync = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
        socket = null;
        out = null;
        inputInjector = null;
        log("Desconectado.");
        updateConnectionState(false);
        remoteStatusLabel.setText("Desconectado");
        sysAudioStartButton.setEnabled(false);
        sysAudioStopButton.setEnabled(false);
        sysAudioStatusLabel.setText("Audio do sistema parado");
        micPlaybackStatusLabel.setText("Sem microfone remoto");
    }

    /** Ativa ou interrompe imediatamente a sincronizacao conforme a configuracao do servidor. */
    private void setClipboardSyncEnabledByServer(boolean enabled) {
        clipboardSyncEnabledByServer = enabled;
        refreshClipboardSync();
        log("Copia e cola da area de transferencia " + (enabled ? "habilitada pelo servidor." : "desabilitada pelo servidor."));
    }

    private void refreshClipboardSync() {
        if (receiver != null) {
            receiver.setClipboardListener(null);
        }
        if (clipboardSync != null) {
            clipboardSync.stop();
            clipboardSync = null;
        }
        if (!clipboardSyncEnabledByServer || receiver == null || out == null) {
            return;
        }

        ClipboardSync sync = new ClipboardSync(new ClipboardSync.Sender() {
            @Override
            public void sendText(String text) {
                trySendRemote(() -> {
                    RemoteMessageSender.sendClipboardText(out, writeLock, text);
                    log("Area de transferencia enviada ao servidor.");
                });
            }

            @Override
            public void sendFiles(byte[] zipBytes) {
                trySendRemote(() -> {
                    RemoteMessageSender.sendClipboardFiles(out, writeLock, zipBytes);
                    log("Arquivos copiados enviados ao servidor (" + zipBytes.length + " bytes).");
                });
            }
        }, new File(outputDirField.getText().trim()));
        sync.setErrorListener(ClientApp.this::log);
        receiver.setClipboardListener(new ClipboardListener() {
            @Override
            public void onClipboardText(String text) {
                sync.applyRemoteText(text);
                log("Area de transferencia recebida do servidor.");
            }

            @Override
            public void onClipboardFiles(byte[] zipBytes) {
                sync.applyRemoteFiles(zipBytes);
                log("Arquivos copiados recebidos do servidor (" + zipBytes.length + " bytes).");
            }
        });
        clipboardSync = sync;
    }

    // Quando a conexao cai sozinha (sem ser por um clique em "Desconectar"),
    // espera um pouco antes de comecar a procurar o servidor de novo, em vez
    // de tentar na hora - sem esse respiro, qualquer instabilidade real de
    // rede (ou um auto-update que falha) virava um loop bem rapido de
    // conectar/desconectar sem parar (ja aconteceu em producao).
    private static final long RECONNECT_BACKOFF_MS = 3000;

    private void scheduleReconnectSearch() {
        Timer timer = new Timer((int) RECONNECT_BACKOFF_MS, e -> startDiscovery());
        timer.setRepeats(false);
        timer.start();
    }

    private interface RemoteIoAction {
        void run() throws Exception;
    }

    private void trySendRemote(RemoteIoAction action) {
        if (out == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception ex) {
            log("Erro ao enviar mensagem remota: " + ex.getMessage());
        }
    }

    private interface InjectorAction {
        void run(InputInjector injector);
    }

    private void withInjector(InjectorAction action) {
        if (!allowRemoteControlCheck.isSelected()) {
            return;
        }
        try {
            if (inputInjector == null) {
                inputInjector = new InputInjector();
            }
            action.run(inputInjector);
            // Acorda a captura de tela na hora em vez de esperar o intervalo
            // ocioso (ate 250ms) - e o que fazia o resultado de um clique
            // demorar para aparecer para quem esta controlando.
            if (screenStreamer != null) {
                screenStreamer.requestImmediateCapture();
            }
        } catch (Exception ex) {
            log("Erro ao aplicar comando remoto: " + ex.getMessage());
        }
    }

    private void startSharingScreen() {
        if (!allowRemoteControlCheck.isSelected()) {
            log("Pedido de controle remoto recusado (desabilitado nesta maquina).");
            return;
        }
        if (screenStreamer != null) {
            return;
        }
        try {
            screenStreamer = new ScreenStreamer(out, writeLock);
            screenStreamer.setErrorListener(this::log);
            // Sempre que o tamanho efetivamente transmitido mudar (inclusive
            // agora, no arranque), avisa o servidor para ele redimensionar o
            // canvas onde os tiles sao desenhados.
            screenStreamer.setStreamSizeListener(size ->
                    trySendRemote(() -> RemoteMessageSender.sendStreamSize(out, writeLock, size)));
            if (pendingViewport != null) {
                // Aplicado antes da thread iniciar: seguro mexer direto, e ja
                // deixa o 1o frame (dentro de run()) sair no tamanho certo.
                screenStreamer.setTargetViewport(pendingViewport);
            }
            RemoteMessageSender.sendScreenSize(out, writeLock, screenStreamer.getScreenSize(), screenStreamer.getDpiScale());
            screenStreamerThread = new Thread(screenStreamer, "screen-streamer");
            screenStreamerThread.setDaemon(true);
            screenStreamerThread.start();
            SwingUtilities.invokeLater(() -> remoteStatusLabel.setText("Compartilhando tela"));
            log("Iniciando compartilhamento de tela. Resolucao nativa: " + screenStreamer.getScreenSize().width
                    + "x" + screenStreamer.getScreenSize().height
                    + " (escala do Windows detectada: " + Math.round(screenStreamer.getDpiScale() * 100) + "%)");
        } catch (Exception ex) {
            log("Erro ao iniciar compartilhamento de tela: " + ex.getMessage());
        }
    }

    private void stopSharingScreen() {
        if (screenStreamer != null) {
            screenStreamer.stop();
            screenStreamer = null;
            screenStreamerThread = null;
            log("Compartilhamento de tela encerrado.");
        }
        SwingUtilities.invokeLater(() -> remoteStatusLabel.setText(socket != null ? "Conectado" : "Desconectado"));
    }

    private void chooseFile() {
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
            selectedFile = f;
            selectedLabel.setText(shorten(f.getAbsolutePath()));
            selectedLabel.setToolTipText(f.getAbsolutePath());
            updateConnectionState(socket != null);
        }
    }

    private void sendSelected() {
        if (selectedFile == null) {
            JOptionPane.showMessageDialog(this, "Selecione um arquivo .zip ou uma pasta primeiro.");
            return;
        }
        if (out == null) {
            JOptionPane.showMessageDialog(this, "Nao conectado ao servidor.");
            return;
        }

        sendButton.setEnabled(false);
        progressBar.setValue(0);
        File fileToSend = selectedFile;

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

    private void updateConnectionState(boolean connected) {
        connectButton.setEnabled(!connected);
        disconnectButton.setEnabled(connected);
        sendButton.setEnabled(connected && selectedFile != null);
        statusLabel.setText(connected ? "Conectado" : "Desconectado");
        remoteStatusLabel.setText(connected ? "Conectado" : "Desconectado");
        sysAudioStartButton.setEnabled(connected);
    }

    private static final java.time.format.DateTimeFormatter LOG_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private void log(String message) {
        String timestamp = java.time.LocalTime.now().format(LOG_TIME_FORMAT);
        SwingUtilities.invokeLater(() -> logArea.append("[" + timestamp + "] " + message + "\n"));
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

    private TrayIcon trayIcon;

    private void initSystemTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            Image iconImage = createTrayIcon();

            PopupMenu popup = new PopupMenu();
            MenuItem openItem = new MenuItem("Abrir Transacao Client");
            openItem.addActionListener(e -> showWindow());

            MenuItem exitItem = new MenuItem("Sair");
            exitItem.addActionListener(e -> {
                disconnect();
                System.exit(0);
            });

            popup.add(openItem);
            popup.addSeparator();
            popup.add(exitItem);

            trayIcon = new TrayIcon(iconImage, "Transacao - Client", popup);
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> showWindow());
            tray.add(trayIcon);
            setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        } catch (Exception ex) {
            log("Aviso: Nao foi possivel registrar icone na bandeja do sistema: " + ex.getMessage());
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            setExtendedState(JFrame.NORMAL);
            toFront();
            requestFocus();
        });
    }

    private static Image createTrayIcon() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(0, 120, 215));
        g2.fillRoundRect(0, 0, 16, 16, 4, 4);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g2.drawString("T", 4, 12);
        g2.dispose();
        return img;
    }

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        boolean isBackground = false;
        if (args != null) {
            for (String arg : args) {
                if ("--background".equalsIgnoreCase(arg) || "--silent".equalsIgnoreCase(arg)
                        || "--hidden".equalsIgnoreCase(arg) || "-b".equalsIgnoreCase(arg)) {
                    isBackground = true;
                    break;
                }
            }
        }
        final boolean background = isBackground;
        SwingUtilities.invokeLater(() -> {
            if (!FirstRunGate.requirePassword(STARTUP_APP_NAME)) {
                System.exit(0);
                return;
            }
            ClientApp app = new ClientApp();
            app.disconnectButton.setEnabled(false);
            if (!background) {
                app.setVisible(true);
            }
        });
    }
}
