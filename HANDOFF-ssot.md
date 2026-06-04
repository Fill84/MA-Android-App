# Handoff — MA Android App (SSOT-richting)

> Plak dit in een nieuwe, schone conversatie. Het bevat alle context + geverifieerde feiten zodat
> niets opnieuw onderzocht hoeft te worden. Werk in het Nederlands (de gebruiker is Nederlandstalig).

## 0. Project & werkwijze
- Repo: `f:\DevProjects\MA-Android-App-2.0` (Android/Kotlin/Compose). GitHub: `https://github.com/Fill84/MA-Android-App-2.0`. Branch: `fix/queue-remove-clear`. Versie v5.0.0 (code 8).
- Het is een **Music Assistant companion** (Sendspin-client). Speelt audio via eigen AudioTrack; media-surface (notificatie/lockscreen/**Bluetooth AVRCP**/Android Auto) via Media3 `MediaLibrarySession`.
- **Projectregels (CLAUDE.md):** houd `claude_mem.md`, `todos.md` en `plan-*.md` (project root) bij. **Globale regel:** ZÉRO self-references in commits/PR's (géén `Co-Authored-By: Claude`, géén "Generated with Claude Code"-footer).
- Pixel 5 hangt aan adb: serial `08201FDD4003KF`, Android 14. `adb` staat op PATH. Niets is gecommit; gebruiker beslist over commits.

## 1. KLAAR & geverifieerd (uncommitted, in de werkboom)
Twee bugs opgelost en on-device geverifieerd (zie `claude_mem.md` sessie 5 voor detail):

**(a) Cover art in de auto** — root cause: voor groep/`active_source`-spelers is de device-queue `ma_<id>` leeg, dus `MusicService.updateMetadataFromQueue` bailt → alleen het Sendspin-pad (zonder cover/buren) voedde de sessie → app-icoon i.p.v. echte cover.
- Nieuw: `media/QueueNeighbors.kt` (`resolveNeighbors`, pure regel: radio/eerste/laatste → geen buur) + `QueueNeighborsTest.kt`.
- `MusicService.kt`: nieuwe `pushTrackFromSource(player)` (echte cover uit `player.current_media.image_url`; prev/next strikt op index via één `getPlayerQueueItems(sourceId, limit=3, offset=idx-1)`), aangeroepen vanuit het non-radio pad van `resolveLiveContext`. Imports `Player`, `resolveNeighbors`.
- `MediaMetadataCoordinator.kt`: `bytes=` toegevoegd aan de emit-log.
- Car-vrij bewezen: app-icoon `bytes=13948` → echte cover `bytes=25741` in `ALBUM_ART`; prev/next correct. **Gebruiker test de auto (BMW X2 F39) morgen** — enige openstaande verificatie.

**(b) Mini-player laadt niet bij herstart** — root cause: app selecteerde nooit een actieve speler (timing + verkeerde aanname over `ma_` vs de universal `upma_`). De "Pixel 5"-kaart die de gebruiker ziet = universal player `upma_jt06pzq30f` (naam "Google Pixel 5").
- `PlayerViewModel.kt`: reactieve `autoSelectPlayingPlayer()` (observeert `_players`; kiest eigen-spelende → enige spelende → eigen), `applyActivePlayer()`, `effectiveQueueId()` (gebruikt `active_source` want device-queue leeg), `activeQueueId`, `ownPlayerId`; event-filters op `activeQueueId`. Nieuwe test in `PlayerViewModelTest.kt`. On-device + screenshot geverifieerd.

Bewust behouden debug-Log.d (in-stijl): `source nowplaying`, `bytes=`, `select player`, `auto-select now-playing`. **Alle unittests groen.**

## 2. Architectuurprincipe (door gebruiker bepaald — leidend)
**Er is precies ÉÉN Single Source Of Truth: de MA-server. Dit geldt voor ALLES, niet alleen settings.** De app mag alleen **cachen/spiegelen** (+ verbindings-/apparaat-identiteit), nooit concurrerende eigen kopieën van serverstaat houden. De twee opgeloste bugs waren symptomen hiervan (meerdere parallelle afleidingen van serverstaat die uiteenliepen).

Waar de app het principe nu schendt:
| Domein | Server = bron | Foute parallelle kopie in app |
|---|---|---|
| Settings | `config.name`/`config.enabled`, `preferred_sendspin_format` | lokale `playerName`/`playerEnabled`/`codecPreference` (dood/dubbel) |
| Now-playing | speler/queue | 2× apart afgeleid: `MediaMetadataCoordinator` (sessie) + `PlayerViewModel` (UI) |
| Play-state | `queue.state`/`player.state` | `MusicService.playingState` + `MaPlayer.streamPlaying` + `PlayerViewModel._isPlaying` |
| Queue | server-queue | `PlayerViewModel._queue` + `MusicService.currentQueueItemId` |

## 3. EERSTVOLGENDE TAAK — Settings-SSOT (klein, geverifieerd, klaar om te bouwen)
Maak codec/name/enabled volledig server-gevoed en **sloop de lokale duplicaten**. Sla op via de bestaande **"Save changes"-knop** (dirty-flow), consistent met de andere MA-config.

**Geverifieerde feiten (niet opnieuw onderzoeken):**
- `MaApi.getPlayerConfig` → `PlayerConfig` heeft **top-level** `name`, `default_name`, `enabled` + `values: Map<String, ConfigEntry>`. `MaApi.savePlayerConfig(playerId, values)` accepteert `name`/`enabled` als keys in de `values`-dict (zie doc-comment in `MaApi.kt`).
- Codec = config-entry met key eindigend op `preferred_sendspin_format` (STRING). Opties zijn **dynamisch**: MA biedt `automatic` + één optie per formaat dat de **client adverteert**.
- **Geverifieerd experiment:** laat je `SendspinCapabilities.buildSupportedFormats` ALLE codecs adverteren (loop over `Codec.entries`), dan biedt de server: `automatic`, `pcm:44100:16:2..pcm:48000:32:2` (6), `flac:44100:16:2..flac:48000:32:2` (6), `opus:48000:16:2` (1) = 14 opties. Formaatwaarde = `codec:samplerate:bitdepth:channels`. (Dit experiment is teruggedraaid; her-implementeer als onderdeel van de fix.)
- Lokale `playerName` wordt NÉRGENS naar de server gestuurd (handshake gebruikt `Build.MANUFACTURER + " " + Build.MODEL` als deviceName in `ServiceLocator.buildSendspinConfig`). Lokale `playerEnabled` is niet aan registratie gekoppeld. Beide zijn dood → veilig te verwijderen.
- De speler die de gebruiker configureert vanuit home = universal `upma_jt06pzq30f`; zijn config bevat de member-keys `ma_jt06pzq30f||protocol||preferred_sendspin_format` etc. `settings.playerId` = `ma_jt06pzq30f`.

**Ontwerp (akkoord met gebruiker):**
1. **codec → server-SSOT, GEEN lokaal meer (ook geen cache):** `SendspinCapabilities.buildSupportedFormats` adverteert ALLE codecs (capability, geen instelling). De "Audio codec"-dropdown in `PlayerSettingsScreen` gebruikt de **server-entry** (`preferred_sendspin_format`): toont diens opties+titels en huidige waarde via `currentConfigValue`, schrijft via `configViewModel.setValue(key, value)` → dirty → "Save changes". Verberg die entry uit de dynamische lijst (`PlayerConfigSection`) om dubbeling te voorkomen. **Verwijder `codecPreference` volledig** uit `AppSettings`/`SettingsRepository`/`SendspinConfig`/`ServiceLocator` (handshake adverteert voortaan altijd alles). Let op: `automatic` betekent dat MA kiest — test dat audio nog speelt (PCM/FLAC/Opus).
2. **name → `config.name`:** "Player name"-veld bindt aan `currentConfigValue(config, edited, "name")` (synthetische key), schrijft via `setValue("name", ...)`. Verwijder lokale `setPlayerName`/`playerName`.
3. **enabled → `config.enabled`:** "Local player enabled"-switch bindt aan synthetische key `"enabled"`, schrijft via `setValue("enabled", ...)`. Verwijder lokale `setPlayerEnabled`/`playerEnabled`. (Er is ook `…||protocol||enabled`; map de switch op de **top-level** `config.enabled`.)
4. **ViewModel uitbreiden** (`PlayerConfigViewModel.kt`): `currentConfigValue`/`configDirtyValues`/`setValue` moeten de synthetische keys `"name"`/`"enabled"` afhandelen — vergelijk tegen `config.name (?: defaultName)` resp. `config.enabled` i.p.v. `config.values[...]`. `save()` stuurt ze mee in `dirtyValues` (API accepteert ze al).
5. **Tests + on-device:** rename → Save → MA toont nieuwe naam (herladen persistent); enabled togglen → Save; codec kiezen → Save → audio speelt; niets dubbel; één save-knop regelt alles. (UI bereiken: long-press speler-kaart op home, ~tap `730 520` op 1080x2340. Screenshot: `adb -s 08201FDD4003KF exec-out screencap -p > "C:/Users/philp/AppData/Local/Temp/x.png"` (Windows-pad i.v.m. MSYS).)

Het bestaande `plan-codec-server-unify.md` is gedeeltelijk achterhaald (ging uit van een simpel duplicaat); deze handoff is leidend.

## 4. DAARNA — Architectuur-SSOT (groot, apart plannen via brainstorming → spec → plan)
Maak de MA-server de enige bron voor **player-state / now-playing / queue**. Bouw **één** repository/store die de server spiegelt (één afleiding van now-playing, één `isPlaying`, één queue), geconsumeerd door zowel de media-sessie-stack (`MediaMetadataCoordinator`/`MaPlayer`) als de UI (`PlayerViewModel`/`MiniPlayer`). Dit elimineert de parallelle afleidingen (de structurele oorzaak van o.a. de net opgeloste bugs). Eerst brainstormen + spec + plan; niet ad-hoc bouwen.

## 5. Kleinere openstaande taak
- **About-sectie** in `SettingsScreen.kt` (er is al een ABOUT-card, regel ~293) uitbreiden met klikbare links via `LocalUriHandler`: GitHub repo (`https://github.com/Fill84/MA-Android-App-2.0`), "Report an issue" (`.../issues`), Music Assistant project (`https://www.music-assistant.io`). Iconen (material-icons-extended is beschikbaar): `Code`, `BugReport`, `Info`, trailing `OpenInNew`.

## 6. Direct te doen in de nieuwe conversatie
1. Lees `claude_mem.md` + `todos.md` + `plan-*.md` (projectregel).
2. Pak **Settings-SSOT** (sectie 3) op met `superpowers:test-driven-development` waar zinvol; verifieer on-device.
3. Daarna About-links (sectie 5).
4. Architectuur-SSOT (sectie 4) pas na expliciete brainstorm/akkoord.
5. Cover-art: wacht op gebruikers BMW-resultaat; bij groen → commit-beslissing (cover art + mini-player als nette aparte commits, zónder self-references).
