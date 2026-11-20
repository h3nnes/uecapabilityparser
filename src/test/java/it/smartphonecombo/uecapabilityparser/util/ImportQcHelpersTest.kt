package it.smartphonecombo.uecapabilityparser.util

import it.smartphonecombo.uecapabilityparser.util.ImportQcHelpers.parseMultiple0xB826
import it.smartphonecombo.uecapabilityparser.util.ImportQcHelpers.parseMultiple0xBOCD
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ImportQcHelpersTest {
    private val nrPayload = File("src/test/resources/0xB826/input/0xB826-v22-NSA.bin").readBytes()
    private val ltePayload = File("src/test/resources/0xB0CDBin/input/0xB0CD-v41.bin").readBytes()

    private fun qcMessage(item: String, title: String, payload: ByteArray): String {
        val hex = payload.joinToString("") { "%02X".format(it) }
        return """
            |
            |2026 Nov 18  12:53:51.992  [A0]  0x$item  $title
            |Subscription ID = 1
            |Misc ID         = 0
            |Version = 1
            |Num Combos = 100
            |
            |Length: ${payload.size}
            |Header: 00 11 22 33 44 55 66 77 88 99 AA BB
            |Payload: $hex
            |
            |
        """
            .trimMargin()
    }

    @Test
    fun parseMultiple0xB826IgnoresLteLogItems() {
        val expected =
            parseMultiple0xB826(qcMessage("B826", "NR5G RRC Supported CA Combos", nrPayload))
        val actual =
            parseMultiple0xB826(
                qcMessage("B0CD", "LTE RRC Supported CA Combos", ltePayload) +
                    qcMessage("B826", "NR5G RRC Supported CA Combos", nrPayload)
            )

        assertTrue(expected.enDcCombos.isNotEmpty())
        assertEquals(expected.enDcCombos, actual.enDcCombos)
        assertEquals(expected.nrCombos, actual.nrCombos)
    }

    @Test
    fun parseMultiple0xBOCDIgnoresNrLogItems() {
        val expected =
            parseMultiple0xBOCD(qcMessage("B0CD", "LTE RRC Supported CA Combos", ltePayload))
        val actual =
            parseMultiple0xBOCD(
                qcMessage("B826", "NR5G RRC Supported CA Combos", nrPayload) +
                    qcMessage("B0CD", "LTE RRC Supported CA Combos", ltePayload)
            )

        assertTrue(expected.lteCombos.isNotEmpty())
        assertEquals(expected.lteCombos, actual.lteCombos)
    }

    @Test
    fun parseMultiple0xB826FailsWhenOnlyLteLogItemsArePresent() {
        val err =
            assertThrows(IllegalArgumentException::class.java) {
                parseMultiple0xB826(qcMessage("B0CD", "LTE RRC Supported CA Combos", ltePayload))
            }

        assertTrue(err.message.orEmpty().contains("No 0xB826 payload"))
    }

    @Test
    fun parseMultiple0xB826SkipsInvalidPayloads() {
        val expected =
            parseMultiple0xB826(qcMessage("B826", "NR5G RRC Supported CA Combos", nrPayload))
        val invalid =
            ByteArray(64).also {
                it[0] = 0x29
                it[1] = 0x64
            }
        val actual =
            parseMultiple0xB826(
                qcMessage("B826", "NR5G RRC Supported CA Combos", invalid) +
                    qcMessage("B826", "NR5G RRC Supported CA Combos", nrPayload)
            )

        assertEquals(expected.enDcCombos, actual.enDcCombos)
        assertEquals(expected.nrCombos, actual.nrCombos)
    }

    @Test
    fun parseMultiple0xB826FailsWhenOnlyInvalidPayloadsArePresent() {
        val invalid =
            ByteArray(64).also {
                it[0] = 0x29
                it[1] = 0x64
            }

        assertThrows(IllegalArgumentException::class.java) {
            parseMultiple0xB826(qcMessage("B826", "NR5G RRC Supported CA Combos", invalid))
        }
    }
}
