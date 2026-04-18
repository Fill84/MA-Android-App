# MediaSession & Artwork — Clean-Slate Rewrite (Design)

**Date:** 2026-04-18
**Status:** Approved, ready for plan
**Scope:** Full rewrite of MediaSession metadata, artwork pipeline, and playlist (prev/current/next) handling. Sendspin audio, MA WebSocket, server connection, and command API-calls are untouched.

## Problem statement

Three prior fixes have been applied to the existing `NativeMediaManager` / `MusicService` artwork code but issues persist:

1. **App-icon shown instead of album cover** — reported regularly, especially for radio/stream content. In-car (Bluetooth AVRCP + Android Auto) often shows no artwork at all.
2. **Prev/next buttons broken outside the phone** — they work in the app's own notification, but in Bluetooth AVRCP and Android Auto the wrong metadata is shown and/or skips don't behave correctly.

Root causes identified during code exploration:
- Two parallel artwork caches (in `MusicService` and embedded in Media3 metadata) that can diverge.
- Fallback app-icon injection happens inside `SimpleBasePlayer.getState()` → every state read re-injects if bytes are momentarily missing.
- No retry/timeout on artwork downloads — transient failures permanently lose artwork for that track.
- Sendspin metadata (radio/streams) never contains artwork bytes, and the merge path with queue-API data is fragile.
- Two overlapping prev/next routing paths: Media3 seek-commands + AVRCP playlist-item selection via `onAddMediaItems`.
- Metadata preservation logic keys on URL but doesn't verify bytes actually exist → can "preserve" null bytes.

## Goals

- Album cover is reliably shown in notification, Bluetooth AVRCP (car/speaker), and Android Auto.
- App-icon fallback works reliably when and only when no real cover is available.
- Prev/next commands in Bluetooth AVRCP and Android Auto update metadata correctly (no stale/duplicate display).
- Code is organized into small, well-bounded, testable components.

## Non-goals

- MA server-side queue handling (untouched).
- Library browse-tree structure in Android Auto (untouched; only artwork pre-fetch added).
- Sendspin audio streaming, reconnect logic, or MA WebSocket client (untouched).
- Pre-fetching artwork for tracks beyond the prev/current/next window.
- Retry state persisted across app restarts.

## Architecture

All new code lives under `app/src/main/java/io/musicassistant/companion/media/`.

| Component | Responsibility | Dependencies |
|---|---|---|
| `ArtworkPipeline` | URL → downloaded & normalized JPEG bytes (300×300). Retry + backoff + timeout + LRU cache. Single source of truth. | OkHttp, `BitmapFactory` |
| `ArtworkFallback` | One-time at init: app-icon → JPEG bytes. Used only when pipeline cannot produce bytes. | `Context`, app-icon drawable |
| `TrackMetadata` (data class) | Immutable snapshot: `title`, `artist`, `album`, `artworkUrl`, `artworkBytes` (nullable until pipeline delivers). | — |
| `QueueSnapshot` (data class) | `prev: TrackMetadata?`, `current: TrackMetadata`, `next: TrackMetadata?`. Atomic. | — |
| `MediaMetadataCoordinator` | Publishes a `QueueSnapshot` `StateFlow`. Merges input from MA queue API, Sendspin metadata, and artwork pipeline. Emits atomic updates only — never partial. Applies fallback hierarchy at commit. | `ArtworkPipeline`, `ArtworkFallback` |
| `MaPlayer` (extends `SimpleBasePlayer`) | Thin Media3 adapter. Reads `QueueSnapshot` → builds 3-item playlist. Routes `handleSeekToPreviousMediaItem` / `handleSeekToNextMediaItem` / `handleSeekToMediaItem` to callbacks. | `MediaMetadataCoordinator` |
| `MediaSessionHost` | Owns `MediaSession` + custom `BitmapLoader`. Wires `MaPlayer` to the session. Session lifecycle. | `MaPlayer` |
| `MusicService` (slimmed) | Foreground service. Orchestration only: MA events → Coordinator; commands → Coordinator callbacks → MA API. Renders notification from Coordinator flow. | `MediaSessionHost`, `MediaMetadataCoordinator` |

Core principles:
1. **Single artwork source** — the Coordinator; no duplicated cache elsewhere.
2. **Atomic metadata** — always a complete snapshot, never partial updates.
3. **Fallback exactly once** — at commit time in Coordinator; never in `getState()`.
4. **SimpleBasePlayer stays** — correct Media3 pattern for a remote-control player (Sendspin does the audio).

## Artwork data flow

```
MA server URL ──► ArtworkPipeline.fetch(url)
                  ├─ cache hit  → bytes (sync)
                  └─ miss       → download (async)
                                   ├─ success → decode → 300×300 JPEG → cache → bytes
                                   ├─ timeout (5s) → retry (exp. backoff 1s, 2s, 4s; max 3)
                                   └─ final failure → null

Coordinator receives bytes-or-null:
  bytes == null? → apply fallback hierarchy (see below)
  ↓
QueueSnapshot emit (atomic)
  ↓
MaPlayer (Media3 state) + Notification renderer
  ↓
MediaSession metadata
  ↓
Custom BitmapLoader → METADATA_KEY_ALBUM_ART bitmap
  ↓
Bluetooth AVRCP / Android Auto / SystemUI
```

Rules:
- **`artworkData` xor `artworkUri`, never both.** Bytes always win. URI only set if we deliberately want remote-art fetching (not any current use case).
- **Radio/Sendspin path**: Sendspin delivers no art → Coordinator preserves last-known bytes for the same track identity, else calls MA queue API for the current queue-item's `media.image ?? currentItem.image`. If that also yields nothing → fallback.
- **Cache**: `LruCache<String, ByteArray>` in `ArtworkPipeline`, keyed by normalized URL. Max ~10 entries (prev/current/next + buffer). Bytes stored already as 300×300 JPEG; not re-encoded.
- **Download trigger**: Coordinator only requests pipeline fetch when URL changes. Same URL → reuse cached bytes directly in new snapshot.
- **Race protection**: each fetch gets a sequence token. If an older download completes after the user has skipped tracks → Coordinator ignores the result (token mismatch).
- **No artwork injection in `SimpleBasePlayer.getState()`** — the player reads only what the Coordinator has committed. State reads are pure.

## Fallback hierarchy (Coordinator, at commit)

```
1. Fresh bytes from pipeline for current URL          → use
2. Cache hit for current URL                          → use
3. Cache hit for last-known URL for this track        → use (track-identity preserve)
4. App-icon JPEG (ArtworkFallback)                    → use
5. null                                               → NEVER. Step 4 always succeeds.
```

Step 3 matters for radio: Sendspin updates can arrive before queue-API has answered. If we already had bytes for this track, keep using them until we provably have new ones.

### Track identity

Used for cache lookups and Sendspin/queue merge:

```
trackId = "${artist}::${title}"   (lowercase, trimmed)
```

Not album (radio often has varying/missing album). Not URL (can change while metadata stays the same).

## Prev/next flow

One timeline-based routing, replacing two overlapping paths.

**3-item playlist**:
- index 0 = prev (or null-placeholder)
- index 1 = current (always)
- index 2 = next (or null-placeholder)
- `setCurrentMediaItemIndex(1)` always

**Media3 declares the commands automatically** based on timeline + available-commands:
- `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` — available when `prev != null`
- `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` — available when `next != null`
- `COMMAND_SEEK_TO_MEDIA_ITEM` — always available (AVRCP playlist-selection path)

**Single handler** in `MaPlayer`:

```kotlin
handleSeekToMediaItem(mediaItemIndex) {
  when (mediaItemIndex) {
    0 -> onPreviousRequested()
    2 -> onNextRequested()
    1 -> no-op   // current track, nothing to do
  }
  // no state mutation — wait for MA server echo
}
```

`handleSeekToPreviousMediaItem` and `handleSeekToNextMediaItem` delegate internally to `handleSeekToMediaItem(0)` / `handleSeekToMediaItem(2)`. One path, no duplicated implementation.

**`AutoBrowseCallback.onAddMediaItems` for prev/next is removed** — Media3 handles this via the playlist on `MediaSession` now. Browse-tree functionality (search, library navigation) remains.

### Metadata consistency

The "wrong info" bug is caused by optimistic metadata mutation. New rule: prev/current/next metadata **is always** the 3-slot snapshot from the Coordinator, and only changes when the MA server sends an actual queue-update. No optimistic updates.

Concretely:
- Click "next" → MA API call → server responds → Coordinator rotates snapshot → MediaSession metadata changes atomically.
- During the round-trip (hundreds of ms), BT/Auto see what they already saw. Better than flashing wrong metadata.

### Command echo guard

Existing 3-second window remains as a safety net against duplicate commands (BT sometimes emits repeat-events). Per-command, not global:
- `lastNextAt`, `lastPreviousAt` — 1 s debounce per button.
- Play/pause keeps its own echo window.

### Browse items in AutoBrowseCallback (Android Auto)

- **Top-level items** (~20): bytes pre-fetched via `ArtworkPipeline` on browse-request. Covers appear immediately.
- **Deeper levels**: bytes embedded during `onLoadChildren`, page-by-page, max 20 per request.
- **Fallback**: if fetch isn't done yet → `artworkUri` on item (Android Auto's own loader takes over). For BT AVRCP 1.5 without BIP this means no cover on browse items — unavoidable without pre-fetching everything.

## Error handling

**ArtworkPipeline**:
- Timeout 5 s per attempt.
- Max 3 total attempts. Wait 1 s between attempt 1 and 2, wait 2 s between attempt 2 and 3. Give up after attempt 3.
- HTTP 4xx (e.g. 404): no retry; return null.
- HTTP 5xx / timeout / IO: retry.
- Bitmap decode failure: no retry (corrupt data); null.
- Log at WARN on failure (expected), not ERROR.

**Coordinator**:
- Pipeline returns null → apply fallback hierarchy.
- MA API queue-detail call fails → keep previous snapshot; log WARN.
- Sendspin metadata arrives while queue-API is pending → merge on track identity (title+artist match); otherwise ignore.

**MaPlayer**:
- No error handling. State reads are pure. Coordinator guarantees a valid snapshot.

**MediaSessionHost**:
- BitmapLoader fails bytes-decode → log ERROR, return empty bitmap. Should never happen (pipeline produces validated JPEG) — alarm bell.

**Command routing**:
- `playerNext` / `playerPrevious` API call fails → log WARN. No UI feedback (we are a remote control; user sees track not changing).
- Connection down → existing MA-client reconnect logic handles it.

## Logging strategy

One tag per new class:
- `ArtworkPipeline`: download start/success/fail with URL + attempt count.
- `Coordinator`: every snapshot emit with track-ids (+ whether fallback was used).
- `MaPlayer`: command calls (next/prev/seek) with resulting index.
- `MediaSessionHost`: session-state transitions.

Makes "why app-icon" trivial to debug: Coordinator log shows which fallback step triggered.

## Scope boundaries

| Area | Touched? |
|---|---|
| MediaSession prev/current/next window | ✅ fully rebuilt |
| Now-playing metadata (title/artist/art) | ✅ via Coordinator |
| MA player queue (full upcoming list) | ❌ untouched |
| Library browse-tree (structure) | ❌ untouched |
| Library browse-tree (artwork) | ✅ pre-fetch via pipeline |
| Sendspin audio streaming | ❌ untouched |
| MA WebSocket / reconnect | ❌ untouched |
| Commands (play/pause/next/prev API calls) | ⚠️ routing restructured, API calls identical |

## Migration

### Delete

- `app/.../media/ArtworkProvider.kt` (currently uncommitted — superseded)
- `app/.../media/ArtworkConverter.kt` (logic absorbed into `ArtworkPipeline`)
- `app/.../media/NativeMediaManager.kt` (replaced by `MaPlayer` + `MediaSessionHost`)

### New

- `app/.../media/ArtworkPipeline.kt`
- `app/.../media/ArtworkFallback.kt`
- `app/.../media/TrackMetadata.kt` (data class)
- `app/.../media/QueueSnapshot.kt` (data class)
- `app/.../media/MediaMetadataCoordinator.kt`
- `app/.../media/MaPlayer.kt`
- `app/.../media/MediaSessionHost.kt`

### Change

- `app/.../service/MusicService.kt`: remove artwork cache + metadata-build logic; wire Coordinator + MediaSessionHost. Sendspin events → Coordinator. Commands → Coordinator callbacks → MA API. Notification renders from Coordinator flow.
- `app/.../auto/AutoBrowseCallback.kt`: remove `onAddMediaItems` prev/next handling (Media3 handles it). Browse tree stays; artwork pre-fetch added for top-level items via `ArtworkPipeline`.

## Implementation order (dependencies first)

1. `TrackMetadata` + `QueueSnapshot` data classes
2. `ArtworkFallback` (app-icon → JPEG bytes, once at init)
3. `ArtworkPipeline` (standalone, unit-testable)
4. `MediaMetadataCoordinator` (uses pipeline + fallback)
5. `MaPlayer` (consumes Coordinator flow)
6. `MediaSessionHost` (hosts `MaPlayer` + `BitmapLoader`)
7. Slim down `MusicService`, wire up new components
8. Adjust `AutoBrowseCallback`
9. Delete old files
10. Build + manual test (device + BT speaker + Android Auto)

## Acceptance criteria

- Track with cover → cover visible in notification, BT speaker, Android Auto.
- Track without cover → app-icon visible on all three, never blank.
- Radio/stream (Sendspin) → queue-API art used; fallback works when absent.
- Cover download fails once, succeeds on retry → no app-icon flash.
- Next/prev in BT car → correct new track metadata (not same as current).
- Next/prev in Android Auto → same.
- Rapid next × 5 → final state matches server's final track; no stuck intermediate state; no crash.
- No artwork races (older download does not overwrite newer track's art).

## Risks / implementation notes

- **BitmapLoader contract**: Media3 expects `ListenableFuture<Bitmap>`. Pipeline delivers bytes → loader decodes bytes → Bitmap. Verify threading.
- **SimpleBasePlayer state-invalidation**: every `postInvalidate()` triggers AVRCP refresh. Coordinator emits must be coalesced — not 5 emits for 1 logical update.
- **Service lifecycle**: Coordinator and Pipeline constructed in `onCreate`, cleaned in `onDestroy`. All coroutines scoped to service lifetime.
