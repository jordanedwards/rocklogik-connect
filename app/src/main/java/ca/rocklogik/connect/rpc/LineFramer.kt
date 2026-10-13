package ca.rocklogik.connect.rpc

import java.io.ByteArrayOutputStream

/**
 * Newline framing for the provisioning protocol (docs/protocol.md section 1).
 *
 * Bytes arrive in notifications of any size; a message may span many of them and one may
 * carry the end of a message and the start of the next. [push] returns every complete line.
 * A line longer than [maxLine] is discarded up to its newline and reported as [Overflow].
 */
class LineFramer(private val maxLine: Int = 16_384) {
    sealed interface Item
    data class Line(val text: String) : Item
    data object Overflow : Item

    private val buf = ByteArrayOutputStream()
    private var discarding = false

    fun push(bytes: ByteArray): List<Item> {
        val out = mutableListOf<Item>()
        for (b in bytes) {
            if (b == NL) {
                if (discarding) {
                    discarding = false
                } else {
                    val text = buf.toString(Charsets.UTF_8.name())
                    if (text.isNotBlank()) out += Line(text)
                }
                buf.reset()
            } else if (!discarding) {
                buf.write(b.toInt())
                if (buf.size() > maxLine) {
                    buf.reset()
                    discarding = true
                    out += Overflow
                }
            }
        }
        return out
    }

    fun reset() {
        buf.reset()
        discarding = false
    }

    private companion object {
        const val NL: Byte = 0x0A
    }
}
