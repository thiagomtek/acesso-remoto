package com.transacao.common.remote;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;

/**
 * Painel que mostra a tela recebida do lado que compartilha (montada a
 * partir de blocos/tiles atualizados sem perda de qualidade) e encaminha
 * mouse/teclado capturados nele para quem esta compartilhando, escalando
 * as coordenadas para o tamanho real da tela remota. A imagem e esticada
 * para ocupar todo o espaco disponivel do painel (pode distorcer levemente
 * se a proporcao da tela remota for diferente da area do painel).
 */
public class RemoteViewerPanel extends JPanel {

    private volatile BufferedImage canvas;
    private volatile Dimension remoteScreenSize = new Dimension(1, 1);
    private volatile RemoteInputSender sender;

    private volatile int drawX;
    private volatile int drawY;
    private volatile int drawWidth = 1;
    private volatile int drawHeight = 1;

    public RemoteViewerPanel() {
        setPreferredSize(new Dimension(960, 560));
        setBackground(Color.BLACK);
        setFocusable(true);
        installInputForwarding();
    }

    public void setInputSender(RemoteInputSender sender) {
        this.sender = sender;
    }

    public void setRemoteScreenSize(Dimension size) {
        this.remoteScreenSize = size;
        this.canvas = new BufferedImage(Math.max(1, size.width), Math.max(1, size.height), BufferedImage.TYPE_INT_RGB);
        repaint();
    }

    public void applyTile(int x, int y, BufferedImage tileImage) {
        BufferedImage current = canvas;
        if (current == null || tileImage == null) {
            return;
        }
        Graphics2D g = current.createGraphics();
        g.drawImage(tileImage, x, y, null);
        g.dispose();
        repaint();
    }

    public void clear() {
        this.canvas = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage image = canvas;
        if (image == null) {
            return;
        }

        drawWidth = Math.max(1, getWidth());
        drawHeight = Math.max(1, getHeight());
        drawX = 0;
        drawY = 0;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.drawImage(image, drawX, drawY, drawWidth, drawHeight, null);
    }

    private void installInputForwarding() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (sender == null) {
                    return;
                }
                Point p = toRemote(e.getPoint());
                sender.sendMouseMove(p.x, p.y);
                sender.sendMousePress(e.getButton());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (sender == null) {
                    return;
                }
                Point p = toRemote(e.getPoint());
                sender.sendMouseMove(p.x, p.y);
                sender.sendMouseRelease(e.getButton());
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (sender == null) {
                    return;
                }
                Point p = toRemote(e.getPoint());
                sender.sendMouseMove(p.x, p.y);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (sender == null) {
                    return;
                }
                Point p = toRemote(e.getPoint());
                sender.sendMouseMove(p.x, p.y);
            }
        });
        addMouseWheelListener(e -> {
            if (sender != null) {
                sender.sendMouseWheel(e.getWheelRotation());
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (sender != null) {
                    sender.sendKeyPress(e.getKeyCode());
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (sender != null) {
                    sender.sendKeyRelease(e.getKeyCode());
                }
            }
        });
    }

    private Point toRemote(Point local) {
        int relativeX = local.x - drawX;
        int relativeY = local.y - drawY;
        double sx = remoteScreenSize.getWidth() / Math.max(1, drawWidth);
        double sy = remoteScreenSize.getHeight() / Math.max(1, drawHeight);
        int rx = (int) Math.round(relativeX * sx);
        int ry = (int) Math.round(relativeY * sy);
        rx = Math.max(0, Math.min(remoteScreenSize.width - 1, rx));
        ry = Math.max(0, Math.min(remoteScreenSize.height - 1, ry));
        return new Point(rx, ry);
    }
}
