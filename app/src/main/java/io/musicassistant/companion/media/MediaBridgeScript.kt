package io.musicassistant.companion.media

/**
 * JavaScript that intercepts navigator.mediaSession to capture all metadata,
 * playback state, position, and action handlers from the Sendspin web player
 * and forward them to the native Android MediaSession via __MA_ANDROID__.
 *
 * Two strategies:
 * 1. INTERCEPTOR (primary): Replaces navigator.mediaSession with a custom object
 *    before Sendspin initializes. This captures everything immediately on set,
 *    prevents the WebView from creating a conflicting internal MediaSession,
 *    and requires no polling. Needs injection at document start.
 *
 * 2. FALLBACK (backup): Wraps methods on the existing navigator.mediaSession
 *    and polls metadata/playbackState every 2 seconds. Used when the interceptor
 *    cannot replace navigator.mediaSession (older WebViews or late injection).
 */
const val MEDIA_BRIDGE_SCRIPT = """
(function() {
    if (window.__ma_bridge_initialized) return;
    window.__ma_handlers = {};

    // --- Strategy 1: Replace navigator.mediaSession with interceptor ---
    try {
        var _metadata = null;
        var _playbackState = 'none';

        var interceptor = {
            setActionHandler: function(action, handler) {
                window.__ma_handlers[action] = handler;
            },
            setPositionState: function(state) {
                if (state && window.__MA_ANDROID__) {
                    window.__MA_ANDROID__.onPositionStateChanged(
                        state.duration || 0, state.position || 0, state.playbackRate || 1
                    );
                }
            },
            setCameraActive: function() {},
            setMicrophoneActive: function() {}
        };

        Object.defineProperty(interceptor, 'metadata', {
            get: function() { return _metadata; },
            set: function(val) {
                _metadata = val;
                if (val && window.__MA_ANDROID__) {
                    var art = '';
                    if (val.artwork && val.artwork.length > 0) {
                        var rawArt = val.artwork[val.artwork.length - 1].src;
                        if (rawArt) {
                            try { art = new URL(rawArt, window.location.origin).href; }
                            catch(e) { art = String(rawArt); }
                        }
                    }
                    window.__MA_ANDROID__.onMetadataChanged(
                        val.title || '', val.artist || '', val.album || '', art
                    );
                }
            }
        });

        Object.defineProperty(interceptor, 'playbackState', {
            get: function() { return _playbackState; },
            set: function(val) {
                _playbackState = val;
                if (window.__MA_ANDROID__) {
                    window.__MA_ANDROID__.onPlaybackStateChanged(val || 'none');
                }
            }
        });

        Object.defineProperty(navigator, 'mediaSession', {
            get: function() { return interceptor; },
            configurable: true
        });

        // Polyfill MediaMetadata — our early interception prevents the browser
        // from initializing this constructor as part of the Media Session API.
        if (typeof MediaMetadata === 'undefined') {
            window.MediaMetadata = function MediaMetadata(init) {
                this.title = (init && init.title) || '';
                this.artist = (init && init.artist) || '';
                this.album = (init && init.album) || '';
                this.artwork = (init && init.artwork) || [];
            };
        }

        // Connection watchdog: detect stale connections and reload if needed.
        // Tracks last bridge activity; if playback was active but no updates
        // arrive for 60 seconds, the WebSocket likely died — reload to reconnect.
        var _lastActivity = Date.now();
        var _wasPlaying = false;
        window.__ma_mark_activity = function() { _lastActivity = Date.now(); };

        // Patch interceptor callbacks to track activity
        var _origMetaSetter = Object.getOwnPropertyDescriptor(interceptor, 'metadata').set;
        Object.defineProperty(interceptor, 'metadata', {
            get: function() { return _metadata; },
            set: function(val) { _lastActivity = Date.now(); _origMetaSetter(val); }
        });
        var _origStateSetter = Object.getOwnPropertyDescriptor(interceptor, 'playbackState').set;
        Object.defineProperty(interceptor, 'playbackState', {
            get: function() { return _playbackState; },
            set: function(val) {
                _lastActivity = Date.now();
                if (val === 'playing') _wasPlaying = true;
                else if (val === 'none') _wasPlaying = false;
                _origStateSetter(val);
            }
        });
        var _origSetPos = interceptor.setPositionState;
        interceptor.setPositionState = function(state) {
            _lastActivity = Date.now();
            _origSetPos(state);
        };

        setInterval(function() {
            var silentSec = (Date.now() - _lastActivity) / 1000;
            if (_wasPlaying && silentSec > 60) {
                _wasPlaying = false;
                window.location.reload();
            }
        }, 30000);

        window.__ma_bridge_initialized = true;
        return; // Interceptor succeeded, no fallback needed
    } catch(e) {}

    // --- Strategy 2: Wrap methods + poll (fallback for late injection) ---
    try {
        var origSetAction = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
        navigator.mediaSession.setActionHandler = function(action, handler) {
            window.__ma_handlers[action] = handler;
            origSetAction(action, handler);
        };
    } catch(e) {}

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
                        try { art = new URL(rawArt, window.location.origin).href; }
                        catch(e) { art = rawArt; }
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

    window.__ma_bridge_initialized = true;
})();
"""
