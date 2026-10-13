package ca.rocklogik.connect.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.rocklogik.connect.ui.theme.Rl

/** A screen: dark bar, optional step bar, body, footer of actions. */
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)?,
    chip: String? = null,
    step: Int? = null,
    backLabel: String = "Back",
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Rl.Ground)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(Rl.Ink).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).semantics { contentDescription = backLabel }) {
                    Glyph.Back(Color.White)
                }
            } else {
                Box(Modifier.size(16.dp))
            }
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (chip != null) Chip(chip)
        }
        if (step != null) StepBar(step)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = body,
        )
        if (footer != null) {
            HorizontalDivider(color = Rl.Rule)
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

@Composable
fun Chip(text: String) {
    Text(
        text, color = Rl.OnNight, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.border(1.dp, Color(0xFF6B7176), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** The six install steps; [step] is 1 to 6. */
@Composable
fun StepBar(step: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 1..6) {
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= step) Rl.Accent else Color(0xFFC9CCC6)))
        }
    }
}

@Composable
fun Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Rl.Accent, contentColor = Color.White),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, Rl.Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Rl.Ink),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Rl.Card).border(1.dp, Rl.Rule, RoundedCornerShape(8.dp)),
        content = content,
    )
}

/** One row in a card; draws its own top rule unless it is the first. */
@Composable
fun CardRow(first: Boolean = false, minHeight: Dp = 56.dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    if (!first) HorizontalDivider(color = Rl.RuleSoft)
    Row(
        modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Heading(text: String) = Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Rl.Ink, lineHeight = 28.sp)

@Composable
fun Sub(text: String) = Text(text, fontSize = 15.sp, color = Rl.Muted, lineHeight = 22.sp)

@Composable
fun Label(text: String) = Text(text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Rl.Muted, letterSpacing = 0.5.sp)

/** A tinted note: info (blue) or warning (amber). */
@Composable
fun Note(text: String, warning: Boolean = false) {
    Text(
        text, fontSize = 14.sp, lineHeight = 20.sp,
        color = if (warning) Color(0xFF5C3A00) else Rl.InfoInk,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (warning) Rl.WarnBg else Rl.InfoBg).padding(12.dp),
    )
}

/** Small stroke glyphs drawn in code, so the app carries no icon library. */
object Glyph {
    @Composable
    fun Back(color: Color) = Canvas(Modifier.size(24.dp)) {
        val p = Path().apply { moveTo(size.width * 0.62f, size.height * 0.25f); lineTo(size.width * 0.37f, size.height * 0.5f); lineTo(size.width * 0.62f, size.height * 0.75f) }
        drawPath(p, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    @Composable
    fun Ok(size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(Rl.Ok, radius = w * 0.4f, style = Stroke(2.2.dp.toPx()))
        val p = Path().apply { moveTo(w * 0.33f, w * 0.5f); lineTo(w * 0.46f, w * 0.63f); lineTo(w * 0.68f, w * 0.38f) }
        drawPath(p, Rl.Ok, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    @Composable
    fun Warn(color: Color = Rl.Warn, size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
        val w = this.size.width
        val p = Path().apply { moveTo(w * 0.5f, w * 0.15f); lineTo(w * 0.9f, w * 0.85f); lineTo(w * 0.1f, w * 0.85f); close() }
        drawPath(p, color, style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.5f, w * 0.42f), Offset(w * 0.5f, w * 0.6f), 2.2.dp.toPx(), StrokeCap.Round)
        drawCircle(color, radius = 1.4.dp.toPx(), center = Offset(w * 0.5f, w * 0.72f))
    }

    @Composable
    fun Off(size: Dp = 22.dp) = Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(Rl.Muted, radius = w * 0.4f, style = Stroke(2.dp.toPx()))
        drawLine(Rl.Muted, Offset(w * 0.32f, w * 0.5f), Offset(w * 0.68f, w * 0.5f), 2.dp.toPx(), StrokeCap.Round)
    }

    @Composable
    fun Bars(bars: Int, color: Color) = Canvas(Modifier.size(28.dp)) {
        val w = size.width
        for (i in 0 until 4) {
            val x = w * (0.2f + i * 0.2f)
            val top = w * (0.75f - i * 0.17f)
            drawLine(if (i < bars) color else Color(0xFFC9CCC6), Offset(x, w * 0.85f), Offset(x, top), 2.4.dp.toPx(), StrokeCap.Round)
        }
    }
}

val Mono = FontFamily.Monospace
val ScreenPadding = PaddingValues(16.dp)
