package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * Servico em segundo plano que evita a suspensao do computador e o desligamento
 * do monitor por inatividade, enviando a cada 5 minutos um pulso da tecla CapsLock.
 * Pressiona e solta a tecla duas vezes consecutivas para preservar o estado original
 * (maiuscula/minuscula) da tecla.
 */
public class ScreenKeepAlive implements Runnable {

    private static final long DEFAULT_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutos

    private final long intervalMs;
    private final Robot robot;
    private final Consumer<String> logConsumer;
    private volatile boolean running = true;

    public ScreenKeepAlive(Consumer<String> logConsumer) {
        this(DEFAULT_INTERVAL_MS, logConsumer);
    }

    public ScreenKeepAlive(long intervalMs, Consumer<String> logConsumer) {
        this.intervalMs = intervalMs;
        this.logConsumer = logConsumer;
        Robot r = null;
        try {
            r = new Robot();
        } catch (AWTException ignored) {
        }
        this.robot = r;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (!running) {
                break;
            }

            triggerKeepAlive();
        }
    }

    public void triggerKeepAlive() {
        if (robot == null) {
            return;
        }
        try {
            // Pressiona e solta a tecla CapsLock duas vezes para registrar atividade
            // no sistema operacional sem alterar o estado de maiuscula/minuscula.
            robot.keyPress(KeyEvent.VK_CAPS_LOCK);
            robot.keyRelease(KeyEvent.VK_CAPS_LOCK);
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            robot.keyPress(KeyEvent.VK_CAPS_LOCK);
            robot.keyRelease(KeyEvent.VK_CAPS_LOCK);

            if (logConsumer != null) {
                logConsumer.accept("Keep-alive: sinal enviado (CapsLock) para manter computador ativo.");
            }
        } catch (Exception e) {
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive falhou ao emitir sinal: " + e.getMessage());
            }
        }
    }
}
