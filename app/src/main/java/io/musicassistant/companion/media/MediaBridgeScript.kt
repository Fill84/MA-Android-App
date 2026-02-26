package io.musicassistant.companion.media

/**
 * JavaScript injected into the WebView after page load.
 *
 * This script:
 * 1. Wraps navigator.mediaSession.setActionHandler to capture handlers
 * 2. Polls navigator.mediaSession.metadata and playbackState every 2 seconds
 * 3. Forwards changes to the Android bridge via __MA_ANDROID__
 *
 * This bridges the Sendspin web player's media state to the Android MediaSession.
 */
const val MEDIA_BRIDGE_SCRIPT = """
(function() {
    if (window.__ma_bridge_initialized) return;
    window.__ma_bridge_initialized = true;
    window.__ma_handlers = {};

    // Wrap navigator.mediaSession.setActionHandler to capture handlers
    try {
        var origSetAction = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
        navigator.mediaSession.setActionHandler = function(action, handler) {
            window.__ma_handlers[action] = handler;
            origSetAction(action, handler);
        };
    } catch(e) {}

    // Poll metadata + playbackState every 2 seconds
    var lastState = '', lastTitle = '', lastArtist = '', lastAlbum = '', lastArt = '';
    setInterval(function() {
        try {
            var state = navigator.mediaSession.playbackState || 'none';
            if (state !== lastState) {
                lastState = state;
                window.__MA_ANDROID__.onPlaybackStateChanged(state);
            }

            var meta = navigator.mediaSession.metadata;
            if (!meta) return;

            var title = meta.title || '';
            var artist = meta.artist || '';
            var album = meta.album || '';
            var art = '';
            if (meta.artwork && meta.artwork.length > 0) {
                var rawArt = meta.artwork[meta.artwork.length - 1].src;
                if (rawArt) {
                    if (rawArt.startsWith('data:') || rawArt.startsWith('blob:')) {
                        art = rawArt;
                    } else {
                        try {
                            art = new URL(rawArt, window.location.origin).href;
                        } catch(e) {
                            art = rawArt;
                        }
                    }
                }
            }

            if (title !== lastTitle || artist !== lastArtist || album !== lastAlbum || art !== lastArt) {
                lastTitle = title;
                lastArtist = artist;
                lastAlbum = album;
                lastArt = art;
                window.__MA_ANDROID__.onMetadataChanged(title, artist, album, art);
            }
        } catch(e) {}
    }, 2000);
})();
"""
