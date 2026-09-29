package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

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
        if (isLockingKey(keyCode)) {
            // CapsLock/NumLock/ScrollLock sao teclas de "trava" com estado
            // proprio no SO - simular so o pressionar/soltar bruto (robot.keyPress
            // + keyRelease) nem sempre alterna esse estado de forma confiavel.
            // A API de locking key state do Toolkit e feita exatamente para isso.
            toggleLockingKey(keyCode);
            return;
        }
        try {
            robot.keyPress(keyCode);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void keyRelease(int keyCode) {
        if (isLockingKey(keyCode)) {
            // O toggle inteiro ja aconteceu no keyPress (nao e um press+release
            // separado como as demais teclas) - nao faz nada aqui.
            return;
        }
        try {
            robot.keyRelease(keyCode);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private boolean isLockingKey(int keyCode) {
        return keyCode == KeyEvent.VK_CAPS_LOCK
                || keyCode == KeyEvent.VK_NUM_LOCK
                || keyCode == KeyEvent.VK_SCROLL_LOCK;
    }

    private void toggleLockingKey(int keyCode) {
        try {
            Toolkit toolkit = Toolkit.getDefaultToolkit();
            boolean current = toolkit.getLockingKeyState(keyCode);
            toolkit.setLockingKeyState(keyCode, !current);
        } catch (Exception e) {
            // Plataforma sem suporte a essa API (ex: alguns ambientes Linux/Mac) -
            // cai de volta no pressionar/soltar bruto, melhor que nada.
            try {
                robot.keyPress(keyCode);
                robot.keyRelease(keyCode);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Digita um caractere Unicode diretamente no sistema host, garantindo fidelidade 1:1
     * independente de diferencas de layout (ex: Mac acessando Windows com layout US-Intl).
     */
    public void typeChar(char c) {
        if (c == '\n') {
            keyPress(KeyEvent.VK_ENTER);
            keyRelease(KeyEvent.VK_ENTER);
            return;
        }
        if (c == '\t') {
            keyPress(KeyEvent.VK_TAB);
            keyRelease(KeyEvent.VK_TAB);
            return;
        }
        if (c == '\b') {
            keyPress(KeyEvent.VK_BACK_SPACE);
            keyRelease(KeyEvent.VK_BACK_SPACE);
            return;
        }
        if (c == ' ') {
            keyPress(KeyEvent.VK_SPACE);
            keyRelease(KeyEvent.VK_SPACE);
            return;
        }
        if (c >= 'a' && c <= 'z') {
            int vk = KeyEvent.VK_A + (c - 'a');
            keyPress(vk);
            keyRelease(vk);
            return;
        }
        if (c >= 'A' && c <= 'Z') {
            int vk = KeyEvent.VK_A + (c - 'A');
            try {
                robot.keyPress(KeyEvent.VK_SHIFT);
                robot.keyPress(vk);
                robot.keyRelease(vk);
            } finally {
                try {
                    robot.keyRelease(KeyEvent.VK_SHIFT);
                } catch (Exception ignored) {
                }
            }
            return;
        }
        if (c >= '0' && c <= '9') {
            int vk = KeyEvent.VK_0 + (c - '0');
            keyPress(vk);
            keyRelease(vk);
            return;
        }

        // Para pontuacoes, acentos (US-Intl dead keys) e caracteres especiais (ex: ç, ã, é, ?, /, @, ~, ^, ', ", etc.):
        // Digita via Alt + Numpad (codigo numerico ANSI/Unicode), garantindo que o Windows gere o caractere exato.
        typeAltNumpad(c);
    }

    private void typeAltNumpad(char c) {
        int code = (int) c;
        String digits;
        if (code <= 255) {
            digits = "0" + code;
        } else {
            digits = String.valueOf(code);
        }
        try {
            // Quem envia o caractere pode ter chegado ate ele digitando com Shift
            // no teclado fisico (ex: Shift+1 para "!") - o Shift bruto ja foi
            // encaminhado e fica fisicamente pressionado aqui ANTES desse
            // caractere chegar. Se o Alt+Numpad rodar com o Shift ainda
            // pressionado, a combinacao Alt+Shift e o atalho padrao do Windows
            // para TROCAR O LAYOUT DE TECLADO, quebrando essa digitacao (e as
            // seguintes, se o layout realmente mudar) - por isso soltamos
            // Shift/Ctrl aqui antes de comecar, independente do que estiver
            // fisicamente pressionado no momento.
            try {
                robot.keyRelease(KeyEvent.VK_SHIFT);
            } catch (Exception ignored) {
            }
            try {
                robot.keyRelease(KeyEvent.VK_CONTROL);
            } catch (Exception ignored) {
            }

            robot.keyPress(KeyEvent.VK_ALT);
            for (char digit : digits.toCharArray()) {
                int numpadKey = KeyEvent.VK_NUMPAD0 + (digit - '0');
                robot.keyPress(numpadKey);
                robot.keyRelease(numpadKey);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                robot.keyRelease(KeyEvent.VK_ALT);
            } catch (Exception ignored) {
            }
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
