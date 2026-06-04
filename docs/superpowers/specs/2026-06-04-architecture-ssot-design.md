# Architectuur-SSOT — Eén server-mirror voor player-state / now-playing / queue

**Datum:** 2026-06-04
**Branch:** `fix/queue-remove-clear`
**Status:** Ontwerp goedgekeurd; klaar voor implementatieplan.

## Doel

De Music Assistant-server is de enige Single Source Of Truth. De app mag serverstaat alleen
**spiegelen/cachen** (plus verbindings-/apparaat-identiteit), nooit concurrerende eigen kopieën
houden. Vandaag leiden twee plekken dezelfde serverstaat onafhankelijk af, met onderling
afwijkende logica:

| Domein | Server = bron | Parallelle kopie in app |
|---|---|---|
| Now-playing | speler/queue | `MediaMetadataCoordinator`-voeding in `MusicService` **én** `PlayerViewModel` |
| Play-state | `queue.state`/`player.state` | `MusicService.playingState` + `MaPlayer.streamPlaying` + `PlayerViewModel._isPlaying` |
| Queue | server-queue | `PlayerViewModel._queue/_queueItems` + `MusicService.currentQueueItemId/previousTrack*` |

Deze duplicatie was de structurele oorzaak van o.a. de recent opgeloste cover-art- en
mini-player-bugs. Dit ontwerp introduceert één centrale mirror met pure afleidingen, geconsumeerd
door zowel de media-sessie-stack als de UI.

## Vastgestelde beslissingen (met gebruiker)

1. **Speler-scope:** de media-sessie (notificatie/Bluetooth/auto) blijft **altijd** gebonden aan
   déze telefoon (de device-speler, `settings.playerId`). De UI mag los "rondkijken" naar andere
   spelers. → De store cachet meerdere spelers en biedt **twee projecties**: een vaste
   device-projectie en een kiesbare selectie-projectie. **Geen waarneembare gedragsverandering.**
2. **Aanpak C — lazy, ref-counted per-id flows.** De store biedt `session(id)`-flows die alleen
   leven zolang er een waarnemer is (plus een korte grace). Géén eager map-cache.
3. **Incrementeel per domein**, elk apart on-device verifieerbaar en terug te draaien.
4. **Gedrag exact behouden** — puur structureel. De net-gefixte cover-art/mini-player/radio-
   gedragingen blijven identiek.

## Architectuur

Nieuwe laag onder `data/`, de enige plek die op serverstaat-events reageert en speler/queue ophaalt:

```
data/player/
  PlayerRepository.kt      — lazy, ref-counted per-id sessie-flows (Aanpak C); de mirror
  PlayerSession.kt         — unified model: afgeleide staat voor één speler
  NowPlayingDerivation.kt  — pure functies (geen Android): queue/player → NowPlaying, isPlaying
media/QueueNeighbors.kt    — bestaat al (resolveNeighbors); blijft de pure prev/next-regel
```

- `PlayerRepository` wordt een **ServiceLocator-singleton**, deelt `api`/`apiClient`, draait op een
  eigen `CoroutineScope`.
- `MediaMetadataCoordinator`, `MaPlayer`, `MediaSessionHost`, `ArtworkPipeline` blijven intern
  **ongewijzigd**. Alleen hun input gaat voortaan via de store.

### Store-API

```kotlin
class PlayerRepository(api, apiClient, scope) {
    fun session(playerId: String): SharedFlow<PlayerSession>   // device + selectie gebruiken dit
    val players: StateFlow<List<Player>>                       // spelerslijst-view (settings/picker)
}
```

**Per-id sessie-flow** (`buildSessionFlow(id)`, `shareIn(scope, WhileSubscribed(5_000), replay = 1)`):

1. Bij start én bij elke `AUTHENTICATED`-transitie: `getPlayer(id)` → bepaal `effectiveQueueId`
   (`player.activeSource ?: player.playerId`) → `getPlayerQueue(qid)` + `getPlayerQueueItems(qid)`
   → emit afgeleide `PlayerSession`.
2. Merge `apiClient.events` gefilterd op id/queue-id:
   - `player_updated(id)` → speler bijwerken; active_source kan wijzigen → queue-id herbepalen +
     queue herladen.
   - `queue_updated(qid)` → queue bijwerken.
   - `queue_items_updated(qid)` → items herladen.
   - Behoud de bestaande optimalisatie: parse uit `event.data` (`tryParsePlayer/PlayerQueue`),
     fallback naar een fetch. Geen extra server-calls introduceren.
3. Elke wijziging → `NowPlayingDerivation` → nieuwe `PlayerSession`.

**Lifecycle / risico-afdekking (zwakke plekken van Aanpak C):**

- **Device-sessie altijd warm:** `MusicService` houdt voor z'n hele leven één collect op
  `session(deviceId)` open → `WhileSubscribed` houdt 'm levend, exact zoals nu (MusicService draait
  continu).
- **Roam-teardown zonder flikkering:** `WhileSubscribed(5_000)` houdt een geroamde speler nog 5s
  warm nadat de UI wegkijkt; `replay = 1` geeft directe laatste waarde bij herabonneren. Terug naar
  de device-speler = de altijd-warme flow → instant.
- **Geen dubbele fetches:** `computeIfAbsent` in een `ConcurrentHashMap<id, SharedFlow>` → één
  gedeelde flow per id, ook als device- en selectie-projectie hetzelfde id kiezen.

**Bewust NIET in de store (device-only — geen duplicatie):** de Sendspin-stream-metadata-overlay,
de radio-stationslogo-fetch en de prev/next-`TrackMetadata` voor AVRCP. Die blijven device-zijdig,
maar gaan voortaan bovenop de store-output i.p.v. een eigen hand-gerolde afleiding. De UI gebruikt
geen prev/next maar de volledige queue-lijst — die asymmetrie is echt en hoort buiten de gedeelde
kern.

## Pure afleidingslaag

```kotlin
data class PlayerSession(
    val playerId: String,
    val effectiveQueueId: String,
    val player: Player?,
    val queue: PlayerQueue?,
    val queueItems: List<QueueItem>,
    val nowPlaying: NowPlaying?,
    val isPlaying: Boolean,
)

data class NowPlaying(
    val title: String, val artist: String, val album: String?,
    val artworkUrl: String?,        // nog niet device-bereikbaar gemaakt
    val isLive: Boolean,
    val durationMs: Long, val elapsedMs: Long, val elapsedAtMs: Long,
    val currentIndex: Int?, val currentQueueItemId: String?,
)
```

**Pure functies** (geen Android, geen netwerk; krijgen al-opgehaalde data):

- `deriveNowPlaying(player, queue): NowPlaying?` — bevat exact de huidige radio-vs-track-keuze:
  - radio: title/artist uit `player.currentMedia`, fallback `currentItem.name`/`media.name`;
    artwork `currentMedia.imageUrl ?: media.image ?: currentItem.image`.
  - track: `media.name` + artists; artwork `media.image ?: currentItem.image`.
  - `isLive = isRadio || currentItem.duration <= 0`; duur 0 bij live.
- `deriveIsPlaying(queue, player): Boolean` — uit `queue.state`/`player.state` (de regel die nu 3×
  verspreid staat).

**Artwork blijft een URL-string in `NowPlaying`.** Het device-bereikbaar maken
(`resolveArtworkUrl`/`getImageUrl`: base-URL + imageproxy-omleiding) en downloaden naar bytes blijft
bij de consument (UI: Coil; sessie: `ArtworkPipeline`). De store kent geen base-URL/token — dat is
verbindings-identiteit, geen serverstaat. Zo blijft de laag puur en JVM-testbaar.

## Migratie van de consumenten

### Device-projectie → `MusicService`

- Vervangt z'n eigen event-observers (`handleQueueUpdated`/`handlePlayerUpdated`/
  `updateMetadataFromQueue`/`refreshMetadataFromServer`/`pushTrackFromSource`) door één collect op
  `repo.session(deviceId)`.
- Per emit: `session.nowPlaying` → `TrackMetadata(current)`; prev/next via bestaande
  `resolveNeighbors` uit `session.queueItems` + `currentIndex`; dan `coordinator.pushQueueUpdate(...)`.
  De source-queue-buren-logica van `pushTrackFromSource` wordt hiermee "map session → neighbors"
  (de store levert de active_source-queue al, want `effectiveQueueId` = active_source).
- **Blijft device-zijdig (ongewijzigd):** `pushSendspinMetadata`, `resolveLiveContext`
  (stationslogo + live-context), command-echo-guard, `setLocalPlaying`.
- Play-state: `playingState`/`MaPlayer.streamPlaying` worden afgeleiden van `session.isPlaying`
  (gemerged met de Sendspin-`streamActive` — zie Risico's), niet langer losse eigen kopieën.

### Selectie-projectie → `PlayerViewModel`

- `_activePlayer`/`activeQueueId`/`_queue`/`_queueItems`/`_isPlaying`/`_currentTrack*`/`_isLive`/
  `effectiveQueueId` worden afgeleid uit `selectedId.flatMapLatest { repo.session(it) }`.
- `autoSelectPlayingPlayer` blijft (zet `selectedId`), maar leest uit `repo.players`.
- Transport-commando's blijven dunne `api.*`-calls; de optimistische `_isPlaying`-debounce blijft als
  UI-only laagje bovenop `session.isPlaying`.

De **gedeelde kern** (`deriveNowPlaying`/`deriveIsPlaying`/queue) is maximaal; alleen device-only
overlays (Sendspin/stationslogo/AVRCP-buren) en UI-only overlay (play-debounce) blijven dunne
laagjes erbovenop. Daarmee verdwijnt de duplicatie.

## Incrementele uitrol

Elke stap: TDD waar zinvol, alle bestaande unittests groen, `assembleDebug` OK, dan on-device.

- **Stap 0 — Store-skelet.** `PlayerRepository` + `PlayerSession` + `NowPlayingDerivation` +
  ServiceLocator-bedrading. Pure-unittests op de afleidingen (radio/track/live/neighbors/isPlaying) +
  flow-test voor `session()` (fake api/events: emit → sessie; reconnect → re-fetch; teardown). Geen
  productie-pad verandert.
- **Stap 1 — Play-state.** `_isPlaying`/`playingState`/`streamPlaying` worden afgeleiden van
  `session.isPlaying` (+ overlays). On-device: play/pause vanuit app/notificatie/BT → één
  consistente staat, geen flikkering.
- **Stap 2 — Queue.** `_queue/_queueItems` en `currentQueueItemId/previousTrack*` uit de store.
  On-device: queue openen, verwijderen/clear, volgende track → lijst + currentIndex kloppen.
- **Stap 3 — Now-playing (grootste risico).** `updateMetadataFromQueue` (beide) + `pushTrackFromSource`
  → `deriveNowPlaying` + map-naar-`TrackMetadata`/Coordinator. **Pas verifiëren ná de BMW cover-art-
  test**, zodat er een bewezen-goede referentie is. Car-vrij via `adb dumpsys media_session` + logcat
  `bytes=`, plus echte auto. Radio, groep/source-speler, prev/next, echte cover vs app-icoon natrekken.

## Risico's & randgevallen

- **`MaPlayer.streamPlaying` ≠ `session.isPlaying`.** `streamActive`/`streamPlaying` reflecteren de
  lokale Sendspin-audio (speelt deze telefoon geluid af), niet "wat de geselecteerde speler doet".
  Gescheiden houden: Media3-`playWhenReady` blijft op de Sendspin-audio-staat; de store voedt alleen
  now-playing/queue/logische play-state voor de notificatie-tekst.
- **Optimistische play-debounce (UI) & command-echo-guard (service) blijven** als dunne overlay vóór
  de store-waarde, zodat een net-ingedrukte knop niet kort terugflikkert door een trager server-event.
- **Reconnect / lege device-queue / active_source-wissel.** De `session()`-flow herbepaalt
  `effectiveQueueId` bij `player_updated` en herlaadt — nu op één plek i.p.v. in beide consumenten.
- **Inline event-data vs round-trip.** Behoud `tryParsePlayer/PlayerQueue` uit `event.data` met
  fallback-fetch in de store; geen extra server-calls.
- **Exact-gedrag-vangnet.** Per stap de bestaande logcat-signalen (`source nowplaying`, `bytes=`,
  `auto-select now-playing`, `select player`) vóór/na vergelijken; ze moeten identiek blijven.

## Buiten scope

- Settings-SSOT (codec/name/enabled) — al gedaan in een eerdere sessie.
- Wijzigingen aan de artwork-pipeline, AudioTrack-stack of Sendspin-protocol.
- Het multi-player-cachen tot een eager map promoveren (bewust Aanpak C).
