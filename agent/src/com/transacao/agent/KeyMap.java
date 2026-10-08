package com.transacao.agent;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

/** Traduz KeyboardEvent.code do navegador para o codigo de tecla virtual do Java (Robot). */
final class KeyMap {

    private static final Map<String, Integer> MAP = new HashMap<>();
    /**
     * Scancodes Set 1 do Windows. KeyboardEvent.code descreve a POSICAO fisica da tecla, nao o
     * caractere resultante; por isso esta tabela e usada apenas para atalhos, navegacao e
     * modificadores. Texto normal usa Unicode e nao passa por aqui.
     */
    private static final Map<String, PhysicalKey> PHYSICAL = new HashMap<>();

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            MAP.put("Key" + c, KeyEvent.VK_A + (c - 'A'));
            physical("Key" + c, new int[] {0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17,
                    0x24, 0x25, 0x26, 0x32, 0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F,
                    0x11, 0x2D, 0x15, 0x2C}[c - 'A'], false);
        }
        for (char c = '0'; c <= '9'; c++) {
            MAP.put("Digit" + c, KeyEvent.VK_0 + (c - '0'));
            MAP.put("Numpad" + c, KeyEvent.VK_NUMPAD0 + (c - '0'));
        }
        int[] digits = {0x0B, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A};
        for (int n = 0; n <= 9; n++) {
            physical("Digit" + n, digits[n], false);
            physical("Numpad" + n, new int[] {0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49}[n], false);
        }
        for (int n = 1; n <= 24; n++) {
            MAP.put("F" + n, KeyEvent.VK_F1 + (n - 1));
            int[] function = {0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44,
                    0x57, 0x58, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x76};
            physical("F" + n, function[n - 1], false);
        }
        MAP.put("Enter", KeyEvent.VK_ENTER);
        MAP.put("NumpadEnter", KeyEvent.VK_ENTER);
        MAP.put("Escape", KeyEvent.VK_ESCAPE);
        MAP.put("Backspace", KeyEvent.VK_BACK_SPACE);
        MAP.put("Tab", KeyEvent.VK_TAB);
        MAP.put("Space", KeyEvent.VK_SPACE);
        MAP.put("ArrowLeft", KeyEvent.VK_LEFT);
        MAP.put("ArrowRight", KeyEvent.VK_RIGHT);
        MAP.put("ArrowUp", KeyEvent.VK_UP);
        MAP.put("ArrowDown", KeyEvent.VK_DOWN);
        MAP.put("Home", KeyEvent.VK_HOME);
        MAP.put("End", KeyEvent.VK_END);
        MAP.put("PageUp", KeyEvent.VK_PAGE_UP);
        MAP.put("PageDown", KeyEvent.VK_PAGE_DOWN);
        MAP.put("Insert", KeyEvent.VK_INSERT);
        MAP.put("Delete", KeyEvent.VK_DELETE);
        MAP.put("ShiftLeft", KeyEvent.VK_SHIFT);
        MAP.put("ShiftRight", KeyEvent.VK_SHIFT);
        MAP.put("ControlLeft", KeyEvent.VK_CONTROL);
        MAP.put("ControlRight", KeyEvent.VK_CONTROL);
        MAP.put("AltLeft", KeyEvent.VK_ALT);
        // Option direito do Mac e Alt direito de um teclado US devem continuar sendo Alt no Windows.
        // VK_ALT_GRAPH sintetiza Ctrl+Alt e quebra atalhos/teclas em layouts US.
        MAP.put("AltRight", KeyEvent.VK_ALT);
        MAP.put("MetaLeft", KeyEvent.VK_WINDOWS);
        MAP.put("MetaRight", KeyEvent.VK_WINDOWS);
        MAP.put("ContextMenu", KeyEvent.VK_CONTEXT_MENU);
        MAP.put("CapsLock", KeyEvent.VK_CAPS_LOCK);
        MAP.put("NumLock", KeyEvent.VK_NUM_LOCK);
        MAP.put("ScrollLock", KeyEvent.VK_SCROLL_LOCK);
        MAP.put("PrintScreen", KeyEvent.VK_PRINTSCREEN);
        MAP.put("Pause", KeyEvent.VK_PAUSE);
        MAP.put("Minus", KeyEvent.VK_MINUS);
        MAP.put("Equal", KeyEvent.VK_EQUALS);
        MAP.put("BracketLeft", KeyEvent.VK_OPEN_BRACKET);
        MAP.put("BracketRight", KeyEvent.VK_CLOSE_BRACKET);
        MAP.put("Backslash", KeyEvent.VK_BACK_SLASH);
        MAP.put("IntlBackslash", KeyEvent.VK_BACK_SLASH);
        MAP.put("IntlYen", KeyEvent.VK_BACK_SLASH);
        MAP.put("Semicolon", KeyEvent.VK_SEMICOLON);
        MAP.put("Quote", KeyEvent.VK_QUOTE);
        MAP.put("Backquote", KeyEvent.VK_BACK_QUOTE);
        MAP.put("Comma", KeyEvent.VK_COMMA);
        MAP.put("Period", KeyEvent.VK_PERIOD);
        MAP.put("Slash", KeyEvent.VK_SLASH);
        MAP.put("NumpadAdd", KeyEvent.VK_ADD);
        MAP.put("NumpadSubtract", KeyEvent.VK_SUBTRACT);
        MAP.put("NumpadMultiply", KeyEvent.VK_MULTIPLY);
        MAP.put("NumpadDivide", KeyEvent.VK_DIVIDE);
        MAP.put("NumpadDecimal", KeyEvent.VK_DECIMAL);

        physical("Enter", 0x1C, false); physical("NumpadEnter", 0x1C, true);
        physical("Escape", 0x01, false); physical("Backspace", 0x0E, false);
        physical("Tab", 0x0F, false); physical("Space", 0x39, false);
        physical("ArrowLeft", 0x4B, true); physical("ArrowRight", 0x4D, true);
        physical("ArrowUp", 0x48, true); physical("ArrowDown", 0x50, true);
        physical("Home", 0x47, true); physical("End", 0x4F, true);
        physical("PageUp", 0x49, true); physical("PageDown", 0x51, true);
        physical("Insert", 0x52, true); physical("Delete", 0x53, true);
        physical("ShiftLeft", 0x2A, false); physical("ShiftRight", 0x36, false);
        physical("ControlLeft", 0x1D, false); physical("ControlRight", 0x1D, true);
        physical("AltLeft", 0x38, false); physical("AltRight", 0x38, true);
        physical("MetaLeft", 0x5B, true); physical("MetaRight", 0x5C, true);
        physical("ContextMenu", 0x5D, true);
        physical("CapsLock", 0x3A, false); physical("NumLock", 0x45, false); physical("ScrollLock", 0x46, false);
        physical("Minus", 0x0C, false); physical("Equal", 0x0D, false);
        physical("BracketLeft", 0x1A, false); physical("BracketRight", 0x1B, false);
        physical("Backslash", 0x2B, false); physical("IntlBackslash", 0x56, false); physical("IntlYen", 0x7D, false);
        physical("Semicolon", 0x27, false); physical("Quote", 0x28, false); physical("Backquote", 0x29, false);
        physical("Comma", 0x33, false); physical("Period", 0x34, false); physical("Slash", 0x35, false);
        physical("NumpadAdd", 0x4E, false); physical("NumpadSubtract", 0x4A, false);
        physical("NumpadMultiply", 0x37, false); physical("NumpadDivide", 0x35, true); physical("NumpadDecimal", 0x53, false);
    }

    private KeyMap() {
    }

    /** @return o VK_*, ou -1 se a tecla nao tem equivalente. */
    static int toVk(String code) {
        Integer v = code == null ? null : MAP.get(code);
        return v == null ? -1 : v;
    }

    static PhysicalKey toPhysical(String code) {
        return code == null ? null : PHYSICAL.get(code);
    }

    private static void physical(String code, int scan, boolean extended) {
        PHYSICAL.put(code, new PhysicalKey(scan, extended));
    }

    static final class PhysicalKey {
        final int scan;
        final boolean extended;

        PhysicalKey(int scan, boolean extended) {
            this.scan = scan;
            this.extended = extended;
        }
    }
}
