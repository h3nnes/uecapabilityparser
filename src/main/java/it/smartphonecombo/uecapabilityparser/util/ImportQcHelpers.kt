package it.smartphonecombo.uecapabilityparser.util

import it.smartphonecombo.uecapabilityparser.extension.decodeHex
import it.smartphonecombo.uecapabilityparser.extension.mutableListWithCapacity
import it.smartphonecombo.uecapabilityparser.extension.preformatHex
import it.smartphonecombo.uecapabilityparser.extension.readUShortLE
import it.smartphonecombo.uecapabilityparser.extension.skipBytes
import it.smartphonecombo.uecapabilityparser.extension.toInputSource
import it.smartphonecombo.uecapabilityparser.importer.Import0xB0CDBin
import it.smartphonecombo.uecapabilityparser.importer.Import0xB826
import it.smartphonecombo.uecapabilityparser.io.IOUtils.echoSafe
import it.smartphonecombo.uecapabilityparser.model.Capabilities
import java.io.InputStream

object ImportQcHelpers {
    private val debug
        get() = Config.getOrDefault("debug", "false").toBoolean()

    private val regexEmptyLine = Regex("^\\s*$", RegexOption.MULTILINE)

    private val regexEmptyOrCommentedLine = Regex("^(#.*)?\\s*$", RegexOption.MULTILINE)

    private fun String.emptyLineIndex(): Int {
        return regexEmptyLine.find(this)?.range?.first ?: this.length
    }

    private fun String.notHexLineIndex(): Int {
        val res = indexOfFirst {
            if (it.isDigit() || it.isWhitespace()) {
                false
            } else if (it.isLetter()) {
                it != 'x' && it !in 'A'..'F' && it !in 'a'..'f'
            } else {
                it != ','
            }
        }
        return if (res == -1) length else res
    }

    /** Matches the header of a QCAT message, e.g. "0xB826 NR5G RRC Supported CA Combos". */
    private val regexQcItemHeader = Regex("""0x(B0CD|B826) {2}""")

    /**
     * A hex payload extracted from the input and, when the input is a QCAT text dump, the id of the
     * log item it belongs to.
     */
    private class HexPayload(val hex: String, val qcItem: String?)

    /**
     * Return the id of the last QCAT log item header in this text, if any.
     *
     * It's used to tell apart the log items of a QCAT text dump that contains more than one type
     * (e.g. 0xB0CD and 0xB826).
     */
    private fun String.lastQcItem(): String? {
        return regexQcItemHeader.findAll(this).lastOrNull()?.groupValues?.get(1)
    }

    private fun splitHex(input: String): List<HexPayload> {
        // Try payload Prefix
        var splitByPrefix = input.split("Payload:")
        if (splitByPrefix.size == 1) {
            // Try scat Prefix
            splitByPrefix = input.split("CA Combos Raw:")
        }
        if (splitByPrefix.size == 1) {
            // Try 0x9801 Prefix
            splitByPrefix = input.split("0x9801")
            if (splitByPrefix.size > 1) {
                // Re-add 0x9801
                splitByPrefix = splitByPrefix.map { "0x9801$it" }
            }
        }

        return if (splitByPrefix.size > 1) {
            splitByPrefix.drop(1).mapIndexed { index, it ->
                // The text before a payload contains the header of the message it belongs to
                HexPayload(
                    hex = it.substring(0, minOf(it.emptyLineIndex(), it.notHexLineIndex())),
                    qcItem = splitByPrefix[index].lastQcItem(),
                )
            }
        } else {
            input.split(regexEmptyOrCommentedLine).map { HexPayload(it, null) }
        }
    }

    fun parseMultiple0xB826(input: String): Capabilities {
        return parseMultipleQcDiag(input, false)
    }

    fun parseMultiple0xBOCD(input: String): Capabilities {
        return parseMultipleQcDiag(input, true)
    }

    private fun parseMultipleQcDiag(input: String, lte: Boolean): Capabilities {
        val qcItem = if (lte) "B0CD" else "B826"
        val payloads = splitHex(input)
        // A QCAT text dump can contain more than one log item type (e.g. 0xB0CD and 0xB826
        // packets of the same capture): only parse the payloads of the requested item, the
        // others would be decoded as garbage.
        val inputArray =
            payloads.filter { payload -> payload.qcItem == null || payload.qcItem == qcItem }
        val otherItems = payloads.size - inputArray.size
        val list = mutableListWithCapacity<Capabilities>(inputArray.size)
        val importer = if (lte) Import0xB0CDBin else Import0xB826
        val errors = mutableListWithCapacity<IllegalArgumentException>(0)
        for (payload in inputArray) {
            if (payload.hex.isBlank()) continue
            try {
                val inputStream = payload.hex.preformatHex().decodeHex().toInputSource()
                list.add(importer.parse(inputStream))
            } catch (err: IllegalArgumentException) {
                errors.add(err)
            }
        }

        if (list.isEmpty() && errors.isNotEmpty()) {
            // Nothing could be parsed, report why instead of returning an empty result.
            throw IllegalArgumentException(errors.first().message, errors.first())
        }

        if (list.isEmpty() && otherItems > 0) {
            throw IllegalArgumentException(
                "No 0x$qcItem payload found, the input only contains other log items"
            )
        }

        if (errors.isNotEmpty()) {
            // A capture can contain packets that aren't valid capabilities
            // (e.g. encrypted OrangeIP secure logs): skip them, keep the rest.
            echoSafe(
                "Warning: skipped ${errors.size} invalid " +
                    "${if (lte) "0xB0CD" else "0xB826"} payload(s): ${errors.first().message}",
                err = true,
            )
        }

        val lteCombos = if (lte) list.flatMap(Capabilities::lteCombos) else emptyList()
        val enDcCombos = if (!lte) list.flatMap(Capabilities::enDcCombos) else emptyList()
        val nrCombos = if (!lte) list.flatMap(Capabilities::nrCombos) else emptyList()
        val nrDcCombos = if (!lte) list.flatMap(Capabilities::nrDcCombos) else emptyList()

        val metadataList = list.map(Capabilities::metadata)

        val capabilities =
            Capabilities(
                lteCombos = lteCombos,
                enDcCombos = enDcCombos,
                nrCombos = nrCombos,
                nrDcCombos = nrDcCombos,
                metadata = metadataList.firstOrNull() ?: mutableMapOf(),
            )

        if (metadataList.size > 1) {
            val keys = metadataList.flatMap { it.keys }.distinct()

            // Add missing keys to maps
            metadataList.forEach { map -> keys.filter { it !in map }.forEach { map[it] = "" } }

            // Add additional metadata
            metadataList.drop(1).forEach { map ->
                map.forEach { (key, value) -> capabilities.addMetadata(key, value) }
            }
        }

        return capabilities
    }

    /**
     * Return the content size of a Qualcomm diag packet. Also set logItem in [capabilities] if
     * available.
     *
     * It supports qualcomm diag packets with or without header.
     *
     * Note: it advances the [stream].
     */
    fun getQcDiagLogSize(stream: InputStream, streamLength: Int, capabilities: Capabilities): Int {
        // Set mark for future reset
        stream.mark(32)

        // Try to read fileSize from the header
        var fileSize = stream.readUShortLE()

        // if fileSize = stream length 0xB826/0xB0CD has a standard header
        if (fileSize != streamLength) {
            // check if there's an additional header (0x9801 + 8 bytes + file length + header)
            if (fileSize == 0x0198) {
                stream.skipBytes(10)
                fileSize = stream.readUShortLE()
            } else {
                // header missing, logSize is stream length
                stream.reset()
                return streamLength
            }
        }

        val logItem = stream.readUShortLE().toString(16).uppercase()
        capabilities.setMetadata("logItem", "0x$logItem")
        if (debug) {
            echoSafe("Log Item: 0x$logItem")
        }
        // Skip the rest of the header
        stream.skipBytes(8)
        return fileSize
    }
}
