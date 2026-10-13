package ca.rocklogik.connect.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A link whose far end is the test: it sees every line written and chooses what comes back. */
private class FakeLink : ByteLink {
    val toPhone = Channel<ByteArray>(Channel.UNLIMITED)
    val written = mutableListOf<JSONObject>()
    private val partial = StringBuilder()
    var onRequest: (JSONObject) -> List<String> = { emptyList() }

    override val incoming: Flow<ByteArray> = toPhone.consumeAsFlow()

    override suspend fun write(bytes: ByteArray) {
        partial.append(String(bytes, Charsets.UTF_8))
        while (true) {
            val nl = partial.indexOf("\n")
            if (nl < 0) break
            val req = JSONObject(partial.substring(0, nl))
            partial.delete(0, nl + 1)
            written += req
            for (reply in onRequest(req)) toPhone.trySend(reply.toByteArray())
        }
    }

    override suspend fun close() {
        toPhone.close()
    }

    fun result(req: JSONObject, result: Any) =
        JSONObject().put("jsonrpc", "2.0").put("id", req.getInt("id")).put("result", result).toString() + "\n"

    fun error(req: JSONObject, code: Int, name: String) =
        JSONObject().put("jsonrpc", "2.0").put("id", req.getInt("id"))
            .put("error", JSONObject().put("code", code).put("message", name)).toString() + "\n"
}

@OptIn(ExperimentalCoroutinesApi::class)
class RpcClientTest {

    private fun TestScope.client(link: FakeLink, log: MutableList<String>? = null, ping: Long = 0): RpcClient {
        val c = RpcClient(link, CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob()),
            log = { _, s -> log?.add(s) }, pingIntervalMs = ping)
        c.start()
        return c
    }

    @Test
    fun replyReassembledAcrossNotifications() = runTest {
        val link = FakeLink()
        link.onRequest = { req ->
            val line = link.result(req, "pong")
            line.chunked(3) // 3-byte notifications
        }
        val c = client(link)
        assertEquals("pong", c.call("ping_v1"))
    }

    @Test
    fun twoRepliesInOneNotificationAndOutOfOrder() = runTest {
        val link = FakeLink()
        val held = mutableListOf<JSONObject>()
        link.onRequest = { req ->
            held += req
            if (held.size == 2) listOf(link.result(held[1], "second") + link.result(held[0], "first")) else emptyList()
        }
        val c = client(link)
        val a = async { c.call("info_v1") }
        val b = async { c.call("status_v1") }
        assertEquals("first", a.await())
        assertEquals("second", b.await())
    }

    @Test
    fun deviceErrorBecomesRpcException() = runTest {
        val link = FakeLink()
        link.onRequest = { req -> listOf(link.error(req, -32001, "not_supported")) }
        val c = client(link)
        try {
            c.call("wifi_scan_v1")
            fail("expected an exception")
        } catch (e: RpcException) {
            assertEquals("not_supported", e.name)
            assertEquals(-32001, e.code)
        }
    }

    @Test
    fun timeoutBecomesRpcException() = runTest {
        val link = FakeLink() // never answers
        val c = client(link)
        val d = async { runCatching { c.call("status_v1", timeoutMs = 1_000) } }
        advanceTimeBy(1_001)
        runCurrent()
        val e = d.await().exceptionOrNull()
        assertTrue(e is RpcException && e.name == RpcException.TIMEOUT)
    }

    @Test
    fun reauthenticatesOnceThenRetries() = runTest {
        val link = FakeLink()
        var authed = false
        var statusCalls = 0
        link.onRequest = { req ->
            when (req.getString("method")) {
                "ble_auth_v1" -> { authed = true; listOf(link.result(req, JSONObject().put("status", true))) }
                "status_v1" -> {
                    statusCalls++
                    if (statusCalls == 1) { authed = false; listOf(link.error(req, -32002, "not_authenticated")) }
                    else listOf(link.result(req, JSONObject().put("ok", authed)))
                }
                else -> emptyList()
            }
        }
        val c = client(link)
        c.authenticate("MOCKKEYMOCKKEYMOCKKEYMOCKK")
        val r = c.call("status_v1") as JSONObject
        assertTrue(r.getBoolean("ok"))
        assertEquals(listOf("ble_auth_v1", "status_v1", "ble_auth_v1", "status_v1"), link.written.map { it.getString("method") })
    }

    @Test
    fun noRetryWithoutAnAcceptedKey() = runTest {
        val link = FakeLink()
        link.onRequest = { req -> listOf(link.error(req, -32002, "not_authenticated")) }
        val c = client(link)
        try {
            c.call("info_v1"); fail()
        } catch (e: RpcException) {
            assertEquals("not_authenticated", e.name)
        }
        assertEquals(1, link.written.size)
    }

    @Test
    fun secretsNeverReachTheLog() = runTest {
        val link = FakeLink()
        link.onRequest = { req -> listOf(link.result(req, JSONObject().put("status", true))) }
        val log = mutableListOf<String>()
        val c = client(link, log)
        c.authenticate("MOCKKEYMOCKKEYMOCKKEYMOCKK")
        c.call("config_set_v1", JSONObject().put("wifi", org.json.JSONArray().put(
            JSONObject().put("ssid", "S").put("security", "wpa2-psk").put("psk", "hunter2-secret"))))
        val all = log.joinToString("\n")
        assertFalse(all.contains("MOCKKEYMOCKKEYMOCKKEYMOCKK"))
        assertFalse(all.contains("hunter2-secret"))
        assertTrue(all.contains("<redacted>"))
    }

    @Test
    fun deadLinkDetectedAfterMissedPings() = runTest {
        val link = FakeLink() // never answers pings
        val c = client(link, ping = 1_000)
        assertTrue(c.alive.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(c.alive.value)
        c.close()
    }

    @Test
    fun framerDiscardsAnOverlongLine() {
        val f = LineFramer(maxLine = 10)
        val items = f.push("0123456789ABCDEF\n{\"ok\":1}\n".toByteArray())
        assertEquals(listOf(LineFramer.Overflow, LineFramer.Line("{\"ok\":1}")), items)
    }

    @Test
    fun lidLabelParses() {
        val l = LidLabel.parse("rlk1:visr?s=0001&k=mockkeymockkeymockkeymockk&ap=VISR-0001-svc")!!
        assertEquals("0001", l.serial)
        assertEquals("MOCKKEYMOCKKEYMOCKKEYMOCKK", l.key)
        assertEquals("VISR-0001-svc", l.accessPoint)
        assertEquals(null, LidLabel.parse("rlk1:visr?s=0001&k=short"))
        assertEquals(null, LidLabel.parse("https://example.com"))
    }

    @Test
    fun statusParsesAndJudgesGreen() {
        val checks = org.json.JSONArray()
        listOf("ok", "ok", "wait", "ok", "ok", "off", "ok", "ok").forEachIndexed { i, s ->
            checks.put(JSONObject().put("id", "c$i").put("state", s).put("title", "T").put("detail", "D"))
        }
        val o = JSONObject().put("at", 1.0).put("uptime_s", 5)
            .put("can", JSONObject().put("live", true).put("bitrate", 250000).put("frames_per_s", 600.0).put("bus_errors", 0))
            .put("notecard", JSONObject().put("transport", "cell"))
            .put("archive", JSONObject().put("segments_pending", 1).put("segments_acked", 0))
            .put("checks", checks)
        val s = Visr.parseStatus(o)
        assertFalse(s.green)
        assertEquals(7, s.greenCount)
    }
}
