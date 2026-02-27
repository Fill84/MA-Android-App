package io.musicassistant.companion.media

/**
 * JavaScript injected into the WebView after page load.
 *
 * This script:
 * 1. Wraps navigator.mediaSession.setActionHandler to capture handlers
 * 2. Wraps navigator.mediaSession.setPositionState to capture position/duration
 * 3. Polls navigator.mediaSession.metadata and playbackState every 2 seconds
 * 4. Falls back to polling audio element for position/duration data
 * 5. Forwards changes to the Android bridge via __MA_ANDROID__
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

    // Wrap navigator.mediaSession.setPositionState to capture position/duration
    var lastPosDur = '', lastPosPos = '', lastPosRate = '';
    try {
        var origSetPosition = navigator.mediaSession.setPositionState.bind(navigator.mediaSession);
        navigator.mediaSession.setPositionState = function(state) {
            if (state) {
                var dur = state.duration || 0;
                var pos = state.position || 0;
                var rate = state.playbackRate || 1;
                var durKey = '' + Math.round(dur * 10);
                var posKey = '' + Math.round(pos);
                var rateKey = '' + Math.round(rate * 100);
                if (durKey !== lastPosDur || posKey !== lastPosPos || rateKey !== lastPosRate) {
                    lastPosDur = durKey;
                    lastPosPos = posKey;
                    lastPosRate = rateKey;
                    window.__MA_ANDROID__.onPositionStateChanged(dur, pos, rate);
                }
            }
            origSetPosition(state);
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

            // Fallback: poll audio element for position if setPositionState wasn't called
            var audio = document.querySelector('audio');
            if (audio && audio.duration && isFinite(audio.duration)) {
                var dur = audio.duration;
                var pos = audio.currentTime || 0;
                var rate = audio.playbackRate || 1;
                var durKey = '' + Math.round(dur * 10);
                var posKey = '' + Math.round(pos);
                var rateKey = '' + Math.round(rate * 100);
                if (durKey !== lastPosDur || posKey !== lastPosPos || rateKey !== lastPosRate) {
                    lastPosDur = durKey;
                    lastPosPos = posKey;
                    lastPosRate = rateKey;
                    window.__MA_ANDROID__.onPositionStateChanged(dur, pos, rate);
                }
            }
        } catch(e) {}
    }, 2000);
})();
"""
