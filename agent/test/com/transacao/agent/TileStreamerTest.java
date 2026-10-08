package com.transacao.agent;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Teste sintetico (em memoria, sem tocar na tela) das partes novas do TileStreamer:
 * deteccao de rolagem (CopyRect), reconstrucao correta e classificacao foto x texto.
 * Roda com: java -cp out;test-out com.transacao.agent.TileStreamerTest
 */
public final class TileStreamerTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        scrollDetectedAndReconstructed(1920, 1080, 700, 40, 40, 800, -18, "rolagem para cima");
        scrollDetectedAndReconstructed(1920, 1080, 700, 40, 40, 800, 23, "rolagem para baixo");
        scrollDetectedAndReconstructed(1280, 720, 400, 100, 60, 640, -7, "rolagem pequena (7px)");
        noFalsePositiveOnRandomChange();
        noFalsePositiveOnUnrelatedStaticBackground();
        photographicClassification();
        System.out.println(failures == 0 ? "TODOS OS TESTES PASSARAM" : failures + " FALHA(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- cenarios ----------

    /** Fundo estatico variado + regiao de "texto" que rola dy pixels; o CopyRect deve reproduzir o quadro novo. */
    private static void scrollDetectedAndReconstructed(int W, int H, int regionH, int regionX, int regionY, int regionW, int dy, String name) throws Exception {
        int[] prev = new int[W * H];
        paintBackground(prev, W, H, 1);
        int[] content = new int[regionW * (regionH + 400)];
        paintTextLike(content, regionW, regionH + 400, 7);
        // prev mostra o conteudo a partir da linha 100; cur, deslocado por -dy (rola dy pixels)
        blitContent(prev, W, content, regionW, regionX, regionY, regionH, 100);
        int[] cur = prev.clone();
        blitContent(cur, W, content, regionW, regionX, regionY, regionH, 100 - dy);

        TileStreamer ts = newStreamer();
        List<TileStreamer.Tile> changed = changedTiles(prev, cur, W, H);
        int[] copy = ts.detectVerticalShift(cur, prev, W, H, changed);
        check(name + ": CopyRect detectado", copy != null);
        if (copy == null) {
            return;
        }
        // aplica o CopyRect sobre o quadro anterior e confere contra o atual: so as linhas expostas podem diferir
        int[] pred = prev.clone();
        TileStreamer.applyCopy(pred, W, copy);
        int wrong = 0;
        for (int y = copy[5]; y < copy[5] + copy[3]; y++) {
            for (int x = copy[4]; x < copy[4] + copy[2]; x++) {
                if (pred[y * W + x] != cur[y * W + x]) {
                    wrong++;
                }
            }
        }
        check(name + ": regiao copiada identica ao quadro novo (erradas=" + wrong + ")", wrong == 0);
        int totalChanged = countDiff(prev, cur);
        int remaining = countDiff(pred, cur);
        check(name + ": o que sobra para enviar e bem menor (" + totalChanged + " -> " + remaining + " px)", remaining < totalChanged / 4);
    }

    private static void noFalsePositiveOnRandomChange() throws Exception {
        int W = 1280;
        int H = 720;
        int[] prev = new int[W * H];
        paintBackground(prev, W, H, 3);
        int[] cur = prev.clone();
        Random r = new Random(9);
        for (int y = 100; y < 500; y++) { // area totalmente nova (nao e rolagem)
            for (int x = 100; x < 700; x++) {
                cur[y * W + x] = r.nextInt(0xFFFFFF);
            }
        }
        int[] copy = newStreamer().detectVerticalShift(cur, prev, W, H, changedTiles(prev, cur, W, H));
        check("conteudo novo aleatorio: nao inventa CopyRect", copy == null);
    }

    /** O fundo ao redor NAO rola; o hash so pode usar as colunas que realmente mudaram. */
    private static void noFalsePositiveOnUnrelatedStaticBackground() throws Exception {
        int W = 1920;
        int H = 1080;
        int[] prev = new int[W * H];
        paintBackground(prev, W, H, 5);
        int[] cur = prev.clone();
        int[] c = diffRegionBounds(prev, cur, W, H);
        check("quadros iguais: sem diferencas", c == null);
    }

    private static void photographicClassification() {
        int W = 256;
        int[] text = new int[W * W];
        paintTextLike(text, W, W, 3);
        int[] photo = new int[W * W];
        Random r = new Random(1);
        for (int i = 0; i < photo.length; i++) {
            photo[i] = (r.nextInt(256) << 16) | (r.nextInt(256) << 8) | r.nextInt(256);
        }
        TileStreamer.Tile t = new TileStreamer.Tile(0, 0, 0, 0, 128, 128);
        check("texto/interface e PNG (nao fotografico)", !TileStreamer.photographic(text, W, t));
        check("foto/video e JPEG (fotografico)", TileStreamer.photographic(photo, W, t));
    }

    // ---------- utilitarios ----------

    private static TileStreamer newStreamer() throws Exception {
        return new TileStreamer(new java.io.DataOutputStream(java.io.OutputStream.nullOutputStream()), new Object());
    }

    private static List<TileStreamer.Tile> changedTiles(int[] prev, int[] cur, int W, int H) {
        List<TileStreamer.Tile> list = new ArrayList<>();
        int T = 128;
        for (int ty = 0; ty * T < H; ty++) {
            for (int tx = 0; tx * T < W; tx++) {
                int x = tx * T;
                int y = ty * T;
                int w = Math.min(T, W - x);
                int h = Math.min(T, H - y);
                boolean diff = false;
                for (int row = 0; row < h && !diff; row++) {
                    int s = (y + row) * W + x;
                    diff = !Arrays.equals(cur, s, s + w, prev, s, s + w);
                }
                if (diff) {
                    list.add(new TileStreamer.Tile(tx, ty, x, y, w, h));
                }
            }
        }
        return list;
    }

    private static int[] diffRegionBounds(int[] a, int[] b, int W, int H) {
        return TileStreamer.diffBounds(a, b, W, changedTiles(a, b, W, H));
    }

    private static int countDiff(int[] a, int[] b) {
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                n++;
            }
        }
        return n;
    }

    /** Fundo "de desktop": faixas e ruido deterministico por linha (cada linha e diferente). */
    private static void paintBackground(int[] px, int W, int H, int seed) {
        Random r = new Random(seed);
        for (int y = 0; y < H; y++) {
            int base = r.nextInt(0xFFFFFF);
            for (int x = 0; x < W; x++) {
                px[y * W + x] = base ^ (int) (((x * 2654435761L) >>> 8) & 0x3F3F3F);
            }
        }
    }

    /** Conteudo tipo texto: linhas com poucas cores, cada linha diferente das outras, com entrelinhas em branco. */
    private static void paintTextLike(int[] px, int W, int H, int seed) {
        Random r = new Random(seed);
        Arrays.fill(px, 0xFAFAFA);
        for (int y = 0; y < H; y++) {
            if (y % 18 >= 4 && y % 18 < 14) { // linha de texto: pixels pretos em posicoes pseudo-aleatorias
                int line = y / 18;
                Random lr = new Random(seed * 1000L + line * 31L + y % 18);
                for (int x = 10; x < W - 10; x++) {
                    if (lr.nextInt(3) == 0) {
                        px[y * W + x] = 0x101010;
                    }
                }
            }
        }
    }

    private static void blitContent(int[] screen, int W, int[] content, int cw, int rx, int ry, int rh, int top) {
        for (int y = 0; y < rh; y++) {
            System.arraycopy(content, (top + y) * cw, screen, (ry + y) * W + rx, cw);
        }
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok  " : "  FALHOU  ") + what);
        if (!ok) {
            failures++;
        }
    }
}

