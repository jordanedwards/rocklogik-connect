package ca.rocklogik.connect.rpc

import org.json.JSONArray
import org.json.JSONObject

/** Typed calls and results for docs/protocol.md section 5. Unknown fields are ignored. */
class Visr(val rpc: RpcClient) {

    data class Info(
        val serial: String,
        val track: String,
        val hwRev: String,
        val swVersion: String,
        val protocolVersion: Int,
        val methods: Map<String, List<Int>>,
        val notecardUid: String?,
        val uptimeS: Long,
    )

    data class Check(val id: String, val state: String, val title: String, val detail: String) {
        val green: Boolean get() = state == "ok" || state == "off"
    }

    data class Status(
        val at: Double,
        val uptimeS: Long,
        val canLive: Boolean,
        val bitrate: Int?,
        val framesPerS: Double,
        val busErrors: Int,
        val transport: String,
        val segmentsPending: Int,
        val segmentsAcked: Int,
        val checks: List<Check>,
        val raw: JSONObject,
    ) {
        val green: Boolean get() = checks.isNotEmpty() && checks.all { it.green }
        val greenCount: Int get() = checks.count { it.green }
    }

    data class Network(val ssid: String, val rssiDbm: Int, val security: String, val captive: Boolean?, val known: Boolean)

    suspend fun ping(): Boolean = rpc.call("ping_v1") == "pong"

    suspend fun authenticate(key: String) = rpc.authenticate(key.trim().uppercase())

    suspend fun info(): Info = parseInfo(rpc.call("info_v1") as JSONObject)

    suspend fun status(): Status = parseStatus(rpc.call("status_v1") as JSONObject)

    suspend fun configGet(): JSONObject = rpc.call("config_get_v1") as JSONObject

    /** Sends only the blocks given; secrets in [blocks] go to the device and nowhere else. */
    suspend fun configSet(blocks: JSONObject): JSONObject = rpc.call("config_set_v1", blocks) as JSONObject

    suspend fun wifiScan(): List<Network> {
        val arr = (rpc.call("wifi_scan_v1", timeoutMs = 25_000) as JSONObject).getJSONArray("networks")
        return (0 until arr.length()).map { i ->
            val n = arr.getJSONObject(i)
            Network(
                ssid = n.getString("ssid"),
                rssiDbm = n.optInt("rssi_dbm"),
                security = n.optString("security"),
                captive = if (n.isNull("captive")) null else n.optBoolean("captive"),
                known = n.optBoolean("known"),
            )
        }
    }

    companion object {
        fun parseInfo(o: JSONObject): Info {
            val proto = o.getJSONObject("protocol")
            val methodsObj = proto.optJSONObject("methods") ?: JSONObject()
            val methods = methodsObj.keys().asSequence().associateWith { k ->
                val a: JSONArray = methodsObj.getJSONArray(k)
                (0 until a.length()).map { a.getInt(it) }
            }
            return Info(
                serial = o.getString("serial"),
                track = o.getString("track"),
                hwRev = o.optString("hw_rev"),
                swVersion = o.optString("sw_version"),
                protocolVersion = proto.getInt("version"),
                methods = methods,
                notecardUid = o.optStringOrNull("notecard_uid"),
                uptimeS = o.optLong("uptime_s"),
            )
        }

        fun parseStatus(o: JSONObject): Status {
            val can = o.getJSONObject("can")
            val nc = o.getJSONObject("notecard")
            val ar = o.getJSONObject("archive")
            val arr = o.getJSONArray("checks")
            val checks = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                Check(c.getString("id"), c.getString("state"), c.getString("title"), c.optString("detail"))
            }
            return Status(
                at = o.getDouble("at"),
                uptimeS = o.optLong("uptime_s"),
                canLive = can.getBoolean("live"),
                bitrate = if (can.isNull("bitrate")) null else can.getInt("bitrate"),
                framesPerS = can.optDouble("frames_per_s", 0.0),
                busErrors = can.optInt("bus_errors"),
                transport = nc.optString("transport", "none"),
                segmentsPending = ar.optInt("segments_pending"),
                segmentsAcked = ar.optInt("segments_acked"),
                checks = checks,
                raw = o,
            )
        }

        private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
    }
}

/** The lid label's QR text: `rlk1:visr?s=<serial>&k=<key>&ap=<ssid>` (docs/protocol.md section 2). */
data class LidLabel(val serial: String, val key: String, val accessPoint: String?) {
    companion object {
        private val KEY = Regex("^[A-Z2-7]{26}$")

        fun parse(text: String): LidLabel? {
            val t = text.trim()
            if (!t.startsWith("rlk1:visr?")) return null
            val q = t.removePrefix("rlk1:visr?").split("&").mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            val serial = q["s"] ?: return null
            val key = q["k"]?.uppercase() ?: return null
            if (!KEY.matches(key)) return null
            return LidLabel(serial, key, q["ap"])
        }

        fun isKey(text: String): Boolean = KEY.matches(text.trim().uppercase())
    }
}
