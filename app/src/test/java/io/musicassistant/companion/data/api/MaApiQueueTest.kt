package io.musicassistant.companion.data.api

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/**
 * Verifies the queue-mutation API wrappers send the exact Music Assistant commands/args confirmed
 * against the live server + `controllers/player_queues.py`:
 *  - `player_queues/delete_item` expects `item_id_or_index` (NOT `queue_item_id`).
 *  - `player_queues/clear` expects just `queue_id`.
 * (`player_queues/move_item` does use `queue_item_id` and is covered by its own existing behaviour.)
 */
class MaApiQueueTest {

    private val client: MaApiClient = mockk()
    private val api = MaApi(client)

    @Test
    fun `queueDeleteItem sends delete_item with item_id_or_index`() = runTest {
        coEvery { client.sendCommand(any(), any()) } returns JsonObject(emptyMap())

        api.queueDeleteItem("upma_q1", "item-abc")

        coVerify(exactly = 1) {
            client.sendCommand(
                "player_queues/delete_item",
                mapOf(
                    "queue_id" to JsonPrimitive("upma_q1"),
                    "item_id_or_index" to JsonPrimitive("item-abc"),
                ),
            )
        }
    }

    @Test
    fun `queueClear sends player_queues clear with queue_id`() = runTest {
        coEvery { client.sendCommand(any(), any()) } returns JsonObject(emptyMap())

        api.queueClear("upma_q1")

        coVerify(exactly = 1) {
            client.sendCommand(
                "player_queues/clear",
                mapOf("queue_id" to JsonPrimitive("upma_q1")),
            )
        }
    }
}
