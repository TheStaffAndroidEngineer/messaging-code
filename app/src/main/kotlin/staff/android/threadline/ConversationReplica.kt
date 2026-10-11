package staff.android.threadline

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun strictLong(value: Any): Long {
    require(value is Int || value is Long)
    return (value as Number).toLong()
}

fun checkMessage(message: JSONObject) {
    for (key in listOf("id", "author", "text")) require(message.get(key) is String)
    require(message.getString("id").isNotBlank() && message.getString("text").length <= 500)
    require(strictLong(message.get("sequence")) >= 1 && message.get("deleted") is Boolean)
}

class ConversationReplica(directory: File) {
    private val file = AtomicFile(File(directory, "conversation.json"))

    private fun empty(epoch: Long = 0, cursor: Long = 0, active: Boolean = true) =
        JSONObject()
            .put("epoch", epoch)
            .put("cursor", cursor)
            .put("active", active)
            .put("messages", JSONArray())

    private fun read(): JSONObject? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val value = JSONObject(String(file.readFully(), Charsets.UTF_8))
        check(value)
        return value
    }

    private fun check(value: JSONObject) {
        require(strictLong(value.get("epoch")) >= 0 && strictLong(value.get("cursor")) >= 0)
        require(value.get("active") is Boolean)
        val messages = value.getJSONArray("messages")
        require(messages.length() <= 100)
        require(value.getBoolean("active") || messages.length() == 0)
        val ids = mutableSetOf<String>()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            checkMessage(message)
            require(ids.add(message.getString("id")))
            require(
                !message.getBoolean("deleted") &&
                    message.getLong("sequence") <= value.getLong("cursor"))
        }
    }

    private fun save(value: JSONObject) {
        check(value)
        val out = file.startWrite()
        try {
            out.write(value.toString().toByteArray())
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        }
    }

    private fun product(value: JSONObject, cached: Boolean) =
        JSONObject(value.toString()).put("room", "Studio team").put("cached", cached)

    fun saved(): JSONObject? = read()?.let { product(it, true) }

    fun refresh(api: CaseApi): JSONObject {
        val metadata = api.request("/view")
        val meta = metadata.getJSONObject("data")
        val epoch = strictLong(meta.get("epoch"))
        require(meta.get("active") is Boolean)
        var work = read() ?: empty()
        if (!meta.getBoolean("active")) {
            work = empty(epoch, strictLong(meta.get("lastEvent")), false)
            save(work)
            return JSONObject()
                .put("observedAt", metadata.get("observedAt"))
                .put("data", product(work, false))
        }
        if (work.getLong("epoch") != epoch) {
            work = empty(epoch)
            save(work)
        }
        repeat(32) {
            val response = api.request("/sync?after=${work.getLong("cursor")}")
            val page = response.getJSONObject("data")
            val pageEpoch = strictLong(page.get("epoch"))
            val cursor = strictLong(page.get("cursor"))
            require(
                page.get("active") is Boolean &&
                    page.get("reset") is Boolean &&
                    page.get("more") is Boolean)
            if (page.getBoolean("reset")) {
                work =
                    empty(pageEpoch, cursor, page.getBoolean("active"))
                        .put("messages", page.getJSONArray("messages"))
            } else if (pageEpoch != work.getLong("epoch")) {
                // Do not merge a page requested with the previous membership's cursor.
                work = empty(pageEpoch)
                save(work)
                return@repeat
            } else {
                val rows = linkedMapOf<String, JSONObject>()
                val old = work.getJSONArray("messages")
                for (i in 0 until old.length()) {
                    val message = old.getJSONObject(i)
                    rows[message.getString("id")] = message
                }
                val events = page.getJSONArray("events")
                var last = work.getLong("cursor")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    val position = strictLong(event.get("event"))
                    require(position == last + 1)
                    require(event.getString("kind") in setOf("upsert", "delete"))
                    val message = event.getJSONObject("message")
                    checkMessage(message)
                    require(message.getString("id") == event.getString("messageId"))
                    if (event.getString("kind") == "delete") require(message.getBoolean("deleted"))
                    if (message.getBoolean("deleted")) rows.remove(message.getString("id"))
                    else rows[message.getString("id")] = message
                    last = position
                }
                require(cursor == last)
                work =
                    empty(pageEpoch, cursor, page.getBoolean("active"))
                        .put("messages", JSONArray(rows.values.sortedBy { it.getLong("sequence") }))
            }
            require(!page.getBoolean("more") || cursor > 0)
            save(work) // A crash cannot retain the cursor while losing its associated rows.
            if (!page.getBoolean("more"))
                return JSONObject()
                    .put("observedAt", response.get("observedAt"))
                    .put("data", product(work, false))
        }
        error("Fixture sync did not converge within its bounded history")
    }
}
