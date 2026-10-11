package staff.android.threadline

import android.app.Application
import android.os.SystemClock
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CaseState(
    val data: JSONObject? = null,
    val busy: Boolean = true,
    val problem: String? = null,
    val pending: JSONObject? = null,
    val outcome: JSONObject? = null,
    val observedAt: Long = 0,
    val receivedElapsed: Long = 0,
    val storageBlocked: Boolean = false
)

open class CaseApi {
    private val client =
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

    open fun request(path: String, body: JSONObject? = null): JSONObject {
        val builder = Request.Builder().url("http://10.0.2.2:8915$path")
        if (body != null)
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            val out = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val bytes = ByteArray(4096)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    if (n > 262144 - out.size())
                        throw IOException("Response exceeds fixture contract")
                    out.write(bytes, 0, n)
                }
            }
            val decoder =
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val result =
                JSONObject(decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString())
            if (!response.isSuccessful)
                throw IOException("Service ${response.code}: ${result.optString("error")}")
            require(result.getString("kind") == "threadline")
            return result
        }
    }
}

class CaseModel(application: Application) : AndroidViewModel(application) {
    private val api = CaseApi()
    private val journal = AtomicFile(File(application.filesDir, "pending-command.json"))
    private val replica = ConversationReplica(application.filesDir)
    private val mutable = MutableStateFlow(CaseState())
    val state = mutable.asStateFlow()

    // Work is serialized by the busy gate, including persistence and refresh.
    init {
        viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { replica.saved() }
                mutable.value = state.value.copy(data = cached)
                val pending = withContext(Dispatchers.IO) { read() }
                mutable.value = state.value.copy(pending = pending)
                refreshInside()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false,
                        storageBlocked = true,
                        problem =
                            "Saved request could not be read. It is preserved for investigation.")
            }
        }
    }

    private fun read(): JSONObject? {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists())
            return null
        val wrapper = JSONObject(String(journal.readFully(), Charsets.UTF_8))
        require(wrapper.has("pending"))
        if (wrapper.isNull("pending")) return null
        val command = wrapper.getJSONObject("pending")
        require(UUID.fromString(command.getString("id")).toString() == command.getString("id"))
        require(command.getString("action").isNotBlank())
        command.getJSONObject("args")
        return command
    }

    private fun save(command: JSONObject?) {
        val output = journal.startWrite()
        try {
            output.write(
                JSONObject().put("pending", command ?: JSONObject.NULL).toString().toByteArray())
            journal.finishWrite(output)
        } catch (error: Exception) {
            journal.failWrite(output)
            throw error
        }
    }

    fun refresh() {
        if (state.value.busy || state.value.storageBlocked) return
        mutable.value = state.value.copy(busy = true, problem = null)
        viewModelScope.launch { refreshInside() }
    }

    private suspend fun refreshInside() {
        try {
            val response = withContext(Dispatchers.IO) { replica.refresh(api) }
            val data = response.getJSONObject("data")
            validateView(data)
            val time = response.get("observedAt")
            require(time is Long || time is Int)
            mutable.value =
                state.value.copy(
                    data = data,
                    observedAt = (time as Number).toLong(),
                    receivedElapsed = SystemClock.elapsedRealtime(),
                    busy = false,
                    problem = null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val cached = withContext(Dispatchers.IO) { runCatching { replica.saved() }.getOrNull() }
            mutable.value =
                state.value.copy(
                    data = cached,
                    busy = false,
                    problem =
                        "Current state could not be checked. Any visible data is the previously committed conversation.")
        }
    }

    fun send(action: String, args: JSONObject = JSONObject()) {
        if (state.value.busy || state.value.storageBlocked || state.value.pending != null) return
        val command =
            JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("action", action)
                .put("args", args)
        mutable.value = state.value.copy(busy = true, problem = null, outcome = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { save(command) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.value =
                    state.value.copy(
                        busy = false, problem = "Request could not be saved. Nothing new was sent.")
                return@launch
            }
            mutable.value = state.value.copy(pending = command)
            deliver(command)
        }
    }

    fun retry() {
        val before = state.value
        if (before.busy || before.storageBlocked || before.pending == null) return
        mutable.value = before.copy(busy = true, problem = null)
        viewModelScope.launch { deliver(before.pending) }
    }

    private suspend fun deliver(command: JSONObject) {
        try {
            val receipt = withContext(Dispatchers.IO) { api.request("/commands", command) }
            require(receipt.getString("commandId") == command.getString("id"))
            val outcome = receipt.getJSONObject("outcome")
            require(outcome.get("accepted") is Boolean)
            if (!outcome.getBoolean("accepted")) {
                val reason = outcome.get("reason")
                require(reason is String && reason.isNotBlank())
            }
            withContext(Dispatchers.IO) { save(null) }
            mutable.value = state.value.copy(pending = null, outcome = outcome)
            // An idempotent receipt can be historical. Never publish receipt.data as current.
            refreshInside()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.value =
                state.value.copy(
                    busy = false,
                    problem =
                        "This request is not acknowledged locally. Its identity is saved; retry the same request.")
        }
    }
}
