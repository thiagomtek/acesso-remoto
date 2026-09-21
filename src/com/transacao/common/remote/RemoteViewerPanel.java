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
                if (sender == null) {
                    return;
                }
                int code = normalizeKeyCode(e);
                if (isSpecialOrShortcutKey(e, code)) {
                    sender.sendKeyPress(code);
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (sender == null) {
                    return;
                }
                int code = normalizeKeyCode(e);
                if (isSpecialOrShortcutKey(e, code)) {
                    sender.sendKeyRelease(code);
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                if (sender == null) {
                    return;
                }
                char c = e.getKeyChar();
                if (c == '\b' || c == '\t' || c == '\n' || c == 27 || c == 127 || c == KeyEvent.CHAR_UNDEFINED) {
                    return;
                }
                if (!e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                    sender.sendKeyTyped(c);
                }
            }
        });
    }

    private int normalizeKeyCode(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_META) {
            return KeyEvent.VK_CONTROL;
        }
        return code;
    }

    private boolean isSpecialOrShortcutKey(KeyEvent e, int code) {
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
            return true;
        }
        switch (code) {
            case KeyEvent.VK_CONTROL:
            case KeyEvent.VK_ALT:
            case KeyEvent.VK_SHIFT:
            case KeyEvent.VK_META:
            case KeyEvent.VK_ALT_GRAPH:
            case KeyEvent.VK_WINDOWS:
            case KeyEvent.VK_CONTEXT_MENU:
            case KeyEvent.VK_ENTER:
            case KeyEvent.VK_BACK_SPACE:
            case KeyEvent.VK_TAB:
            case KeyEvent.VK_ESCAPE:
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_INSERT:
            case KeyEvent.VK_HOME:
            case KeyEvent.VK_END:
            case KeyEvent.VK_PAGE_UP:
            case KeyEvent.VK_PAGE_DOWN:
            case KeyEvent.VK_UP:
            case KeyEvent.VK_DOWN:
            case KeyEvent.VK_LEFT:
            case KeyEvent.VK_RIGHT:
            case KeyEvent.VK_CAPS_LOCK:
            case KeyEvent.VK_NUM_LOCK:
            case KeyEvent.VK_SCROLL_LOCK:
            case KeyEvent.VK_PRINTSCREEN:
            case KeyEvent.VK_PAUSE:
            case KeyEvent.VK_F1:
            case KeyEvent.VK_F2:
            case KeyEvent.VK_F3:
            case KeyEvent.VK_F4:
            case KeyEvent.VK_F5:
            case KeyEvent.VK_F6:
            case KeyEvent.VK_F7:
            case KeyEvent.VK_F8:
            case KeyEvent.VK_F9:
            case KeyEvent.VK_F10:
            case KeyEvent.VK_F11:
            case KeyEvent.VK_F12:
                return true;
            default:
                return false;
        }
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
