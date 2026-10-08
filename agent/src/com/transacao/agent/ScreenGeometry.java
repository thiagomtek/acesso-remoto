package com.transacao.agent;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

/** Geometria do monitor principal. Sem dependencia do WebRTC: usada tambem no modo compativel. */
final class ScreenGeometry {

    private ScreenGeometry() {
    }

    private static GraphicsConfiguration gc() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
    }

    /** Resolucao fisica do monitor (pixels reais, nao a reduzida pela escala do Windows). */
    static int[] physicalSize() {
        GraphicsConfiguration gc = gc();
        Rectangle logical = gc.getBounds();
        double scale = gc.getDefaultTransform().getScaleX();
        return new int[] {(int) Math.round(logical.width * scale), (int) Math.round(logical.height * scale)};
    }

    /** Area logica do monitor (o que o Robot usa para posicionar o mouse). */
    static Rectangle logicalBounds() {
        return gc().getBounds();
    }
}
