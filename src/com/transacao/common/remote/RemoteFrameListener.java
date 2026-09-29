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

    /** Tamanho efetivamente transmitido agora (pode ser menor que onScreenSize, se adaptado ao viewport de quem ve). */
    void onStreamSize(Dimension size);

    void onTile(int x, int y, BufferedImage tileImage);
}
