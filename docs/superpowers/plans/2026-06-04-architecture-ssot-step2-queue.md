# Architectuur-SSOT — Stap 2: Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Laat `PlayerViewModel._queue` / `_queueItems` (en de actieve speler + now-playing-trigger) uit de centrale `PlayerRepository.session(...)` komen, en sloop de eigen queue-fetching/event-afhandeling van de ViewModel — zonder waarneembare UI-verandering.

**Architecture:** Voortbouwend op Stap 0+1. De `observeSelectedSession()`-collector (in Stap 1 toegevoegd) wordt de enige plek die `_activePlayer`/`_queue`/`_queueItems` voedt en de UI-metadata triggert, atomair uit één `PlayerSession`-emit. De ViewModel stopt met zelf `getPlayerQueue`/`getPlayerQueueItems` aanroepen en met het afhandelen van `queue_updated`/`queue_items_updated`-events (de store doet dat nu). De now-playing-*afleiding* zelf (`updateMetadataFromQueue`) blijft ongewijzigd — die wordt pas in Stap 3 vervangen door `session.nowPlaying`.

**Tech Stack:** Kotlin, Coroutines/Flow, JUnit4 + MockK + kotlinx-coroutines-test + Turbine.

**Scope-grens:** Alleen de **UI-zijde** (`PlayerViewModel`). De device-zijde (`MusicService.currentQueueItemId`/`previousTrack*`) blijft ongemoeid — die migreert in **Stap 3 (now-playing)**, ná de BMW cover-art-test. Zie `docs/superpowers/specs/2026-06-04-architecture-ssot-design.md`.

**Globale regel:** géén self-references in commits. **Commit-grens:** niet committen tot de gebruiker de BMW-grens opheft — sla elke commit-stap over, laat alles uncommitted.

---

## Bestandsindeling

- Modify: `app/src/main/java/io/musicassistant/companion/ui/player/PlayerViewModel.kt` — queue/actieve speler/now-playing-trigger uit de sessie; verwijder eigen queue-plumbing.
- Modify test: `app/src/test/java/io/musicassistant/companion/ui/player/PlayerViewModelTest.kt` — `seedQueue` via de sessie-flow; +2 queue-tests.

**Publieke API blijft identiek:** `PlayerViewModel.queue: StateFlow<PlayerQueue?>` en `queueItems: StateFlow<List<QueueItem>>` veranderen niet van type/expose. `QueueScreen.kt`/`NowPlayingScreen.kt`/`DetailScreens.kt` worden NIET aangeraakt.

---

## Task 1: Queue + actieve speler + now-playing-trigger uit de sessie

**Files:**
- Modify: `app/src/main/java/io/musicassistant/companion/ui/player/PlayerViewModel.kt`
- Test: `app/src/test/java/io/musicassistant/companion/ui/player/PlayerViewModelTest.kt`

- [ ] **Step 1: Schrijf de falende tests**

Pas eerst de `seedQueue`-helper aan zodat hij de queue via de sessie-flow seedt (de oude `loadQueue`-weg verdwijnt in deze taak). Vervang de bestaande `seedQueue` in `PlayerViewModelTest` door:

```kotlin
/** Seeds `_queue` with the given id by emitting a session for the selected player. */
private suspend fun TestScope.seedQueue(vm: PlayerViewModel, queueId: String) {
    vm.selectPlayer(queueId) // sets selectedId → subscribes to repo.session(queueId) == sessionFlow
    advanceUntilIdle()
    sessionFlow.emit(
        io.musicassistant.companion.data.player.PlayerSession
            .empty(queueId)
            .copy(queue = PlayerQueue(queueId = queueId))
    )
    advanceUntilIdle()
}
```

Voeg twee nieuwe tests toe:

```kotlin
@Test
fun `queue and items reflect the selected session`() = runTest(dispatcher) {
    val vm = PlayerViewModel(application)
    vm.selectPlayer("upma_q1")
    advanceUntilIdle()

    val items = listOf(
        io.musicassistant.companion.data.model.QueueItem(queueItemId = "a", name = "A"),
        io.musicassistant.companion.data.model.QueueItem(queueItemId = "b", name = "B"),
    )
    sessionFlow.emit(
        io.musicassistant.companion.data.player.PlayerSession
            .empty("upma_q1")
            .copy(queue = PlayerQueue(queueId = "upma_q1", currentIndex = 1), queueItems = items)
    )
    advanceUntilIdle()

    assertEquals("upma_q1", vm.queue.value?.queueId)
    assertEquals(1, vm.queue.value?.currentIndex)
    assertEquals(listOf("a", "b"), vm.queueItems.value.map { it.queueItemId })
}

@Test
fun `queue clears when the session has no queue`() = runTest(dispatcher) {
    val vm = PlayerViewModel(application)
    seedQueue(vm, "upma_q1")
    assertEquals("upma_q1", vm.queue.value?.queueId)

    sessionFlow.emit(
        io.musicassistant.companion.data.player.PlayerSession.empty("upma_q1") // queue = null
    )
    advanceUntilIdle()

    assertEquals(null, vm.queue.value)
    assertEquals(emptyList<io.musicassistant.companion.data.model.QueueItem>(), vm.queueItems.value)
}
```

- [ ] **Step 2: Draai de tests, verwacht falen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.ui.player.PlayerViewModelTest"`
Expected: FAIL — `_queue`/`_queueItems` worden nog niet door de sessie gevoed (de twee nieuwe tests falen; mogelijk falen ook de bestaande queue-mutatie-tests omdat `seedQueue` nu via de sessie seedt terwijl de productie-code dat nog niet leest).

- [ ] **Step 3: Migreer `PlayerViewModel.kt`**

**(a)** Vervang de body van `observeSelectedSession()` zodat de sessie `_activePlayer`/`_queue`/`_queueItems` voedt en de UI-metadata triggert:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
private fun observeSelectedSession() {
    selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repo.session(id) }
        .onEach { session: PlayerSession? ->
            if (session != null) {
                session.player?.let { _activePlayer.value = it }
                setIsPlayingFromServer(session.isPlaying)
                _queue.value = session.queue
                _queueItems.value = session.queueItems
                updateMetadataFromQueue()
            }
        }
        .launchIn(viewModelScope)
}
```

**(b)** Vereenvoudig `applyActivePlayer(player)` — geen eigen queue-load meer:

```kotlin
private fun applyActivePlayer(player: Player) {
    _activePlayer.value = player
    _isPlaying.value = player.state == PlayerState.PLAYING
    selectedId.value = player.playerId
}
```

**(c)** Vereenvoudig de else-tak van `selectPlayer(playerId)` (speler niet in lijst) — geen `loadQueue` meer:

```kotlin
fun selectPlayer(playerId: String) {
    val selected = _players.value.find { it.playerId == playerId }
    if (selected != null) {
        applyActivePlayer(selected)
    } else {
        _activePlayer.value = null
        selectedId.value = playerId
    }
}
```

**(d)** Verwijder de queue-event-afhandeling uit `observeEvents()` (de store doet dit nu). De `when` wordt:

```kotlin
private fun observeEvents() {
    apiClient
            .events
            .onEach { event ->
                when (event.event) {
                    "player_added", "player_updated" -> handlePlayerUpdated(event)
                }
            }
            .launchIn(viewModelScope)
}
```

**(e)** Strip `handlePlayerUpdated(event)` tot enkel het onderhouden van de **spelerslijst** `_players` (de actieve speler + queue komen nu uit de sessie). Vervang de hele methode door:

```kotlin
private fun handlePlayerUpdated(event: MaApiClient.MaEvent) {
    val changedId = event.objectId
    val isAdded = event.event == "player_added"
    viewModelScope.launch {
        try {
            if (changedId != null && !isAdded) {
                val updated = tryParsePlayer(event.data) ?: api.getPlayer(changedId) ?: return@launch
                _players.value = if (_players.value.any { it.playerId == changedId }) {
                    _players.value.map { if (it.playerId == changedId) updated else it }
                } else {
                    _players.value + updated
                }
            } else {
                // player_added or no objectId — full refresh to pick up new players.
                _players.value = api.getPlayers()
            }
            // Active player / queue / metadata are driven reactively by [observeSelectedSession];
            // [autoSelectPlayingPlayer] reacts to the list update when nothing is selected yet.
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh players: ${e.message}")
        }
    }
}
```

**(f)** Verwijder de nu-ongebruikte leden: de methoden `loadQueue`, `handleQueueUpdated`, `handleQueueItemsUpdated`, `effectiveQueueId`, `tryParsePlayerQueue`, en het veld `activeQueueId`. Verwijder ook de bijbehorende imports als ze nergens anders gebruikt worden (laat de compiler/`compileDebugKotlin` "unused"-waarschuwingen leiden; verwijder dode imports zoals `PlayerQueue` alleen als ze écht ongebruikt zijn — `PlayerQueue` blijft als type van `_queue`/`queue`).

> Let op: laat `updateMetadataFromQueue`, `updateElapsedTimeFromQueue`, `currentPositionMs`/`durationMs`, de `_currentTrack*`/`_isLive`/`_ui*`-velden en alle transport/mutatie-methoden (`play/pause/next/previous/seekTo/setVolume/toggleShuffle/toggleRepeat/playQueueIndex/deleteQueueItem/clearQueue/moveQueueItem/playMedia`) ONGEMOEID. Die zijn Stap-3- resp. command-laag en blijven werken: ze lezen `_queue.value` (nu sessie-gevoed) en `_activePlayer.value`.

- [ ] **Step 4: Draai de gerichte tests, verwacht slagen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.ui.player.PlayerViewModelTest"`
Expected: PASS — de 2 nieuwe queue-tests + alle bestaande (clearQueue/deleteQueueItem/auto-select/play-state) groen. (`seedQueue` seedt nu via de sessie; `clearQueue`/`deleteQueueItem` lezen `_queue.value.queueId` dat de sessie-emit heeft gezet.)

- [ ] **Step 5: Volledige suite + build**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (alles groen).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: On-device verificatie (Pixel 5, `08201FDD4003KF`)**

Installeer (`adb -s 08201FDD4003KF install -r app/build/outputs/apk/debug/app-debug.apk`) en controleer dat de queue-UI exact zoals vóór werkt:
- Speel een album op een speler → open de queue (lijst-icoon op het Now Playing-scherm) → de **queue-lijst en de huidige-track-markering (currentIndex)** kloppen.
- **Volgende track** → de queue-markering schuift mee (sessie-gedreven).
- **Track verwijderen** (×) → verdwijnt uit de lijst; **Clear queue** → lijst leeg + knop verdwijnt.
- **Shuffle/Repeat** togglen → de knop reageert direct (optimistisch) en blijft kloppen na de server-echo (let op eventuele korte flikker — zie Risico's).
- Wissel naar een andere speler en terug → de queue volgt de selectie.
- Geen crash; logcat `select player`/`auto-select now-playing` blijven verschijnen zoals voorheen.

Screenshot: `adb -s 08201FDD4003KF exec-out screencap -p > "C:/Users/philp/AppData/Local/Temp/ssot_queue.png"`

- [ ] **Step 7: Commit** — OVERSLAAN (commit-grens actief). Laat alles uncommitted.

---

## Risico's & aandachtspunten

- **Optimistische shuffle/repeat.** `toggleShuffle`/`toggleRepeat` zetten `_queue.value` optimistisch en reverten bij fout. Nu de sessie óók `_queue` voedt, kan een tussentijdse sessie-emit (bv. door een `player_updated` vlak ná de tap, maar vóór het `queue_updated`-antwoord) de optimistische waarde kort overschrijven → mogelijke korte flikker van de shuffle/repeat-knop. Dit is zeldzaam en strookt met het SSOT-principe (server is leidend). Controleer dit on-device (Step 6); als het storend blijkt, is de fix in een vervolgstap om de toggle-knoppen rechtstreeks op `session.queue.shuffleEnabled/repeatMode` te tonen (geen lokale optimistische kopie meer).
- **Volgorde-atomiciteit.** `_activePlayer`, `_queue`, `_queueItems` en de metadata-trigger komen nu uit dezelfde `PlayerSession`-emit → geen race meer tussen "speler bijgewerkt" en "queue herladen" (verbetering t.o.v. de oude losse paden; geen waarneembare gedragsverandering).
- **Frequentere `updateMetadataFromQueue`.** Wordt nu per sessie-emit aangeroepen i.p.v. alleen bij `queue_updated`. `MutableStateFlow` dedupt gelijke waarden, dus geen UI-flikker; alleen iets meer rekenwerk (verwaarloosbaar). De now-playing-*logica* zelf is onveranderd (Stap 3 vervangt 'm later door `session.nowPlaying`).
- **Spelerslijst blijft VM-verantwoordelijkheid.** De store levert (nog) geen spelerslijst; `handlePlayerUpdated` onderhoudt `_players` voor de picker/auto-select. Dat blijft zo tot een eventuele latere stap `repo.players` introduceert.

## Zelf-review (uitgevoerd)

- **Spec-dekking:** Stap 2 uit de spec (§"Incrementele uitrol": `PlayerViewModel._queue/_queueItems` uit de store) → Task 1. Device-zijde expliciet uitgesteld naar Stap 3 (scope-grens). 
- **Geen placeholders:** elke code-stap bevat volledige code; elke run-stap een exact commando + verwachte uitkomst.
- **Type-consistentie:** `session.player`/`session.queue`/`session.queueItems`/`session.isPlaying` ⇄ `PlayerSession` (Stap 0). `_queue: MutableStateFlow<PlayerQueue?>` en `_queueItems: MutableStateFlow<List<QueueItem>>` ongewijzigd van type. `seedQueue` is nu `suspend` (gebruikt `sessionFlow.emit`); de bestaande callers staan al in `runTest`-context.
- **Geen overbouw:** alleen de queue-bron verschuift; UI, commands en now-playing-afleiding blijven ongemoeid.
