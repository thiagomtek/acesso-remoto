package com.transacao.agent;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.util.List;

/**
 * Captura de tela compartilhada por todas as sessoes: so captura enquanto ha alguem assistindo
 * (contagem de referencias), e usa a captura nativa do WebRTC na resolucao FISICA do monitor
 * (nao a reduzida pela escala do Windows).
 */
final class ScreenSource {

    private final PeerConnectionFactory factory;
    private VideoDesktopSource source;
    private VideoTrack track;
    private int users;

    ScreenSource(PeerConnectionFactory factory) {
        this.factory = factory;
    }

    synchronized VideoTrack acquire(int fps) throws Exception {
        if (users++ == 0) {
            long id = primaryScreenId();
            source = new VideoDesktopSource();
            source.setSourceId(id, false);
            source.setFrameRate(fps);
            int[] phys = ScreenGeometry.physicalSize();
            source.setMaxFrameSize(phys[0], phys[1]);
            track = factory.createVideoTrack("screen", source);
            source.start();
        }
        return track;
    }

    synchronized void release() {
        if (users > 0 && --users == 0) {
            try {
                source.stop();
                track.dispose();
                source.dispose();
            } finally {
                source = null;
                track = null;
            }
        }
    }

    private static long primaryScreenId() {
        ScreenCapturer capturer = new ScreenCapturer();
        try {
            List<DesktopSource> sources = capturer.getDesktopSources();
            return sources.isEmpty() ? 0 : sources.get(0).id;
        } finally {
            capturer.dispose();
        }
    }
}
