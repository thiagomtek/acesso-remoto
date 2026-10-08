package com.transacao.common.remote;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Aplica localmente os eventos de mouse/teclado recebidos de quem esta
 * controlando remotamente esta maquina.
 */
public class InputInjector {

    private final Robot robot;
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
    private Process unicodeHelper;
    private BufferedWriter unicodeHelperIn;
    private BufferedReader unicodeHelperOut;
    private volatile boolean nativeHelperEnabled = true;
    /** A primeira partida do PowerShell/Add-Type pode levar segundos; as seguintes devem ser instantaneas. */
    private boolean unicodeHelperFresh;
    /** Se a politica da maquina bloquear o helper, nao transforma cada caractere em um timeout longo. */
    private long unicodeRetryAfterMs;

    public void disableNativeHelper() {
        this.nativeHelperEnabled = false;
        closeUnicodeHelper();
    }

    public void enableNativeHelper() {
        this.nativeHelperEnabled = true;
    }

    public boolean isNativeHelperEnabled() {
        return nativeHelperEnabled;
    }

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
        typeText(String.valueOf(c));
    }

    /**
     * Insere texto como texto, e nao como uma sequencia de teclas do layout remoto. No Windows,
     * SendInput/Unicode e usado inclusive para letras, digitos e pontuacao: e a diferenca que evita
     * Mac US -> Windows ABNT2/US-Intl trocar caracteres. Retorna falso quando foi preciso usar o
     * fallback legado de compatibilidade.
     */
    public boolean typeText(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        boolean nativeUnicode = windows;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                keyPress(KeyEvent.VK_ENTER);
                keyRelease(KeyEvent.VK_ENTER);
                continue;
            }
            if (c == '\t') {
                keyPress(KeyEvent.VK_TAB);
                keyRelease(KeyEvent.VK_TAB);
                continue;
            }
            if (c == '\b') {
                keyPress(KeyEvent.VK_BACK_SPACE);
                keyRelease(KeyEvent.VK_BACK_SPACE);
                continue;
            }
            if (windows && typeUnicodeWindows(c)) {
                continue;
            }
            nativeUnicode = false;
            typeCharFallback(c);
        }
        return nativeUnicode;
    }

    /** Fallback para plataformas sem SendInput Unicode. */
    private void typeCharFallback(char c) {
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
        // No Windows, digita via SendInput/KEYEVENTF_UNICODE (funciona para QUALQUER code point,
        // independente do layout/codepage ativo no lado remoto - ver typeUnicodeWindows). Em outros
        // SOs (sem esse helper), cai no truque antigo de Alt+Numpad (so cobre Latin-1, ate 255).
        typeAltNumpad(c);
    }

    /**
     * Injeta a tecla pela sua posicao fisica (scan code Set 1) quando o Windows oferece SendInput.
     * O chamador faz fallback para Robot/VK se isso nao estiver disponivel, preservando maquinas
     * restritas e sistemas nao-Windows.
     */
    public boolean keyPressPhysical(int scanCode, boolean extended) {
        return windows && nativeHelperEnabled && typeScanWindows(scanCode, extended, false);
    }

    public boolean keyReleasePhysical(int scanCode, boolean extended) {
        return windows && nativeHelperEnabled && typeScanWindows(scanCode, extended, true);
    }

    /** Inicia a ponte nativa ao abrir a sessao, sem digitar nada nem capturar a tela. */
    public synchronized boolean prepareNativeKeyboard() {
        if (!windows || !nativeHelperEnabled || System.currentTimeMillis() < unicodeRetryAfterMs) return false;
        try {
            ensureUnicodeHelper();
            writeHelper("PING");
            return waitHelperReply("PONG");
        } catch (Exception e) {
            disableUnicodeHelperBriefly();
            return false;
        }
    }

    /**
     * Digita um caractere via SendInput do Win32 com KEYEVENTF_UNICODE, que injeta o code point
     * exato sem depender de VK/layout/codepage - diferente do Alt+Numpad (que só cobre 0-255 via
     * a codepage ANSI ativa, e nao tem truque confiavel sem o registro EnableHexNumpad para o
     * resto do Unicode, como aspas curvas "/" , travessao —, etc. vindos de autocorrecao do Mac).
     * Mantem um processo PowerShell auxiliar vivo (mesmo padrao usado em UserPresenceMonitor) para
     * nao pagar o custo de iniciar um processo novo a cada caractere digitado.
     */
    private synchronized boolean typeUnicodeWindows(char c) {
        try {
            if (!nativeHelperEnabled || System.currentTimeMillis() < unicodeRetryAfterMs) {
                return false;
            }
            ensureUnicodeHelper();
            if (unicodeHelperIn == null) {
                return false;
            }
            writeHelper("U:" + (int) c);
            return waitHelperReply("2");
        } catch (Exception e) {
            disableUnicodeHelperBriefly();
            return false;
        }
    }

    private synchronized boolean typeScanWindows(int scanCode, boolean extended, boolean keyUp) {
        try {
            if (!nativeHelperEnabled || System.currentTimeMillis() < unicodeRetryAfterMs) {
                return false;
            }
            ensureUnicodeHelper();
            if (unicodeHelperIn == null) {
                return false;
            }
            writeHelper("S:" + scanCode + ":" + (extended ? 1 : 0) + ":" + (keyUp ? 1 : 0));
            return waitHelperReply("1");
        } catch (Exception e) {
            disableUnicodeHelperBriefly();
            return false;
        }
    }

    /** Espera mais so na primeira chamada: Add-Type e o processo PowerShell ainda estao iniciando. */
    private boolean waitHelperReply(String expected) throws Exception {
        long timeoutMs = unicodeHelperFresh ? 5_000 : 100;
        unicodeHelperFresh = false;
        long limit = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < limit) {
            if (unicodeHelperOut != null && unicodeHelperOut.ready()) {
                boolean ok = expected.equals(unicodeHelperOut.readLine());
                if (!ok) disableUnicodeHelperBriefly();
                return ok;
            }
            Thread.sleep(2);
        }
        disableUnicodeHelperBriefly();
        return false;
    }

    private void disableUnicodeHelperBriefly() {
        closeUnicodeHelper();
        unicodeRetryAfterMs = System.currentTimeMillis() + 30_000;
    }

    private void writeHelper(String command) throws Exception {
        unicodeHelperIn.write(command);
        unicodeHelperIn.newLine();
        unicodeHelperIn.flush();
    }

    private static final String UNICODE_HELPER_SCRIPT =
            "Add-Type @'\n" +
            "using System;\n" +
            "using System.Runtime.InteropServices;\n" +
            "public static class AssistenteType {\n" +
            "  [StructLayout(LayoutKind.Sequential)]\n" +
            "  struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }\n" +
            "  [StructLayout(LayoutKind.Sequential)]\n" +
            "  struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }\n" +
            "  [StructLayout(LayoutKind.Explicit)]\n" +
            "  struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }\n" +
            "  [StructLayout(LayoutKind.Sequential)]\n" +
            "  struct INPUT { public uint type; public InputUnion u; }\n" +
            "  [DllImport(\"user32.dll\", SetLastError = true)]\n" +
            "  static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);\n" +
            "  const uint INPUT_KEYBOARD = 1;\n" +
            "  const uint KEYEVENTF_UNICODE = 0x0004;\n" +
            "  const uint KEYEVENTF_SCANCODE = 0x0008;\n" +
            "  const uint KEYEVENTF_EXTENDEDKEY = 0x0001;\n" +
            "  const uint KEYEVENTF_KEYUP = 0x0002;\n" +
            "  public static int InputSize() { return Marshal.SizeOf(typeof(INPUT)); }\n" +
            "  public static uint TypeUnicode(ushort codeUnit) {\n" +
            "    INPUT[] inputs = new INPUT[2];\n" +
            "    inputs[0].type = INPUT_KEYBOARD;\n" +
            "    inputs[0].u.ki = new KEYBDINPUT { wVk = 0, wScan = codeUnit, dwFlags = KEYEVENTF_UNICODE, time = 0, dwExtraInfo = IntPtr.Zero };\n" +
            "    inputs[1].type = INPUT_KEYBOARD;\n" +
            "    inputs[1].u.ki = new KEYBDINPUT { wVk = 0, wScan = codeUnit, dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, time = 0, dwExtraInfo = IntPtr.Zero };\n" +
            "    return SendInput(2, inputs, Marshal.SizeOf(typeof(INPUT)));\n" +
            "  }\n" +
            "  public static uint TypeScan(ushort scan, bool extended, bool up) {\n" +
            "    INPUT[] inputs = new INPUT[1];\n" +
            "    uint flags = KEYEVENTF_SCANCODE | (extended ? KEYEVENTF_EXTENDEDKEY : 0) | (up ? KEYEVENTF_KEYUP : 0);\n" +
            "    inputs[0].type = INPUT_KEYBOARD;\n" +
            "    inputs[0].u.ki = new KEYBDINPUT { wVk = 0, wScan = scan, dwFlags = flags, time = 0, dwExtraInfo = IntPtr.Zero };\n" +
            "    return SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));\n" +
            "  }\n" +
            "}\n" +
            "'@\n" +
            "[Console]::InputEncoding = [System.Text.Encoding]::UTF8\n" +
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\n" +
            "while ($line = [Console]::In.ReadLine()) {\n" +
            "  if ($line -eq 'PING') { [Console]::Out.WriteLine('PONG') }\n" +
            "  elseif ($line -eq 'SIZE') { [Console]::Out.WriteLine([AssistenteType]::InputSize()) }\n" +
            "  elseif ($line -match '^U:(\\d+)$') { [Console]::Out.WriteLine([AssistenteType]::TypeUnicode([uint16]$Matches[1])) }\n" +
            "  elseif ($line -match '^S:(\\d+):([01]):([01])$') { [Console]::Out.WriteLine([AssistenteType]::TypeScan([uint16]$Matches[1], $Matches[2] -eq '1', $Matches[3] -eq '1')) }\n" +
            "}\n";

    private synchronized void ensureUnicodeHelper() throws Exception {
        if (unicodeHelper != null && unicodeHelper.isAlive()) {
            return;
        }
        closeUnicodeHelper();
        String encoded = Base64.getEncoder().encodeToString(UNICODE_HELPER_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        unicodeHelper = pb.start();
        unicodeHelperFresh = true;
        unicodeHelperIn = new BufferedWriter(new OutputStreamWriter(unicodeHelper.getOutputStream(), StandardCharsets.UTF_8));
        unicodeHelperOut = new BufferedReader(new InputStreamReader(unicodeHelper.getInputStream(), StandardCharsets.UTF_8));
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeUnicodeHelper));
    }

    private synchronized void closeUnicodeHelper() {
        try {
            if (unicodeHelperIn != null) {
                unicodeHelperIn.close();
            }
        } catch (Exception ignored) {
        }
        unicodeHelperIn = null;
        try {
            if (unicodeHelperOut != null) {
                unicodeHelperOut.close();
            }
        } catch (Exception ignored) {
        }
        unicodeHelperOut = null;
        if (unicodeHelper != null) {
            unicodeHelper.destroy();
            unicodeHelper = null;
        }
        unicodeHelperFresh = false;
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
