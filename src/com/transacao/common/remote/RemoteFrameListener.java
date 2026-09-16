package com.transacao.common.remote;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/**
 * Callback usado por quem RECEBE a tela compartilhada (o lado que controla)
 * para ser avisado do tamanho da tela remota e dos blocos (tiles) que vao
 * mudando, sem perda de qualidade (PNG).
 */
public interface RemoteFrameListener {

    void onScreenSize(Dimension size, double dpiScale);

    void onTile(int x, int y, BufferedImage tileImage);
}
