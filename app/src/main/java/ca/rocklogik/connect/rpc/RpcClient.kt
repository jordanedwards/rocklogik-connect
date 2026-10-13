package ca.rocklogik.connect.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The byte link under the client: a BLE Nordic UART Service, or a fake in tests. */
interface ByteLink {
    /** Every notification's bytes, in order. Completes when the link closes. */
    val incoming: Flow<ByteArray>
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

/** A JSON-RPC error from the device, or a local failure given a protocol name. */
class RpcException(val code: Int, val name: String, val data: String? = null) :
    Exception(if (data != null) "$name: $data" else name) {
    companion object {
        const val TIMEOUT = "timeout"
        const val LINK_CLOSED = "link_closed"
    }
}

/**
 * JSON-RPC 2.0 over a [ByteLink], to docs/protocol.md.
 *
 * Written clean-room from the protocol document (ROCKLOGIK-88), not from Cricket Connect.
 * Requests carry integer ids and replies may arrive in any order. A request answered with
 * `not_authenticated` is retried once after re-sending the key, if one has been accepted on
 * this link. A ping every [pingIntervalMs] of idle detects a dead link; [pingMisses] missed
 * pings in a row close it.
 */
class RpcClient(
    private val link: ByteLink,
    private val scope: CoroutineScope,
    private val log: (direction: Direction, json: String) -> Unit = { _, _ -> },
    private val pingIntervalMs: Long = 5_000,
    private val pingMisses: Int = 3,
) {
    enum class Direction { OUT, IN }

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val framer = LineFramer()
    private val writeLock = Mutex()
    private var key: String? = null
    @Volatile private var activity = false
    private var reader: Job? = null
    private var pinger: Job? = null

    private val _alive = MutableStateFlow(true)
    val alive: StateFlow<Boolean> = _alive

    fun start() {
        reader = scope.launch {
            try {
                link.incoming.collect { bytes -> onBytes(bytes) }
            } finally {
                failAll(RpcException(0, RpcException.LINK_CLOSED))
            }
        }
        if (pingIntervalMs > 0) pinger = scope.launch { pingLoop() }
    }

    suspend fun close() {
        pinger?.cancel()
        reader?.cancel()
        failAll(RpcException(0, RpcException.LINK_CLOSED))
        link.close()
    }

    /** Sends the key and remembers it for re-authentication on this link. */
    suspend fun authenticate(key: String) {
        call("ble_auth_v1", JSONObject().put("key", key), retryAuth = false)
        this.key = key
    }

    /**
     * Calls [method] and returns its `result` (a JSONObject, JSONArray, String, number or
     * boolean). Throws [RpcException] for a device error, a timeout or a closed link.
     */
    suspend fun call(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = 10_000,
        retryAuth: Boolean = true,
    ): Any? {
        try {
            return callOnce(method, params, timeoutMs)
        } catch (e: RpcException) {
            val k = key
            if (retryAuth && e.name == "not_authenticated" && k != null && method != "ble_auth_v1") {
                callOnce("ble_auth_v1", JSONObject().put("key", k), timeoutMs)
                return callOnce(method, params, timeoutMs)
            }
            throw e
        }
    }

    private suspend fun callOnce(method: String, params: JSONObject?, timeoutMs: Long): Any? {
        if (!_alive.value) throw RpcException(0, RpcException.LINK_CLOSED)
        val id = nextId.getAndIncrement()
        val req = JSONObject().put("jsonrpc", "2.0").put("method", method).put("id", id)
        if (params != null) req.put("params", params)
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        try {
            val text = req.toString()
            log(Direction.OUT, Redact.json(text))
            writeLock.withLock { link.write((text + "\n").toByteArray(Charsets.UTF_8)) }
            activity = true
            val reply = try {
                withTimeout(timeoutMs) { waiter.await() }
            } catch (e: TimeoutCancellationException) {
                throw RpcException(0, RpcException.TIMEOUT, "$method after ${timeoutMs} ms")
            }
            val err = reply.optJSONObject("error")
            if (err != null) {
                throw RpcException(err.optInt("code"), err.optString("message", "error"),
                    err.optString("data").ifEmpty { null })
            }
            return if (reply.has("result")) reply.get("result").let { if (it == JSONObject.NULL) null else it } else null
        } finally {
            pending.remove(id)
        }
    }

    private fun onBytes(bytes: ByteArray) {
        activity = true
        for (item in framer.push(bytes)) {
            if (item !is LineFramer.Line) continue
            log(Direction.IN, Redact.json(item.text))
            val obj = try {
                JSONObject(item.text)
            } catch (e: JSONException) {
                continue
            }
            if (!obj.has("id") || obj.isNull("id")) continue
            val id = obj.optInt("id", -1)
            pending[id]?.complete(obj)
        }
    }

    private suspend fun pingLoop() {
        var misses = 0
        while (true) {
            delay(pingIntervalMs)
            if (activity) {
                activity = false
                continue
            }
            misses = try {
                callOnce("ping_v1", null, pingIntervalMs)
                activity = false
                0
            } catch (e: RpcException) {
                if (e.name == RpcException.LINK_CLOSED) return
                misses + 1
            }
            if (misses >= pingMisses) {
                _alive.value = false
                failAll(RpcException(0, RpcException.LINK_CLOSED, "no reply to $pingMisses pings"))
                return
            }
        }
    }

    private fun failAll(e: RpcException) {
        _alive.value = false
        for ((_, w) in pending) w.completeExceptionally(e)
        pending.clear()
    }
}

/** Secrets never reach a log or a terminal (docs/protocol.md section 4). */
object Redact {
    val SECRET_FIELDS = setOf("key", "psk")

    fun json(text: String): String = try {
        when {
            text.trimStart().startsWith("{") -> redact(JSONObject(text)).toString()
            text.trimStart().startsWith("[") -> redact(JSONArray(text)).toString()
            else -> text
        }
    } catch (e: JSONException) {
        // Unparseable: redact anything that looks like a secret field rather than show it.
        text.replace(Regex("\"(key|psk)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"<redacted>\"")
    }

    private fun redact(o: Any?): Any? = when (o) {
        is JSONObject -> JSONObject().also { out ->
            for (k in o.keys()) out.put(k, if (k in SECRET_FIELDS) "<redacted>" else redact(o.get(k)))
        }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until o.length()) out.put(redact(o.get(i))) }
        else -> o
    }
}
