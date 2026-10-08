package com.transacao.agent;

/** Teste puro: garante os mapeamentos de posicao usados por atalhos, sem abrir Robot nem tela. */
public final class KeyMapTest {
    private static int failures;

    public static void main(String[] args) {
        check("A fisico US", scan("KeyA") == 0x1E);
        check("ponto fisico US", scan("Period") == 0x34);
        check("virgula fisica US", scan("Comma") == 0x33);
        check("Control direito e estendido", KeyMap.toPhysical("ControlRight").extended);
        check("Alt direito nao vira AltGr", KeyMap.toVk("AltRight") == java.awt.event.KeyEvent.VK_ALT);
        check("Command/Meta ainda tem VK Windows para fallback", KeyMap.toVk("MetaLeft") == java.awt.event.KeyEvent.VK_WINDOWS);
        check("F12", scan("F12") == 0x58);
        check("F24", scan("F24") == 0x76);
        check("seta e estendida", scan("ArrowLeft") == 0x4B && KeyMap.toPhysical("ArrowLeft").extended);
        check("codigo desconhecido recusa", KeyMap.toVk("NoSuchKey") == -1 && KeyMap.toPhysical("NoSuchKey") == null);
        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static int scan(String code) {
        KeyMap.PhysicalKey key = KeyMap.toPhysical(code);
        return key == null ? -1 : key.scan;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) failures++;
    }
}
