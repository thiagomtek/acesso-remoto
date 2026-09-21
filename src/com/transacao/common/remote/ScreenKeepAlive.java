package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * Servico em segundo plano que evita a suspensao do computador e o desligamento
 * do monitor por inatividade, enviando periodicamente um pulso de atividade:
 * um leve movimento do mouse (1px e volta) seguido de CapsLock (pressiona e
 * solta duas vezes para preservar o estado original de maiuscula/minuscula).
 * O movimento de mouse e o sinal mais confiavel para resetar o timer de
 * desligamento de tela do Windows - so a tecla CapsLock sozinha as vezes nao
 * e suficiente, deixando a tela apagar entre um pulso e outro (o que faz a
 * proxima captura de tela remota ficar bem mais lenta, ate a tela "acordar").
 */
public class ScreenKeepAlive implements Runnable {

    // Reduzido de 5 minutos para 1: o intervalo de desligar a tela configurado
    // no Windows pode ser bem menor que 5 minutos, entao um pulso a cada 5min
    // deixava a tela apagar (e a captura remota ficar lenta) entre um pulso e
    // outro.
    private static final long DEFAULT_INTERVAL_MS = 60 * 1000L; // 1 minuto

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
            // Move o mouse 1px e volta - e o sinal que o gerenciador de energia
            // do Windows mais confiavelmente reconhece como atividade real,
            // resetando tanto o timer de suspensao quanto o de desligar a tela.
            Point p = MouseInfo.getPointerInfo().getLocation();
            robot.mouseMove(p.x + 1, p.y);
            robot.mouseMove(p.x, p.y);

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
                logConsumer.accept("Keep-alive: sinal enviado (mouse + CapsLock) para manter computador ativo.");
            }
        } catch (Exception e) {
            if (logConsumer != null) {
                logConsumer.accept("Keep-alive falhou ao emitir sinal: " + e.getMessage());
            }
        }
    }
}
