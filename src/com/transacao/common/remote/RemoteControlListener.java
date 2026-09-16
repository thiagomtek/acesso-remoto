package com.transacao.common.remote;

/**
 * Callback usado por quem COMPARTILHA a tela (o lado controlado) para ser
 * avisado de pedidos de inicio/fim de sessao e dos eventos de mouse/teclado
 * recebidos do lado que controla.
 */
public interface RemoteControlListener {

    void onStartRequested();

    void onStopRequested();

    void onMouseMove(int x, int y);

    void onMousePress(int button);

    void onMouseRelease(int button);

    void onMouseWheel(int rotation);

    void onKeyPress(int keyCode);

    void onKeyRelease(int keyCode);
}
