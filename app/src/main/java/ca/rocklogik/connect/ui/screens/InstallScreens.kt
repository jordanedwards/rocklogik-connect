package ca.rocklogik.connect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.rocklogik.connect.ConnectViewModel
import ca.rocklogik.connect.ConnectViewModel.Conn
import ca.rocklogik.connect.ui.Card
import ca.rocklogik.connect.ui.CardRow
import ca.rocklogik.connect.ui.Glyph
import ca.rocklogik.connect.ui.Heading
import ca.rocklogik.connect.ui.Mono
import ca.rocklogik.connect.ui.Note
import ca.rocklogik.connect.ui.Primary
import ca.rocklogik.connect.ui.Screen
import ca.rocklogik.connect.ui.Secondary
import ca.rocklogik.connect.ui.Sub
import ca.rocklogik.connect.ui.theme.Rl

private fun chipOf(vm: ConnectViewModel): String? = (vm.conn.value as? Conn.Ready)?.name

/** A wireframed step not built yet: its place in the flow, and the ticket that builds it. */
@Composable
fun PendingStep(
    title: String, step: Int?, chip: String?, heading: String, ticket: String,
    onBack: () -> Unit, next: String?, onNext: (() -> Unit)?,
) {
    Screen(title, onBack = onBack, chip = chip, step = step, footer = if (next != null && onNext != null) ({ Primary(next, onClick = onNext) }) else null) {
        Heading(heading)
        Note("This step is wireframed and arrives with $ticket.")
    }
}

// -- 8 Status check -------------------------------------------------------------------------

@Composable
fun StatusScreen(vm: ConnectViewModel, onBack: () -> Unit, onNext: () -> Unit, onTerminal: () -> Unit) {
    val status by vm.status.collectAsState()
    val err by vm.statusError.collectAsState()
    DisposableEffect(Unit) { val j = vm.pollStatus(); onDispose { j.cancel() } }
    Screen("4 of 6 · Status", onBack = onBack, chip = chipOf(vm), step = 4, footer = {
        Primary("Next: recce", onClick = onNext)
        Secondary("Why is a line not green?", onClick = onTerminal)
    }) {
        val s = status
        if (s == null) {
            Sub(err ?: "Asking the unit…")
            return@Screen
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (s.green) Color(0xFFE5F2EA) else Rl.WarnBg).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.green) Glyph.Ok(26.dp) else Glyph.Warn(size = 26.dp)
            Column(Modifier.weight(1f)) {
                Text(if (s.green) "All ${s.checks.size} green" else "${s.greenCount} of ${s.checks.size} green", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(if (s.green) "Safe to close the lid." else "Do not close the lid until every line is green. Live, every 5 s.",
                    fontSize = 13.sp, color = if (s.green) Rl.Ok else Color(0xFF5C3A00))
            }
        }
        if (err != null) Note("Last refresh failed: $err", warning = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Card {
                s.checks.forEachIndexed { i, c ->
                    CardRow(first = i == 0, minHeight = 54.dp) {
                        when (c.state) {
                            "ok" -> Glyph.Ok()
                            "off" -> Glyph.Off()
                            "fail" -> Glyph.Warn(Rl.Fail)
                            else -> Glyph.Warn()
                        }
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(c.detail, fontSize = 13.sp, color = Rl.Muted)
                        }
                        Text(
                            when (c.state) { "ok" -> "OK"; "off" -> "OFF"; "fail" -> "FAIL"; else -> "WAIT" },
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = when (c.state) { "ok" -> Rl.Ok; "off" -> Rl.Muted; "fail" -> Rl.Fail; else -> Rl.Warn },
                        )
                    }
                }
            }
        }
    }
}

// -- 12 Engineer terminal -------------------------------------------------------------------

@Composable
fun TerminalScreen(vm: ConnectViewModel, onBack: () -> Unit) {
    val lines by vm.terminal.collectAsState()
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.animateScrollToItem(lines.size - 1) }
    Column(Modifier.background(Rl.Ground)) {
        Screen("Raw link", onBack = onBack, chip = chipOf(vm)) {
            Note("Engineer tools. Shows what the unit actually sends. Keys and passphrases are redacted on screen and never logged.", warning = true)
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Rl.Night).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lines) { l ->
                    Text(
                        "${l.time} ${if (l.outgoing) "→" else "←"} ${l.text}",
                        fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp,
                        color = when { l.error -> Color(0xFFE58C84); l.outgoing -> Color(0xFFE6A15A); else -> Color(0xFF9FD1AE) },
                    )
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf("status_v1", "info_v1", "config_get_v1", "segments_v1", "notecard_status_v1")) {
                    OutlinedButton(onClick = { vm.sendRaw(m) }, modifier = Modifier.height(40.dp)) { Text(m, fontFamily = Mono, fontSize = 13.sp) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, label = { Text("method or raw JSON") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 14.sp), modifier = Modifier.weight(1f))
                androidx.compose.material3.Button(
                    onClick = { vm.sendRaw(input); input = "" }, enabled = input.isNotBlank(),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Rl.Accent),
                    modifier = Modifier.height(52.dp),
                ) { Text("Send") }
            }
        }
    }
}
