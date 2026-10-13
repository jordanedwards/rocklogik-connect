package ca.rocklogik.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.rocklogik.connect.ble.FoundUnit
import ca.rocklogik.connect.ble.NusLink
import ca.rocklogik.connect.ble.VisrScanner
import ca.rocklogik.connect.rpc.Redact
import ca.rocklogik.connect.rpc.RpcClient
import ca.rocklogik.connect.rpc.RpcException
import ca.rocklogik.connect.rpc.Visr
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The one connection to one VISR, shared by every screen. */
class ConnectViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Conn {
        data object Idle : Conn
        data class Connecting(val name: String) : Conn
        data class Locked(val name: String) : Conn
        data class Unlocking(val name: String) : Conn
        data class Ready(val name: String, val info: Visr.Info) : Conn
        data class Failed(val name: String?, val message: String) : Conn
    }

    data class TermLine(val time: String, val outgoing: Boolean, val text: String, val error: Boolean)

    private val _units = MutableStateFlow<List<FoundUnit>>(emptyList())
    val units: StateFlow<List<FoundUnit>> = _units.asStateFlow()
    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private val _conn = MutableStateFlow<Conn>(Conn.Idle)
    val conn: StateFlow<Conn> = _conn.asStateFlow()

    private val _status = MutableStateFlow<Visr.Status?>(null)
    val status: StateFlow<Visr.Status?> = _status.asStateFlow()
    private val _statusError = MutableStateFlow<String?>(null)
    val statusError: StateFlow<String?> = _statusError.asStateFlow()

    private val _terminal = MutableStateFlow<List<TermLine>>(emptyList())
    val terminal: StateFlow<List<TermLine>> = _terminal.asStateFlow()

    private var scanJob: Job? = null
    private var link: NusLink? = null
    private var rpc: RpcClient? = null
    var visr: Visr? = null
        private set

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    // -- scanning ------------------------------------------------------------------------

    fun startScan() {
        if (scanJob?.isActive == true) return
        _scanError.value = null
        scanJob = viewModelScope.launch {
            VisrScanner.scan()
                .catch { _scanError.value = it.message }
                .collect { u ->
                    val now = System.currentTimeMillis()
                    _units.value = (_units.value.filter { it.device.address != u.device.address && now - it.seenAt < 30_000 } + u)
                        .sortedByDescending { it.rssi }
                }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    // -- connection ----------------------------------------------------------------------

    fun connect(unit: FoundUnit) {
        stopScan()
        viewModelScope.launch {
            disconnectNow()
            _conn.value = Conn.Connecting(unit.name)
            val l = NusLink(getApplication())
            try {
                l.connect(unit.device)
                val c = RpcClient(l, viewModelScope, log = ::logLine)
                c.start()
                link = l
                rpc = c
                visr = Visr(c)
                _conn.value = Conn.Locked(unit.name)
                viewModelScope.launch {
                    c.alive.collect { alive ->
                        if (!alive && _conn.value !is Conn.Idle && link === l) {
                            _conn.value = Conn.Failed(unit.name, "The link to ${unit.name} dropped.")
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { l.close() }
                _conn.value = Conn.Failed(unit.name, "Could not connect: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun unlock(key: String) {
        val v = visr ?: return
        val name = currentName() ?: return
        viewModelScope.launch {
            _conn.value = Conn.Unlocking(name)
            try {
                v.authenticate(key)
                val info = v.info()
                _conn.value = Conn.Ready(name, info)
            } catch (e: RpcException) {
                _conn.value = if (e.name == "not_authenticated") {
                    Conn.Locked(name).also { _unlockError.value = "That key does not open $name. Check the label inside this unit's lid." }
                } else {
                    Conn.Failed(name, "Unlock failed: ${e.message}")
                }
            }
        }
    }

    private val _unlockError = MutableStateFlow<String?>(null)
    val unlockError: StateFlow<String?> = _unlockError.asStateFlow()
    fun clearUnlockError() { _unlockError.value = null }

    fun disconnect() {
        viewModelScope.launch { disconnectNow() }
    }

    private suspend fun disconnectNow() {
        val c = rpc
        rpc = null
        visr = null
        link = null
        _status.value = null
        if (c != null) runCatching { c.close() }
        _conn.value = Conn.Idle
    }

    private fun currentName(): String? = when (val c = _conn.value) {
        is Conn.Connecting -> c.name
        is Conn.Locked -> c.name
        is Conn.Unlocking -> c.name
        is Conn.Ready -> c.name
        is Conn.Failed -> c.name
        Conn.Idle -> null
    }

    // -- status --------------------------------------------------------------------------

    /** Polls status_v1 every 5 s for as long as the returned job runs (docs/protocol.md). */
    fun pollStatus(): Job = viewModelScope.launch {
        while (isActive) {
            val v = visr ?: break
            try {
                _status.value = v.status()
                _statusError.value = null
            } catch (e: RpcException) {
                _statusError.value = e.message
            }
            delay(5_000)
        }
    }

    // -- engineer terminal ---------------------------------------------------------------

    /** Sends a method name, or a whole JSON request object, as typed in the terminal. */
    fun sendRaw(input: String) {
        val c = rpc ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            try {
                if (text.startsWith("{")) {
                    val o = JSONObject(text)
                    c.call(o.getString("method"), o.optJSONObject("params"))
                } else {
                    c.call(text)
                }
            } catch (e: RpcException) {
                // the reply is already in the log
            } catch (e: JSONException) {
                append(TermLine(clock.format(Date()), true, "not JSON: ${e.message}", true))
            }
        }
    }

    private fun logLine(direction: RpcClient.Direction, json: String) {
        val err = json.contains("\"error\"")
        append(TermLine(clock.format(Date()), direction == RpcClient.Direction.OUT, Redact.json(json), err))
    }

    private fun append(l: TermLine) {
        _terminal.value = (_terminal.value + l).takeLast(500)
    }

    override fun onCleared() {
        val c = rpc
        if (c != null) kotlinx.coroutines.runBlocking { runCatching { c.close() } }
    }
}
