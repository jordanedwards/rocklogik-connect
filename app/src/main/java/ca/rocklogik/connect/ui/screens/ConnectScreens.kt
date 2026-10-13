package ca.rocklogik.connect.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import ca.rocklogik.connect.BuildConfig
import ca.rocklogik.connect.ConnectViewModel
import ca.rocklogik.connect.ConnectViewModel.Conn
import ca.rocklogik.connect.rpc.LidLabel
import ca.rocklogik.connect.ui.Card
import ca.rocklogik.connect.ui.CardRow
import ca.rocklogik.connect.ui.Glyph
import ca.rocklogik.connect.ui.Heading
import ca.rocklogik.connect.ui.Label
import ca.rocklogik.connect.ui.Mono
import ca.rocklogik.connect.ui.Note
import ca.rocklogik.connect.ui.Primary
import ca.rocklogik.connect.ui.Screen
import ca.rocklogik.connect.ui.Secondary
import ca.rocklogik.connect.ui.Sub
import ca.rocklogik.connect.ui.theme.Rl

// -- 1 Sign in ------------------------------------------------------------------------------

@Composable
fun SignInScreen(onSignedIn: () -> Unit) {
    val configured = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotEmpty()
    Column(Modifier.fillMaxSize().background(Rl.Ink).padding(horizontal = 24.dp, vertical = 32.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text("RockLogik Connect", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
            Spacer(Modifier.height(20.dp))
            Text("Provision, check and record a Scout VISR install from the cab.", color = Color(0xFFC9CCC6), fontSize = 17.sp, lineHeight = 26.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Primary(if (configured) "Sign in with Google" else "Continue (development build)", onClick = onSignedIn)
            Text(
                if (configured) "Access is by invitation. Your Google account must be on the RockLogik list."
                else "Google sign-in is not configured in this build (ROCKLOGIK-82, 90). Device features work; nothing is sent to the cloud.",
                color = Color(0xFFB4B8B2), fontSize = 14.sp, lineHeight = 21.sp,
            )
            Text("Version ${BuildConfig.VERSION_NAME}", color = Color(0xFF8A9095), fontSize = 13.sp)
        }
    }
}

// -- 2 Find ---------------------------------------------------------------------------------

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun hasPermissions(ctx: Context) = requiredPermissions().all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

private fun locationOff(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return !(lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
}

private fun bluetoothOff(ctx: Context): Boolean {
    val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    return bm.adapter?.isEnabled != true
}

@Composable
fun FindScreen(vm: ConnectViewModel, onSignOut: () -> Unit, onPrepare: () -> Unit, onPicked: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    var recheck by remember { mutableStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(ctx); recheck++
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { recheck++ }
    val units by vm.units.collectAsState()
    val scanError by vm.scanError.collectAsState()
    val btOff = remember(recheck) { bluetoothOff(ctx) }
    val locOff = remember(recheck) { locationOff(ctx) }
    val ready = granted && !btOff && !locOff

    LaunchedEffect(Unit) { vm.disconnect() }
    DisposableEffect(ready) {
        if (ready) vm.startScan()
        onDispose { vm.stopScan() }
    }

    Screen("Nearby units", onBack = onSignOut, backLabel = "Sign out", footer = {
        Secondary("Prepare a truck record without a unit", onClick = onPrepare)
    }) {
        Heading("Key on, then find the box")
        Sub("The unit only runs with the key on. Stand in or beside the cab. Scanning continues while this screen is open.")
        when {
            !granted -> {
                Note(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "RockLogik Connect needs Nearby devices permission to find units."
                     else "Android 7 to 11 only lets an app scan for Bluetooth devices with Location permission. The app does not use your location.", warning = true)
                Primary("Allow") { ask.launch(requiredPermissions()) }
            }
            btOff -> {
                Note("Bluetooth is off.", warning = true)
                Primary("Turn on Bluetooth") { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            }
            locOff -> {
                Note("Location is off. Android needs it on to scan for Bluetooth devices on this version.", warning = true)
                Primary("Open location settings") { enableBt.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            else -> {
                Label("Found")
                if (scanError != null) Note("Scan failed: $scanError", warning = true)
                if (units.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Rl.Accent)
                        Sub("Looking for VISR units…")
                    }
                } else Card {
                    units.forEachIndexed { i, u ->
                        CardRow(first = i == 0, minHeight = 72.dp, modifier = Modifier.clickable { vm.connect(u); onPicked() }) {
                            Glyph.Bars(u.bars, if (u.bars >= 3) Rl.Ok else Rl.Warn)
                            Column(Modifier.weight(1f)) {
                                Text(u.name, fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                                Text("${u.rssi} dBm", color = Rl.Muted, fontSize = 14.sp)
                            }
                            Text("›", fontSize = 24.sp, color = Rl.Ink)
                        }
                    }
                }
                Sub("Strongest first. The serial on the lid label matches the name here.")
            }
        }
    }
}

// -- 3 Unlock -------------------------------------------------------------------------------

@Composable
fun UnlockScreen(vm: ConnectViewModel, onBack: () -> Unit, onUnlocked: () -> Unit) {
    val conn by vm.conn.collectAsState()
    val err by vm.unlockError.collectAsState()
    var key by remember { mutableStateOf("") }
    val name = when (val c = conn) {
        is Conn.Connecting -> c.name; is Conn.Locked -> c.name; is Conn.Unlocking -> c.name
        is Conn.Ready -> c.name; is Conn.Failed -> c.name ?: "unit"; Conn.Idle -> "unit"
    }
    LaunchedEffect(conn) { if (conn is Conn.Ready) onUnlocked() }

    Screen("Unlock $name", onBack = { vm.disconnect(); onBack() }) {
        when (val c = conn) {
            is Conn.Connecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Rl.Accent); Sub("Connecting to ${c.name}…")
            }
            is Conn.Failed -> {
                Note(c.message, warning = true)
                Secondary("Back to nearby units") { vm.disconnect(); onBack() }
            }
            else -> {
                Heading("Enter the key from the lid label")
                Sub("Scanning the label's QR code arrives with ROCKLOGIK-93. Until then, type the 26-character key printed under it, or paste the label text.")
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; vm.clearUnlockError() },
                    label = { Text("Key") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 17.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (err != null) Note(err!!, warning = true)
                val parsed = LidLabel.parse(key)?.key ?: key.trim().uppercase()
                Primary(if (conn is Conn.Unlocking) "Unlocking…" else "Unlock",
                    enabled = LidLabel.isKey(parsed) && conn is Conn.Locked) { vm.unlock(parsed) }
            }
        }
    }
}

// -- 4 Unit ---------------------------------------------------------------------------------

@Composable
fun UnitScreen(vm: ConnectViewModel, onBack: () -> Unit, onStart: () -> Unit, onStatus: () -> Unit, onTerminal: () -> Unit) {
    val conn by vm.conn.collectAsState()
    val status by vm.status.collectAsState()
    DisposableEffect(Unit) { val j = vm.pollStatus(); onDispose { j.cancel() } }
    val c = conn
    if (c !is Conn.Ready) {
        Screen("Unit", onBack = onBack) {
            Note(if (c is Conn.Failed) c.message else "Not connected.", warning = true)
            Secondary("Back to nearby units", onClick = onBack)
        }
        return
    }
    val info = c.info
    Screen(c.name, onBack = { vm.disconnect(); onBack() }, chip = info.track, backLabel = "Disconnect", footer = {
        Primary("Start install", onClick = onStart)
        Secondary("Engineer tools", onClick = onTerminal)
    }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KV("Software", info.swVersion, Modifier.weight(1f)); KV("Protocol", "v${info.protocolVersion}", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KV("Notecard", info.notecardUid ?: "none", Modifier.weight(1f)); KV("Up for", "${info.uptimeS / 60} min", Modifier.weight(1f))
                }
            }
            Label("Install")
            Card {
                val s = status
                val steps = listOf(
                    "Identify the truck" to "Not set", "Photos" to "0 of 4", "Site network" to "Optional",
                    "Status check" to (if (s == null) "Checking…" else if (s.green) "All green" else "${s.greenCount} of ${s.checks.size} green"),
                    "Recce, 15 min" to "Not run", "Record" to "Not sent",
                )
                steps.forEachIndexed { i, (t, st) ->
                    CardRow(first = i == 0, minHeight = 52.dp, modifier = if (i == 3) Modifier.clickable(onClick = onStatus) else Modifier) {
                        Text("${i + 1}", fontWeight = FontWeight.SemiBold, color = Rl.Muted, modifier = Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)))
                        Text(t, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Text(st, fontSize = 14.sp, color = if (i == 3 && s?.green == true) Rl.Ok else Rl.Muted)
                    }
                }
            }
            Sub("Steps can be revisited in any order. Record stays locked until the status check is all green.")
        }
    }
}

@Composable
private fun KV(k: String, v: String, modifier: Modifier) = Column(modifier) {
    Text(k, fontSize = 13.sp, color = Rl.Muted)
    Text(v, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = Mono, maxLines = 1)
}
