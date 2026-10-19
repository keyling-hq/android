package com.keyling.hub.layout

import com.keyling.hub.protocol.STROKE_BYTES

/** Strokes flag bit 0: the dongle inverts Shift for this key while the host has Caps Lock on. */
private const val FLAG_CAPS = 0x01

class UntypableTextException(val layout: KeyboardLayout, val count: Int) :
    IllegalArgumentException("$count characters can't be typed with layout ${layout.id}")

/**
 * A host keyboard layout: how to type each character it supports as packed
 * `[modifiers, keycode, flags]` strokes.
 */
class KeyboardLayout(val id: String, val name: String, private val strokes: Map<Int, ByteArray>) {
    val size: Int get() = strokes.size

    fun canType(codePoint: Int): Boolean = codePoint in strokes

    /**
     * Translates [text] to packed strokes. Line breaks (\r\n, \r, \n) become Enter. Throws
     * [UntypableTextException] if any character is missing from the layout, rather than typing
     * a password with characters left out.
     */
    fun translate(text: String): ByteArray {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val out = java.io.ByteArrayOutputStream(normalized.length * STROKE_BYTES)
        var missing = 0
        normalized.codePoints().forEach { c ->
            val s = strokes[c]
            if (s == null) missing++ else out.write(s)
        }
        if (missing > 0) throw UntypableTextException(this, missing)
        return out.toByteArray()
    }

    companion object {
        /**
         * Parses a table written by tools/gen_layouts.py: `# id`, `# name` headers, then
         * `<code point> <flags> <mods>:<key> [<mods>:<key>]` lines, all hex.
         */
        fun parse(text: String): KeyboardLayout {
            var id: String? = null
            var name: String? = null
            val strokes = HashMap<Int, ByteArray>()
            for ((index, raw) in text.lineSequence().withIndex()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    val (key, value) = line.removePrefix("#").trim().split(' ', limit = 2).let {
                        it[0] to it.getOrElse(1) { "" }.trim()
                    }
                    when (key) {
                        "id" -> id = value
                        "name" -> name = value
                    }
                    continue
                }
                val parts = line.split(' ')
                require(parts.size in 3..4) { "line ${index + 1}: expected 1 or 2 strokes" }
                val codePoint = parts[0].toInt(16)
                val caps = parts[1].toInt(16) and FLAG_CAPS != 0
                val keys = parts.drop(2)
                val packed = ByteArray(keys.size * STROKE_BYTES)
                keys.forEachIndexed { i, stroke ->
                    val (mods, key) = stroke.split(':').map { it.toInt(16) }
                    packed[i * STROKE_BYTES] = mods.toByte()
                    packed[i * STROKE_BYTES + 1] = key.toByte()
                    packed[i * STROKE_BYTES + 2] = if (caps && i == keys.lastIndex) FLAG_CAPS.toByte() else 0
                }
                strokes[codePoint] = packed
            }
            val layoutId = requireNotNull(id) { "missing # id" }
            return KeyboardLayout(layoutId, name ?: layoutId, strokes)
        }
    }
}
