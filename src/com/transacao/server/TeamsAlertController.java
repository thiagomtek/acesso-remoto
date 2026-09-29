package com.transacao.server;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;

/**
 * Alerta de atividade nova no Teams do client: em vez do beep, toca uma
 * sirene e abre uma janela para pausar. Com "verificar inatividade" marcado
 * (padrao), so toca quando o usuario esta ausente do PC do Servidor (sem
 * mexer mouse/teclado ha N minutos, ver UserPresenceMonitor) - se esta
 * presente, so registra no log. Desmarcado, toca sempre.
 *
 * A sirene para quando: o usuario clica em pausar/fecha a janela, o usuario
 * volta a mexer no PC, ou a atividade do Teams e lida no client.
 */
public class TeamsAlertController implements UserPresenceMonitor.Listener {

    private static final int DEFAULT_AWAY_MINUTES = 5;
    private static final int SNOOZE_MINUTES = 30;

    private final Consumer<String> log;
    private final SirenPlayer siren = new SirenPlayer();
    private final UserPresenceMonitor presence;

    private final JCheckBox enabledCheck = new JCheckBox(
            "Tocar sirene quando chegar atividade no Teams do client", true);
    private final JCheckBox checkIdleCheck = new JCheckBox(
            "Verificar inatividade (so tocar quando eu estiver ausente)", true);
    private final JSpinner awayMinutesSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_AWAY_MINUTES, 1, 240, 1));
    private final JLabel presenceLabel = new JLabel("Presente");
    private final JButton testButton = new JButton("Testar sirene");

    private JDialog alertDialog;
    private JLabel alertDetailLabel;
    private long snoozedUntil;

    public TeamsAlertController(Consumer<String> log) {
        this.log = log;
        siren.setErrorListener(log);
        presence = new UserPresenceMonitor(DEFAULT_AWAY_MINUTES * 60_000L, this);
        presence.setErrorListener(log);
        awayMinutesSpinner.addChangeListener(e ->
                presence.setAwayAfterMs(((Number) awayMinutesSpinner.getValue()).intValue() * 60_000L));
        checkIdleCheck.addActionListener(e -> awayMinutesSpinner.setEnabled(checkIdleCheck.isSelected()));
        testButton.addActionListener(e -> showAlert("Teste da sirene."));
    }

    public void start() {
        presence.start();
    }

    public JPanel buildSettingsPanel() {
        awayMinutesSpinner.setEnabled(checkIdleCheck.isSelected());
        JSpinner.NumberEditor spinnerEditor = new JSpinner.NumberEditor(awayMinutesSpinner);
        spinnerEditor.getTextField().setColumns(3);
        awayMinutesSpinner.setEditor(spinnerEditor);
        presenceLabel.setFont(presenceLabel.getFont().deriveFont(Font.BOLD));

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Alerta do Teams do client"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        panel.add(enabledCheck, c);

        c.gridy = 1;
        panel.add(checkIdleCheck, c);

        c.gridy = 2;
        c.gridwidth = 1;
        c.insets = new Insets(2, 24, 4, 4);
        JPanel awayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        awayRow.add(new JLabel("Considerar ausente apos"));
        awayRow.add(awayMinutesSpinner);
        awayRow.add(new JLabel("min sem mexer no mouse/teclado. Agora:"));
        awayRow.add(presenceLabel);
        panel.add(awayRow, c);

        c.gridy = 3;
        c.insets = new Insets(6, 4, 4, 4);
        panel.add(testButton, c);
        return panel;
    }

    public void onActivityDetected() {
        SwingUtilities.invokeLater(() -> {
            if (!enabledCheck.isSelected()) {
                log.accept("Alerta do Teams desativado - sem sirene.");
                return;
            }
            long now = System.currentTimeMillis();
            if (now < snoozedUntil) {
                log.accept("Alerta do Teams silenciado por mais "
                        + ((snoozedUntil - now) / 60_000 + 1) + " min - sem sirene.");
                return;
            }
            if (!checkIdleCheck.isSelected()) {
                showAlert("Chegou atividade nova no Teams do client.");
                return;
            }
            if (!presence.isAway()) {
                log.accept("Voce esta presente no Servidor - sem sirene.");
                return;
            }
            showAlert("Chegou atividade nova no Teams do client enquanto voce estava ausente.");
        });
    }

    public void onActivityCleared() {
        SwingUtilities.invokeLater(() -> {
            if (siren.isPlaying()) {
                siren.stop();
                log.accept("Sirene parada: a atividade do Teams foi lida no client.");
            }
            closeAlert();
        });
    }

    @Override
    public void onAway() {
        SwingUtilities.invokeLater(() -> {
            presenceLabel.setText("Ausente");
            log.accept("Usuario ausente do Servidor (sem input de mouse/teclado).");
        });
    }

    @Override
    public void onBack() {
        SwingUtilities.invokeLater(() -> {
            presenceLabel.setText("Presente");
            log.accept("Usuario voltou ao Servidor.");
            if (siren.isPlaying()) {
                siren.stop();
                log.accept("Sirene parada: usuario voltou.");
                if (alertDetailLabel != null) {
                    alertDetailLabel.setText("Sirene parada porque voce voltou.");
                }
            }
        });
    }

    private void showAlert(String detail) {
        log.accept("Tocando sirene: atividade no Teams do client.");
        SystemVolume.ensureMaxVolume(log);
        siren.start();
        if (alertDialog == null) {
            alertDialog = buildAlertDialog();
        }
        alertDetailLabel.setText(detail);
        alertDialog.pack();
        alertDialog.setLocationRelativeTo(null);
        alertDialog.setVisible(true);
        alertDialog.toFront();
    }

    private JDialog buildAlertDialog() {
        // Sem dono (owner null): se fosse filha da janela do Servidor, sumiria
        // junto quando ela estivesse minimizada.
        JDialog dialog = new JDialog((Frame) null, "Atividade no Teams", false);
        dialog.setAlwaysOnTop(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                pauseAlert();
            }
        });

        JLabel title = new JLabel("Nova atividade no Teams do client!");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(new Color(180, 0, 0));
        alertDetailLabel = new JLabel(" ");

        JButton pauseButton = new JButton("Pausar sirene e alerta");
        pauseButton.addActionListener(e -> pauseAlert());
        JButton snoozeButton = new JButton("Silenciar alertas por " + SNOOZE_MINUTES + " min");
        snoozeButton.addActionListener(e -> {
            snoozedUntil = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L;
            log.accept("Alertas do Teams silenciados por " + SNOOZE_MINUTES + " min.");
            pauseAlert();
        });

        JPanel text = new JPanel(new GridLayout(0, 1, 0, 6));
        text.add(title);
        text.add(alertDetailLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(pauseButton);
        buttons.add(snoozeButton);

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        content.add(text, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(pauseButton);
        return dialog;
    }

    private void pauseAlert() {
        if (siren.isPlaying()) {
            siren.stop();
            log.accept("Sirene pausada pelo usuario.");
        }
        closeAlert();
    }

    private void closeAlert() {
        if (alertDialog != null) {
            alertDialog.setVisible(false);
        }
    }
}
