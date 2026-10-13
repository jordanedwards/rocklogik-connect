package ca.rocklogik.connect.ble

import android.bluetooth.BluetoothDevice
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings

/** A VISR seen in a scan. */
data class FoundUnit(val device: BluetoothDevice, val name: String, val rssi: Int, val seenAt: Long) {
    val serial: String get() = name.removePrefix(Nus.NAME_PREFIX)
    /** Signal as 0 to 4 bars, for the Find screen. */
    val bars: Int get() = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        rssi >= -90 -> 1
        else -> 0
    }
}

/**
 * Scans for VISR units: a name starting VISR-, or the Nordic UART Service in the advert (a long
 * name may push the service UUID into the scan response, or the UUID may push out the name, so
 * either is enough). No hardware filter: some phones drop name-filtered results.
 */
object VisrScanner {
    fun scan(): Flow<FoundUnit> = callbackFlow {
        val scanner = BluetoothLeScannerCompat.getScanner()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .setUseHardwareFilteringIfSupported(false)
            .build()
        val nus = ParcelUuid(Nus.SERVICE)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: safeName(result.device)
                val hasNus = result.scanRecord?.serviceUuids?.contains(nus) == true
                if (name?.startsWith(Nus.NAME_PREFIX) == true || hasNus) {
                    trySend(FoundUnit(result.device, name ?: "VISR-?", result.rssi, System.currentTimeMillis()))
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    @Suppress("MissingPermission")
    private fun safeName(d: BluetoothDevice): String? = try {
        d.name
    } catch (e: SecurityException) {
        null
    }
}
