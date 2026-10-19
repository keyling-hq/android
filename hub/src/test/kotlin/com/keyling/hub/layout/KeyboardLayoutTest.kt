package com.keyling.hub.layout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardLayoutTest {
    private val table = """
        # id xx-XX
        # name Test
        # source test
        000a 0 00:28
        0041 1 02:04
        0061 1 00:04
        0040 0 40:14
        00e1 1 00:2f 00:04
    """.trimIndent()

    private val layout = KeyboardLayout.parse(table)

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun parsesHeaderAndEntries() {
        assertEquals("xx-XX", layout.id)
        assertEquals("Test", layout.name)
        assertEquals(5, layout.size)
    }

    @Test
    fun translatesWithCapsFlagOnLetters() {
        assertArrayEquals(bytes(0x02, 0x04, 1, 0x00, 0x04, 1, 0x40, 0x14, 0), layout.translate("Aa@"))
    }

    @Test
    fun deadKeyStrokeHasNoCapsFlag() {
        assertArrayEquals(bytes(0x00, 0x2f, 0, 0x00, 0x04, 1), layout.translate("á"))
    }

    @Test
    fun lineBreaksBecomeEnter() {
        assertArrayEquals(bytes(0x00, 0x28, 0, 0x00, 0x28, 0, 0x00, 0x28, 0), layout.translate("\r\n\n\r"))
    }

    @Test
    fun refusesUntypableText() {
        val e = runCatching { layout.translate("a€b🔑") }.exceptionOrNull()
        assertTrue(e is UntypableTextException)
        assertEquals(3, (e as UntypableTextException).count)
    }

    @Test
    fun shippedTablesParse() {
        val files = File("src/main/assets/layouts").listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        assertTrue("no layout tables found", files.size >= 2)
        val layouts = files.map { KeyboardLayout.parse(it.readText()) }
        assertEquals(layouts.size, layouts.map { it.id.lowercase() }.toSet().size)
        for (l in layouts) {
            assertTrue("${l.id} can't type Enter, Tab and space", "\n\t ".all { l.canType(it.code) })
        }
        val es = layouts.single { it.id == "es-419" }
        assertTrue("ñ@¿".codePoints().allMatch(es::canType))
    }
}
