package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.InputEvent;

/**
 * Aplica localmente os eventos de mouse/teclado recebidos de quem esta
 * controlando remotamente esta maquina.
 */
public class InputInjector {

    private final Robot robot;

    public InputInjector() throws AWTException {
        this.robot = new Robot();
        this.robot.setAutoDelay(1);
    }

    public void moveMouse(int x, int y) {
        robot.mouseMove(x, y);
    }

    public void mousePress(int button) {
        robot.mousePress(toMask(button));
    }

    public void mouseRelease(int button) {
        robot.mouseRelease(toMask(button));
    }

    public void mouseWheel(int rotation) {
        robot.mouseWheel(rotation);
    }

    public void keyPress(int keyCode) {
        try {
            robot.keyPress(keyCode);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void keyRelease(int keyCode) {
        try {
            robot.keyRelease(keyCode);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private int toMask(int button) {
        switch (button) {
            case 1: return InputEvent.BUTTON1_DOWN_MASK;
            case 2: return InputEvent.BUTTON2_DOWN_MASK;
            case 3: return InputEvent.BUTTON3_DOWN_MASK;
            default: return InputEvent.BUTTON1_DOWN_MASK;
        }
    }
}
