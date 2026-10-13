package ca.rocklogik.connect.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import ca.rocklogik.connect.rpc.ByteLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID

/** UUIDs of the Nordic UART Service (docs/protocol.md section 1). */
object Nus {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone writes
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // VISR notifies
    const val NAME_PREFIX = "VISR-"
    const val MTU = 247
}

/**
 * A [ByteLink] over one VISR's Nordic UART Service, on Nordic's Android BLE Library, which
 * queues GATT operations, negotiates the MTU and splits long writes.
 */
class NusLink(context: Context) : ByteLink {
    private val manager = Manager(context)
    private val bytes = Channel<ByteArray>(Channel.UNLIMITED)

    override val incoming: Flow<ByteArray> = bytes.receiveAsFlow()

    val mtu: Int get() = manager.mtuNow

    suspend fun connect(device: BluetoothDevice) {
        manager.connect(device)
            .retry(2, 200)
            .useAutoConnect(false)
            .timeout(15_000)
            .suspend()
    }

    override suspend fun write(bytes: ByteArray) {
        val rx = manager.rx ?: throw IllegalStateException("not connected")
        manager.send(rx, bytes)
    }

    override suspend fun close() {
        try {
            manager.disconnect().suspend()
        } catch (e: Exception) {
            Log.w(TAG, "disconnect: $e")
        }
        bytes.close()
    }

    private inner class Manager(context: Context) : BleManager(context) {
        var rx: BluetoothGattCharacteristic? = null
        var tx: BluetoothGattCharacteristic? = null
        var mtuNow = 23

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val s = gatt.getService(Nus.SERVICE) ?: return false
            rx = s.getCharacteristic(Nus.RX)
            tx = s.getCharacteristic(Nus.TX)
            return rx != null && tx != null
        }

        override fun initialize() {
            setNotificationCallback(tx).with { _, data -> data.value?.let { bytes.trySend(it) } }
            requestMtu(Nus.MTU).with { _, m -> mtuNow = m }.fail { _, s -> Log.w(TAG, "MTU request failed: $s") }.enqueue()
            enableNotifications(tx).enqueue()
        }

        override fun onServicesInvalidated() {
            rx = null
            tx = null
            bytes.close()
        }

        suspend fun send(c: BluetoothGattCharacteristic, data: ByteArray) {
            writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).split().suspend()
        }

        override fun getMinLogPriority(): Int = Log.INFO

        override fun log(priority: Int, message: String) {
            Log.println(priority, TAG, message)
        }
    }

    private companion object {
        const val TAG = "NusLink"
    }
}
