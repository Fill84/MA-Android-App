# Architectuur-SSOT — Fundament + Play-state (Stap 0 & 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduceer één centrale server-mirror (`PlayerRepository`) met pure afleidingen, bedraad in ServiceLocator, en migreer als eerste consument `PlayerViewModel._isPlaying` zodat de UI-playstate uit de store komt — zonder waarneembare gedragsverandering.

**Architecture:** Aanpak C (lazy, ref-counted per-id `session(id)`-flows). Een `PlayerRepository` is de enige plek die op `apiClient.events`/`connectionState` reageert en speler/queue ophaalt. Pure `NowPlayingDerivation`-functies (geen Android) leiden now-playing/play-state af. Twee projecties (device + selectie) consumeren dezelfde `session(id)`-flow; in dit plan migreert alleen de UI-playstate.

**Tech Stack:** Kotlin, Coroutines/Flow (`channelFlow`, `shareIn`, `WhileSubscribed`, `flatMapLatest`), kotlinx-serialization, JUnit4 + MockK + kotlinx-coroutines-test + Turbine (allen al in de test-classpath).

**Scope-grens:** Dit plan dekt **Stap 0** (fundament, niet-geconsumeerd) en **Stap 1** (UI-playstate). **Stap 2 (queue)** en **Stap 3 (now-playing)** krijgen elk een eigen plan ná dit fundament; Stap 3 pas ná de BMW cover-art-test. `MusicService.playingState` en `MaPlayer.streamPlaying` blijven bewust lokale-audio-staat (Sendspin) en vallen buiten dit plan. Zie `docs/superpowers/specs/2026-06-04-architecture-ssot-design.md`.

**Globale regel:** géén self-references in commits (geen `Co-Authored-By`, geen "Generated with"-footer).

---

## Bestandsindeling

- Create: `app/src/main/java/io/musicassistant/companion/data/player/NowPlaying.kt` — pure model + `NowPlayingDerivation`.
- Create: `app/src/main/java/io/musicassistant/companion/data/player/PlayerSession.kt` — unified per-speler model.
- Create: `app/src/main/java/io/musicassistant/companion/data/player/PlayerRepository.kt` — lazy per-id `session()`-flows; de mirror.
- Modify: `app/src/main/java/io/musicassistant/companion/service/ServiceLocator.kt` — singleton-bedrading + repo-scope.
- Modify: `app/src/main/java/io/musicassistant/companion/ui/player/PlayerViewModel.kt` — `_isPlaying` uit `session(selectedId)`.
- Create test: `app/src/test/java/io/musicassistant/companion/data/player/NowPlayingDerivationTest.kt`
- Create test: `app/src/test/java/io/musicassistant/companion/data/player/PlayerRepositoryTest.kt`
- Modify test: `app/src/test/java/io/musicassistant/companion/ui/player/PlayerViewModelTest.kt`

---

## Task 1: Pure now-playing + play-state afleiding

**Files:**
- Create: `app/src/main/java/io/musicassistant/companion/data/player/NowPlaying.kt`
- Test: `app/src/test/java/io/musicassistant/companion/data/player/NowPlayingDerivationTest.kt`

- [ ] **Step 1: Schrijf de falende test**

```kotlin
package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.ItemMapping
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.model.QueueMediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingDerivationTest {

    private fun trackItem(
        name: String = "Song",
        duration: Int = 200,
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        mediaType: MediaType = MediaType.TRACK,
    ) = QueueItem(
        queueItemId = "qi1",
        name = name,
        duration = duration,
        mediaItem = QueueMediaItem(
            name = name,
            mediaType = mediaType,
            artists = artists.map { ItemMapping(name = it) },
            album = album?.let { ItemMapping(name = it) },
        ),
    )

    private fun queue(
        item: QueueItem?,
        state: PlayerState = PlayerState.PLAYING,
        index: Int? = 3,
        elapsed: Double = 12.0,
    ) = PlayerQueue(
        queueId = "q",
        currentItem = item,
        currentIndex = index,
        state = state,
        elapsedTime = elapsed,
    )

    @Test
    fun `track derivation maps title artist album and duration`() {
        val np = NowPlayingDerivation.deriveNowPlaying(player = null, queue = queue(trackItem()))
        requireNotNull(np)
        assertEquals("Song", np.title)
        assertEquals("Artist", np.artist)
        assertEquals("Album", np.album)
        assertFalse(np.isLive)
        assertEquals(200_000L, np.durationMs)
        assertEquals(12_000L, np.elapsedMs)
        assertEquals(3, np.currentIndex)
        assertEquals("qi1", np.currentQueueItemId)
    }

    @Test
    fun `radio derivation prefers currentMedia title and artist`() {
        val player = Player(
            playerId = "ma_1",
            currentMedia = io.musicassistant.companion.data.model.CurrentMedia(
                mediaType = "radio", title = "Live Song", artist = "Live Artist",
                imageUrl = "http://192.168.1.2:8095/imageproxy?x=1",
            ),
        )
        val item = trackItem(name = "Station", duration = 0, mediaType = MediaType.RADIO)
        val np = NowPlayingDerivation.deriveNowPlaying(player, queue(item, index = null))
        requireNotNull(np)
        assertEquals("Live Song", np.title)
        assertEquals("Live Artist", np.artist)
        assertTrue(np.isLive)
        assertEquals(0L, np.durationMs)
        assertEquals("http://192.168.1.2:8095/imageproxy?x=1", np.currentMediaImageUrl)
    }

    @Test
    fun `live when duration is zero even for non-radio`() {
        val np = NowPlayingDerivation.deriveNowPlaying(null, queue(trackItem(duration = 0)))
        requireNotNull(np)
        assertTrue(np.isLive)
        assertEquals(0L, np.durationMs)
    }

    @Test
    fun `null when no current item`() {
        assertNull(NowPlayingDerivation.deriveNowPlaying(null, queue(item = null)))
        assertNull(NowPlayingDerivation.deriveNowPlaying(null, null))
    }

    @Test
    fun `isPlaying from queue state, falls back to player state`() {
        assertTrue(NowPlayingDerivation.deriveIsPlaying(queue(trackItem(), state = PlayerState.PLAYING), null))
        assertFalse(NowPlayingDerivation.deriveIsPlaying(queue(trackItem(), state = PlayerState.PAUSED), null))
        assertTrue(NowPlayingDerivation.deriveIsPlaying(null, Player(state = PlayerState.PLAYING)))
        assertFalse(NowPlayingDerivation.deriveIsPlaying(null, null))
    }
}
```

- [ ] **Step 2: Draai de test, verwacht falen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.data.player.NowPlayingDerivationTest"`
Expected: FAIL — `Unresolved reference: NowPlayingDerivation` / `NowPlaying`.

- [ ] **Step 3: Implementeer `NowPlaying.kt`**

```kotlin
package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState

/**
 * Derived now-playing for one player/queue. Pure data — no Android, no network. Artwork is kept as
 * RAW references ([artworkImage] = the server image ref, [currentMediaImageUrl] = the radio song's
 * url). Turning those into a device-reachable URL and downloading bytes is the consumer's job
 * (connection identity, not server state) — see MusicService.resolveArtworkUrl / PlayerViewModel.getImageUrl.
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String?,
    val artworkImage: MediaItemImage?,
    val currentMediaImageUrl: String?,
    val isLive: Boolean,
    val durationMs: Long,
    val elapsedMs: Long,
    val currentIndex: Int?,
    val currentQueueItemId: String?,
)

/** Pure derivations that replace the duplicated logic in PlayerViewModel + MusicService. */
object NowPlayingDerivation {

    /** Radio-vs-track logic, mirroring both existing consumers exactly. */
    fun deriveNowPlaying(player: Player?, queue: PlayerQueue?): NowPlaying? {
        val currentItem = queue?.currentItem ?: return null
        val media = currentItem.mediaItem ?: return null
        val cm = player?.currentMedia
        val isRadio = media.mediaType == MediaType.RADIO
        val isLive = isRadio || currentItem.duration <= 0

        val title: String
        val artist: String
        val album: String?
        if (isRadio) {
            title = cm?.title?.takeIf { it.isNotBlank() } ?: currentItem.name.ifBlank { media.name }
            artist = cm?.artist?.takeIf { it.isNotBlank() } ?: media.name
            album = cm?.album ?: media.name
        } else {
            title = media.name
            artist = media.artists.joinToString(", ") { it.name }
            album = media.album?.name
        }

        return NowPlaying(
            title = title,
            artist = artist,
            album = album,
            artworkImage = media.image ?: currentItem.image,
            currentMediaImageUrl = cm?.imageUrl?.takeIf { it.isNotBlank() },
            isLive = isLive,
            durationMs = if (currentItem.duration > 0 && !isLive) currentItem.duration * 1000L else 0L,
            elapsedMs = (queue.elapsedTime * 1000).toLong(),
            currentIndex = queue.currentIndex,
            currentQueueItemId = currentItem.queueItemId,
        )
    }

    /** Logical play-state. queue.state is authoritative; player.state is the fallback. */
    fun deriveIsPlaying(queue: PlayerQueue?, player: Player?): Boolean = when {
        queue != null -> queue.state == PlayerState.PLAYING
        else -> player?.state == PlayerState.PLAYING
    }
}
```

- [ ] **Step 4: Draai de test, verwacht slagen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.data.player.NowPlayingDerivationTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit** (alleen als de gebruiker de commit-grens heeft opgeheven; anders overslaan en doorgaan)

```bash
git add app/src/main/java/io/musicassistant/companion/data/player/NowPlaying.kt \
        app/src/test/java/io/musicassistant/companion/data/player/NowPlayingDerivationTest.kt
git commit -m "feat(player): pure now-playing + isPlaying derivation"
```

---

## Task 2: PlayerSession-model

**Files:**
- Create: `app/src/main/java/io/musicassistant/companion/data/player/PlayerSession.kt`

- [ ] **Step 1: Implementeer `PlayerSession.kt`** (data-holder; getest via de repository-flow in Task 3)

```kotlin
package io.musicassistant.companion.data.player

import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.QueueItem

/** Unified, derived state for ONE player — the consumable projection of the server mirror. */
data class PlayerSession(
    val playerId: String,
    val effectiveQueueId: String,
    val player: Player?,
    val queue: PlayerQueue?,
    val queueItems: List<QueueItem>,
    val nowPlaying: NowPlaying?,
    val isPlaying: Boolean,
) {
    companion object {
        fun empty(playerId: String) = PlayerSession(
            playerId = playerId,
            effectiveQueueId = playerId,
            player = null,
            queue = null,
            queueItems = emptyList(),
            nowPlaying = null,
            isPlaying = false,
        )
    }
}
```

- [ ] **Step 2: Compileer-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit** (zelfde commit-grens-voorbehoud)

```bash
git add app/src/main/java/io/musicassistant/companion/data/player/PlayerSession.kt
git commit -m "feat(player): PlayerSession unified model"
```

---

## Task 3: PlayerRepository — lazy per-id session()-flow

**Files:**
- Create: `app/src/main/java/io/musicassistant/companion/data/player/PlayerRepository.kt`
- Test: `app/src/test/java/io/musicassistant/companion/data/player/PlayerRepositoryTest.kt`

- [ ] **Step 1: Schrijf de falende test**

```kotlin
package io.musicassistant.companion.data.player

import app.cash.turbine.test
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.data.model.QueueMediaItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRepositoryTest {

    private val events = MutableSharedFlow<MaApiClient.MaEvent>(extraBufferCapacity = 64)
    private val connState = MutableStateFlow(ConnectionState.AUTHENTICATED)

    private fun queue(state: PlayerState, id: String = "ma_1") = PlayerQueue(
        queueId = id,
        state = state,
        currentItem = QueueItem(
            queueItemId = "qi1", name = "Song", duration = 100,
            mediaItem = QueueMediaItem(name = "Song", mediaType = MediaType.TRACK),
        ),
        currentIndex = 0,
    )

    private fun fakes(): Pair<MaApi, MaApiClient> {
        val api = mockk<MaApi>(relaxed = true)
        val client = mockk<MaApiClient>(relaxed = true)
        every { client.events } returns events
        every { client.connectionState } returns connState
        coEvery { api.getPlayer("ma_1") } returns Player(playerId = "ma_1", state = PlayerState.PLAYING)
        coEvery { api.getPlayerQueue("ma_1") } returns queue(PlayerState.PLAYING)
        coEvery { api.getPlayerQueueItems("ma_1", any(), any()) } returns emptyList<QueueItem>()
        return api to client
    }

    @Test
    fun `emits derived session after authenticated`() = runTest {
        val (api, client) = fakes()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            val s = awaitItem()
            assertEquals("ma_1", s.playerId)
            assertTrue(s.isPlaying)
            assertEquals("Song", s.nowPlaying?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `re-emits on queue_updated with inline data`() = runTest {
        val (api, client) = fakes()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            assertTrue(awaitItem().isPlaying)            // initial PLAYING
            // Server reports PAUSED via a queue_updated event (no inline data → fallback fetch).
            coEvery { api.getPlayerQueue("ma_1") } returns queue(PlayerState.PAUSED)
            events.emit(MaApiClient.MaEvent(event = "queue_updated", objectId = "ma_1", data = null))
            assertEquals(false, awaitItem().isPlaying)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `player_updated with new active_source switches queue id`() = runTest {
        val (api, client) = fakes()
        coEvery { api.getPlayer("ma_1") } returns
            Player(playerId = "ma_1", state = PlayerState.PLAYING, activeSource = "upma_1")
        coEvery { api.getPlayerQueue("upma_1") } returns queue(PlayerState.PLAYING, id = "upma_1")
        coEvery { api.getPlayerQueueItems("upma_1", any(), any()) } returns emptyList<QueueItem>()
        val repo = PlayerRepository(api, client, backgroundScope)

        repo.session("ma_1").test {
            val s = awaitItem()
            assertEquals("upma_1", s.effectiveQueueId)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 2: Draai de test, verwacht falen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.data.player.PlayerRepositoryTest"`
Expected: FAIL — `Unresolved reference: PlayerRepository`.

- [ ] **Step 3: Implementeer `PlayerRepository.kt`**

```kotlin
package io.musicassistant.companion.data.player

import android.util.Log
import io.musicassistant.companion.data.api.MaApi
import io.musicassistant.companion.data.api.MaApiClient
import io.musicassistant.companion.data.model.ConnectionState
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerQueue
import io.musicassistant.companion.data.model.QueueItem
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Single server mirror. The ONLY place that reacts to [MaApiClient.events]/[connectionState] and
 * fetches player/queue state. Exposes lazy, ref-counted per-id [session] flows (Approach C): each
 * lives only while observed (plus a 5s grace), so the device session stays warm as long as
 * MusicService collects it while roamed-to UI players are torn down shortly after.
 */
class PlayerRepository(
    private val api: MaApi,
    private val apiClient: MaApiClient,
    private val scope: CoroutineScope,
) {
    companion object { private const val TAG = "PlayerRepository" }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val sessions = ConcurrentHashMap<String, SharedFlow<PlayerSession>>()

    /** Shared, ref-counted derived state for [playerId]. */
    fun session(playerId: String): SharedFlow<PlayerSession> =
        sessions.getOrPut(playerId) {
            buildSessionFlow(playerId)
                .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
        }

    private fun effectiveQueueId(player: Player): String =
        player.activeSource?.takeIf { it.isNotBlank() } ?: player.playerId

    private fun tryParsePlayer(data: JsonElement?): Player? = data?.let {
        runCatching { json.decodeFromJsonElement(Player.serializer(), it) }.getOrNull()
    }

    private fun tryParseQueue(data: JsonElement?): PlayerQueue? = data?.let {
        runCatching { json.decodeFromJsonElement(PlayerQueue.serializer(), it) }.getOrNull()
    }

    private fun buildSessionFlow(playerId: String): Flow<PlayerSession> = channelFlow {
        var player: Player? = null
        var queueId: String = playerId
        var queue: PlayerQueue? = null
        var items: List<QueueItem> = emptyList()

        suspend fun emitState() {
            send(
                PlayerSession(
                    playerId = playerId,
                    effectiveQueueId = queueId,
                    player = player,
                    queue = queue,
                    queueItems = items,
                    nowPlaying = NowPlayingDerivation.deriveNowPlaying(player, queue),
                    isPlaying = NowPlayingDerivation.deriveIsPlaying(queue, player),
                )
            )
        }

        suspend fun loadQueue() {
            queue = runCatching { api.getPlayerQueue(queueId) }.getOrNull()
            items = runCatching { api.getPlayerQueueItems(queueId) }.getOrElse { emptyList() }
        }

        suspend fun reload() {
            player = runCatching { api.getPlayer(playerId) }.getOrNull()
            queueId = player?.let { effectiveQueueId(it) } ?: playerId
            loadQueue()
            emitState()
        }

        if (apiClient.connectionState.value == ConnectionState.AUTHENTICATED) reload()

        // Reload on each (re)authentication.
        launch {
            apiClient.connectionState.collect { state ->
                if (state == ConnectionState.AUTHENTICATED) reload()
            }
        }

        // Merge server events for this player/queue (inline event data; fallback fetch).
        apiClient.events.collect { event ->
            when (event.event) {
                "player_updated", "player_added" -> {
                    if (event.objectId != null && event.objectId != playerId) return@collect
                    val updated = tryParsePlayer(event.data)
                        ?: runCatching { api.getPlayer(playerId) }.getOrNull() ?: return@collect
                    player = updated
                    val newQid = effectiveQueueId(updated)
                    if (newQid != queueId) { queueId = newQid; loadQueue() }
                    emitState()
                }
                "queue_updated" -> {
                    if (event.objectId != null && event.objectId != queueId) return@collect
                    val q = tryParseQueue(event.data)
                        ?: runCatching { api.getPlayerQueue(queueId) }.getOrNull() ?: return@collect
                    queue = q
                    emitState()
                }
                "queue_items_updated" -> {
                    if (event.objectId != null && event.objectId != queueId) return@collect
                    items = runCatching { api.getPlayerQueueItems(queueId) }.getOrElse { emptyList() }
                    emitState()
                }
            }
        }
    }
}
```

- [ ] **Step 4: Draai de test, verwacht slagen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.data.player.PlayerRepositoryTest"`
Expected: PASS (3 tests). Als een test hangt op `awaitItem()`: controleer dat `connState` op `AUTHENTICATED` staat vóór collect (de fake zet dat al).

- [ ] **Step 5: Commit** (commit-grens-voorbehoud)

```bash
git add app/src/main/java/io/musicassistant/companion/data/player/PlayerRepository.kt \
        app/src/test/java/io/musicassistant/companion/data/player/PlayerRepositoryTest.kt
git commit -m "feat(player): PlayerRepository lazy per-id session flow (server mirror)"
```

---

## Task 4: ServiceLocator-bedrading

**Files:**
- Modify: `app/src/main/java/io/musicassistant/companion/service/ServiceLocator.kt`

- [ ] **Step 1: Voeg de repo-singleton + scope toe**

Voeg de imports toe (bovenin, bij de andere imports):

```kotlin
import io.musicassistant.companion.data.player.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
```

Voeg binnen `object ServiceLocator` toe, direct ná de regel `val api: MaApi by lazy { MaApi(apiClient) }`:

```kotlin
    /** App-scoped scope for the player mirror; lives as long as the process. */
    private val repoScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** Single server mirror for player/queue/now-playing state. */
    val playerRepository: PlayerRepository by lazy { PlayerRepository(api, apiClient, repoScope) }
```

- [ ] **Step 2: Compileer-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Volledige test-suite groen houden (geen regressie)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — alle bestaande tests + de nieuwe `data.player`-tests groen.

- [ ] **Step 4: Commit** (commit-grens-voorbehoud)

```bash
git add app/src/main/java/io/musicassistant/companion/service/ServiceLocator.kt
git commit -m "feat(player): wire PlayerRepository singleton into ServiceLocator"
```

---

## Task 5 (Stap 1): PlayerViewModel — UI-playstate uit de store

Migreer `PlayerViewModel._isPlaying` zodat het uit `playerRepository.session(selectedId).isPlaying` komt, met behoud van het optimistische debounce-laagje. De selectie-plumbing (`selectedId` + `flatMapLatest`) die hier landt, wordt in latere stappen (queue/now-playing) hergebruikt. Queue/metadata blijven in deze stap nog via de bestaande paden lopen (transitioneel; opgeruimd in Stap 2/3).

**Files:**
- Modify: `app/src/main/java/io/musicassistant/companion/ui/player/PlayerViewModel.kt`
- Test: `app/src/test/java/io/musicassistant/companion/ui/player/PlayerViewModelTest.kt`

- [ ] **Step 1: Schrijf de falende test**

Voeg toe aan `PlayerViewModelTest` (gebruik het bestaande setup-patroon van die test: `mockkObject(ServiceLocator)` etc.). Voeg in de bestaande `@Before`/setup een gemockte repo toe en zorg dat `ServiceLocator.playerRepository` die teruggeeft:

```kotlin
    // In de test-class: een controleerbare sessie-flow per id.
    private val sessionFlow = kotlinx.coroutines.flow.MutableSharedFlow<io.musicassistant.companion.data.player.PlayerSession>(replay = 1)

    // In setup(), ná het mocken van ServiceLocator.api/apiClient:
    // every { ServiceLocator.playerRepository } returns fakeRepo
    // every { fakeRepo.session(any()) } returns sessionFlow
```

```kotlin
    @Test
    fun `isPlaying follows the selected session`() = runTest {
        val vm = PlayerViewModel(app)
        // Selecteer onze speler (zet selectedId → abonneert op sessionFlow).
        vm.selectPlayer("ma_1")
        sessionFlow.emit(
            io.musicassistant.companion.data.player.PlayerSession.empty("ma_1").copy(isPlaying = true)
        )
        vm.isPlaying.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `optimistic pause wins over a stale playing event within debounce`() = runTest {
        val vm = PlayerViewModel(app)
        vm.selectPlayer("ma_1")
        vm.pause() // user action → optimistic false + debounce window
        // A late server session still says PLAYING; debounce must keep it false.
        sessionFlow.emit(
            io.musicassistant.companion.data.player.PlayerSession.empty("ma_1").copy(isPlaying = true)
        )
        vm.isPlaying.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
```

- [ ] **Step 2: Draai de test, verwacht falen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.ui.player.PlayerViewModelTest"`
Expected: FAIL — `playerRepository`/selection-plumbing bestaat nog niet, of `isPlaying` volgt de sessie niet.

- [ ] **Step 3: Implementeer de wijziging in `PlayerViewModel.kt`**

Voeg het repo-veld toe bij de andere injectie-velden (rond regel 41-43):

```kotlin
    private val repo = ServiceLocator.playerRepository
```

Voeg imports toe (bovenin):

```kotlin
import io.musicassistant.companion.data.player.PlayerSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
```

Voeg een selectie-id + sessie-observatie toe. Plaats dit veld bij de overige private flows (rond regel 73 waar `activeQueueId` staat):

```kotlin
    /** The player id whose session feeds the UI; null until selected/auto-selected. */
    private val selectedId = MutableStateFlow<String?>(null)
```

Voeg in `init { ... }` (ná `observeEvents()`) de sessie-observatie toe:

```kotlin
        observeSelectedSession()
```

Voeg de methode toe (bij de andere observe-methoden):

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSelectedSession() {
        selectedId
            .flatMapLatest { id -> if (id == null) flowOf(null) else repo.session(id) }
            .onEach { session: PlayerSession? ->
                if (session != null) setIsPlayingFromServer(session.isPlaying)
            }
            .launchIn(viewModelScope)
    }
```

Zet `selectedId` bij selectie. In `applyActivePlayer(player)` (rond regel 199), ná `_activePlayer.value = player`:

```kotlin
        selectedId.value = player.playerId
```

In `selectPlayer(playerId)` (rond regel 556), in de else-tak waar een onbekende speler direct wordt geladen, ná `_activePlayer.value = null`:

```kotlin
        selectedId.value = playerId
```

Verwijder de nu-dubbele server-gedreven `_isPlaying`-bron in `handleQueueUpdated` (rond regel 300): verwijder de regel `setIsPlayingFromServer(q.state == PlayerState.PLAYING)` — de sessie-observatie levert dit nu. Laat de rest van `handleQueueUpdated` (queue/metadata) ongemoeid.

Laat `applyActivePlayer`'s regel `_isPlaying.value = player.state == PlayerState.PLAYING` staan als directe seed (snelle eerste waarde vóór de sessie binnenkomt); de sessie corrigeert daarna. Dit blijft binnen "gedrag behouden".

- [ ] **Step 4: Draai de gerichte test, verwacht slagen**

Run: `./gradlew :app:testDebugUnitTest --tests "io.musicassistant.companion.ui.player.PlayerViewModelTest"`
Expected: PASS — inclusief de twee nieuwe tests en alle bestaande PlayerViewModel-tests.

- [ ] **Step 5: Volledige suite + build**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (alles groen).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: On-device verificatie (Pixel 5, `08201FDD4003KF`)**

Installeer en controleer dat de play/pause-staat in de UI exact zoals vóór reageert:
- App openen terwijl muziek speelt → mini-player toont "playing" (play/pause-knop correct).
- Pauzeer via de app → knop wordt direct pauze, flikkert niet terug naar play.
- Pauzeer/hervat via een ander apparaat (server-event) → UI volgt binnen ~1s.
- Logcat-signalen `auto-select now-playing` / `select player` blijven identiek aan vóór de wijziging.

Screenshot: `adb -s 08201FDD4003KF exec-out screencap -p > "C:/Users/philp/AppData/Local/Temp/playstate.png"`

- [ ] **Step 7: Commit** (commit-grens-voorbehoud)

```bash
git add app/src/main/java/io/musicassistant/companion/ui/player/PlayerViewModel.kt \
        app/src/test/java/io/musicassistant/companion/ui/player/PlayerViewModelTest.kt
git commit -m "feat(player): drive PlayerViewModel isPlaying from the server-mirror session"
```

---

## Zelf-review (uitgevoerd)

- **Spec-dekking:** Stap 0 (store-skelet: `PlayerRepository`/`PlayerSession`/`NowPlayingDerivation` + ServiceLocator) → Task 1-4. Stap 1 (play-state UI) → Task 5. Stap 2/3 expliciet buiten scope (eigen plannen), conform spec §"Incrementele uitrol". De device-only overlays (Sendspin/stationslogo/AVRCP-buren) blijven ongemoeid — bevestigd in scope-grens.
- **Geen placeholders:** elke code-stap bevat volledige code; elke run-stap een exact commando + verwachte uitkomst.
- **Type-consistentie:** `session(id): SharedFlow<PlayerSession>`, `deriveNowPlaying(player, queue): NowPlaying?`, `deriveIsPlaying(queue, player): Boolean`, `PlayerSession.empty(id)`, `playerRepository` — consistent gebruikt over Task 1→5. `NowPlaying` draagt rauwe artwork-refs (geen base-URL nodig), consistent met spec §"Pure afleidingslaag".
- **Bekend aandachtspunt voor de uitvoerder:** in `PlayerRepositoryTest` draait `shareIn` op `backgroundScope` onder `runTest` (virtuele tijd) — `WhileSubscribed(5_000)` start zodra Turbine collect; de initiële `reload()` loopt vóór de event-collectie (kleine race, identiek aan huidig gedrag, acceptabel). Als de PlayerViewModel-test flaky is op de debounce-timing: gebruik dezelfde `runTest`-virtuele klok als de bestaande PlayerViewModel-tests.
