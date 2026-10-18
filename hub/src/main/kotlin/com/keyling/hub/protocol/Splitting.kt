package com.keyling.hub.protocol

/** Largest UTF-8 text in one Text request. */
const val MAX_TEXT_BYTES = 1023

/** Size of one packed `[modifiers, keycode, flags]` entry in a Strokes request. */
const val STROKE_BYTES = 3

/** Largest packed strokes in one Strokes request. */
const val MAX_STROKES_BYTES = 1020

/** Splits [text] into pieces of at most [maxBytes] UTF-8 bytes, only between code points. */
fun splitText(text: String, maxBytes: Int = MAX_TEXT_BYTES): List<String> {
    require(maxBytes >= 4) { "maxBytes must fit any code point" }
    val pieces = mutableListOf<String>()
    var start = 0
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        val length = utf8Length(codePoint)
        if (bytes + length > maxBytes) {
            pieces += text.substring(start, i)
            start = i
            bytes = 0
        }
        bytes += length
        i += Character.charCount(codePoint)
    }
    if (start < text.length) pieces += text.substring(start)
    return pieces
}

/** Splits packed [strokes] into pieces of at most [maxBytes], only between entries. */
fun splitStrokes(strokes: ByteArray, maxBytes: Int = MAX_STROKES_BYTES): List<ByteArray> {
    require(strokes.size % STROKE_BYTES == 0) { "strokes must be whole $STROKE_BYTES-byte entries" }
    val step = maxBytes - maxBytes % STROKE_BYTES
    require(step > 0) { "maxBytes must fit one stroke" }
    return (strokes.indices step step).map { strokes.copyOfRange(it, minOf(it + step, strokes.size)) }
}

private fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}
