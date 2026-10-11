package staff.android.threadline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Review-owned contract tests. Real Android AtomicFile/JSON; scripted transport, no HTTP service. */
@RunWith(AndroidJUnit4::class)
class ReplicaContractTest {
    private lateinit var directory: File
    private lateinit var replica: ConversationReplica

    @Before fun setUp() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "replica-review-${System.nanoTime()}")
        check(directory.mkdirs())
        replica = ConversationReplica(directory)
    }

    @After fun cleanUp() { directory.deleteRecursively() }

    private data class Step(val path: String, val result: JSONObject? = null, val failure: IOException? = null)
    private class ScriptedApi(vararg script: Step) : CaseApi() {
        private val steps = ArrayDeque(script.toList())
        override fun request(path: String, body: JSONObject?): JSONObject {
            assertNull("Replica must only perform reads", body)
            check(steps.isNotEmpty()) { "Unexpected extra request $path" }
            val step = steps.removeFirst()
            assertEquals(step.path, path)
            step.failure?.let { throw it }
            return JSONObject(requireNotNull(step.result).toString())
        }
        fun exhausted() = assertTrue("Some expected requests were not made", steps.isEmpty())
    }

    private fun message(sequence: Int, deleted: Boolean = false) = JSONObject()
        .put("id", "m-$sequence").put("author", "You").put("text", "Message $sequence")
        .put("sequence", sequence).put("deleted", deleted)

    private fun metadata(epoch: Int = 1, last: Int = 3, active: Boolean = true) = envelope(JSONObject()
        .put("room", "Studio team").put("epoch", epoch).put("active", active)
        .put("lastEvent", last).put("messageCount", 3))

    private fun envelope(data: JSONObject) = JSONObject().put("kind", "threadline")
        .put("observedAt", 1000).put("data", data)

    private fun page(cursor: Int, epoch: Int = 1, active: Boolean = true, reset: Boolean = false,
                     messages: List<JSONObject> = emptyList(), events: List<JSONObject> = emptyList(),
                     more: Boolean = false) = envelope(JSONObject().put("epoch", epoch).put("active", active)
        .put("reset", reset).put("messages", JSONArray(messages)).put("events", JSONArray(events))
        .put("cursor", cursor).put("more", more))

    private fun event(position: Int, kind: String, message: JSONObject) = JSONObject()
        .put("event", position).put("kind", kind).put("messageId", message.getString("id"))
        .put("message", message)

    private fun seed(count: Int = 3) {
        val api = ScriptedApi(Step("/view", metadata(last = count)),
            Step("/sync?after=0", page(count, reset = true, messages = (1..count).map { message(it) })))
        replica.refresh(api); api.exhausted()
    }

    private fun persisted() = requireNotNull(ConversationReplica(directory).saved())
    private fun ids(value: JSONObject): List<String> {
        val rows = value.getJSONArray("messages")
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Malformed sync must not be committed") }
        catch (_: IllegalArgumentException) { /* Contract rejection. */ }
    }
    private fun unavailable(block: () -> Unit) {
        try { block(); fail("Scripted sync must fail") }
        catch (_: IOException) { /* Expected connection failure. */ }
    }

    @Test fun gapAfterValidDeleteDoesNotPartiallyCommitRowsOrCursor() {
        seed()
        val api = ScriptedApi(Step("/view", metadata(last = 6)), Step("/sync?after=3",
            page(6, events = listOf(event(4, "delete", message(3, true)), event(6, "upsert", message(6))))))
        rejected { replica.refresh(api) }; api.exhausted()
        val restored = persisted()
        assertEquals(3L, restored.getLong("cursor"))
        assertEquals(listOf("m-1", "m-2", "m-3"), ids(restored))
    }

    @Test fun cursorBeyondLastAppliedEventDoesNotLoseAnUnseenChange() {
        seed()
        val api = ScriptedApi(Step("/view", metadata(last = 5)), Step("/sync?after=3",
            page(5, events = listOf(event(4, "delete", message(3, true))))))
        rejected { replica.refresh(api) }; api.exhausted()
        assertEquals(3L, persisted().getLong("cursor"))
        assertTrue(ids(persisted()).contains("m-3"))
    }

    @Test fun malformedResetsAndFalseDeleteCannotReplaceACommittedConversation() {
        seed()
        val malformed = listOf(
            page(3, reset = true, messages = listOf(message(1), message(1))),
            page(3, reset = true, messages = listOf(message(3, true))),
            page(3, reset = true, messages = listOf(message(4))),
            page(3, active = false, reset = true, messages = listOf(message(1))),
            page(4, events = listOf(event(4, "delete", message(3))))
        )
        for (bad in malformed) {
            val api = ScriptedApi(Step("/view", metadata()), Step("/sync?after=3", bad))
            rejected { replica.refresh(api) }; api.exhausted()
            assertEquals(3L, persisted().getLong("cursor"))
            assertEquals(listOf("m-1", "m-2", "m-3"), ids(persisted()))
        }
    }

    @Test fun historicalUpsertCarryingCurrentTombstoneNeverCreatesALiveBubble() {
        seed(2)
        val api = ScriptedApi(Step("/view", metadata(last = 5)), Step("/sync?after=2",
            page(5, events = listOf(event(3, "upsert", message(3, true)),
                event(4, "delete", message(3, true)), event(5, "upsert", message(5))))))
        val result = replica.refresh(api).getJSONObject("data"); api.exhausted()
        assertEquals(5L, result.getLong("cursor"))
        assertEquals(listOf("m-1", "m-2", "m-5"), ids(result))
        assertEquals(ids(result), ids(persisted()))
    }

    @Test fun observedMembershipChangePurgesOldRowsEvenWhenFollowingSyncIsUnavailable() {
        seed()
        val api = ScriptedApi(Step("/view", metadata(epoch = 3)),
            Step("/sync?after=0", failure = IOException("Unavailable after membership observation")))
        unavailable { replica.refresh(api) }; api.exhausted()
        val restored = persisted()
        assertEquals(3L, restored.getLong("epoch"))
        assertEquals(0L, restored.getLong("cursor"))
        assertEquals(emptyList<String>(), ids(restored))
        assertTrue(restored.getBoolean("cached"))
    }

    @Test fun membershipChangeBetweenMetadataAndPagePurgesBeforeRetryFailure() {
        seed()
        val api = ScriptedApi(Step("/view", metadata(last = 4)),
            Step("/sync?after=3", page(4, epoch = 3, events = listOf(event(4, "upsert", message(4))))),
            Step("/sync?after=0", failure = IOException("Unavailable after new epoch page")))
        unavailable { replica.refresh(api) }; api.exhausted()
        assertEquals(3L, persisted().getLong("epoch"))
        assertEquals(0L, persisted().getLong("cursor"))
        assertTrue(ids(persisted()).isEmpty())
    }

    @Test fun completedPageSurvivesLaterFailureAndReconstructionResumesItsExactCursor() {
        seed()
        val interrupted = ScriptedApi(Step("/view", metadata(last = 6)), Step("/sync?after=3",
            page(5, events = listOf(event(4, "delete", message(3, true)), event(5, "upsert", message(5))), more = true)),
            Step("/sync?after=5", failure = IOException("Second page unavailable")))
        unavailable { replica.refresh(interrupted) }; interrupted.exhausted()
        assertEquals(5L, persisted().getLong("cursor"))
        assertEquals(listOf("m-1", "m-2", "m-5"), ids(persisted()))
        val resumed = ScriptedApi(Step("/view", metadata(last = 6)), Step("/sync?after=5",
            page(6, events = listOf(event(6, "upsert", message(6))))))
        val result = ConversationReplica(directory).refresh(resumed).getJSONObject("data"); resumed.exhausted()
        assertEquals(6L, result.getLong("cursor"))
        assertEquals(listOf("m-1", "m-2", "m-5", "m-6"), ids(result))
    }
}
