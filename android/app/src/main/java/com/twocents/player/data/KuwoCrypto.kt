package com.twocents.player.data

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.floor

object KuwoCrypto {
    private val gb18030: Charset = Charset.forName("GB18030")

    fun encryptQuery(query: String): String {
        return Base64.getEncoder().encodeToString(encrypt(query.toByteArray(Charsets.UTF_8)))
    }

    fun buildLyricsParams(
        musicId: String,
        includeLyricX: Boolean,
    ): String {
        val params = buildString {
            append("user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_")
            append(musicId)
            if (includeLyricX) {
                append("&lrcx=1")
            }
        }
        return Base64.getEncoder().encodeToString(xorEncrypt(params.toByteArray(Charsets.UTF_8), LYRIC_KEY))
    }

    fun decodeLyricResponse(
        payload: ByteArray,
        includeLyricX: Boolean,
    ): String {
        if (!payload.decodeToString().startsWith("tp=content")) return ""

        val separator = "\r\n\r\n".toByteArray(Charsets.UTF_8)
        val bodyStart = payload.indexOfSubArray(separator)
        if (bodyStart < 0) return ""

        val compressed = payload.copyOfRange(bodyStart + separator.size, payload.size)
        val inflated = inflate(compressed)
        if (inflated.isEmpty()) return ""

        if (!includeLyricX) {
            return inflated.toString(gb18030)
        }

        val xorPayload = Base64.getDecoder().decode(inflated.toString(Charsets.UTF_8))
        return xorEncrypt(xorPayload, LYRIC_KEY).toString(gb18030)
    }

    fun convertRawLrc(rawLrc: String): String {
        if (rawLrc.isBlank()) return ""

        val output = mutableListOf<String>()
        val lines = rawLrc.split("\r\n", "\n", "\r")
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            val lineMatch = lineRegex.matchEntire(line)
            if (lineMatch == null) {
                output += line
                index += 1
                continue
            }

            val timestamp = lineMatch.groupValues[1]
            val payload = lineMatch.groupValues[2]
            if (payload.replace("<0,0>", "").isBlank()) {
                index += 1
                continue
            }
            if (payload.startsWith("<0,0>") && chineseRegex.containsMatchIn(payload)) {
                index += 1
                continue
            }

            val words = wordRegex.findAll(payload).toList()
            val lyric = if (words.isEmpty()) {
                payload.replace("<0,0>", "").trim()
            } else {
                words.joinToString(separator = "") { it.groupValues[3] }
            }

            var translation = ""
            if (index + 1 < lines.size) {
                val nextMatch = lineRegex.matchEntire(lines[index + 1])
                val nextPayload = nextMatch?.groupValues?.getOrNull(2).orEmpty()
                if (nextPayload.startsWith("<0,0>") && chineseRegex.containsMatchIn(nextPayload)) {
                    translation = nextPayload.replace("<0,0>", "").trim()
                    index += 1
                }
            }

            output += "[$timestamp]$lyric"
            if (translation.isNotBlank()) {
                output += "[$timestamp]$translation"
            }
            index += 1
        }

        return output.joinToString(separator = "\n")
    }

    fun formatLyricTime(milliseconds: Long): String {
        val safeMilliseconds = milliseconds.coerceAtLeast(0L)
        val totalSeconds = safeMilliseconds / 1000.0
        val minutes = floor(totalSeconds / 60).toInt()
        val seconds = floor(totalSeconds % 60).toInt()
        val fraction = (safeMilliseconds % 1000L).toInt()
        return "[%02d:%02d.%03d]".format(minutes, seconds, fraction)
    }

    private fun encrypt(message: ByteArray): ByteArray {
        return crypt(
            message = message,
            key = SONG_KEY,
            mode = CryptMode.ENCRYPT,
        )
    }

    private fun crypt(
        message: ByteArray,
        key: ByteArray,
        mode: CryptMode,
    ): ByteArray {
        var keyLong = 0L
        for (index in 0 until 8) {
            keyLong = keyLong or ((key[index].toLong() and 0xffL) shl (index * 8))
        }

        val subKeys = createSubKeys(keyLong, mode)
        val blockCount = message.size / 8
        val outputBlocks = LongArray((1 + 8 * (blockCount + 1)) / 8)

        for (blockIndex in 0 until blockCount) {
            var value = 0L
            for (byteIndex in 0 until 8) {
                value = value or ((message[blockIndex * 8 + byteIndex].toLong() and 0xffL) shl (byteIndex * 8))
            }
            outputBlocks[blockIndex] = des64(subKeys, value)
        }

        val remainder = message.copyOfRange(blockCount * 8, message.size)
        var tailValue = 0L
        remainder.forEachIndexed { index, byte ->
            tailValue = tailValue or ((byte.toLong() and 0xffL) shl (index * 8))
        }
        if (remainder.isNotEmpty() || mode == CryptMode.ENCRYPT) {
            outputBlocks[blockCount] = des64(subKeys, tailValue)
        }

        return ByteArray(outputBlocks.size * 8).also { output ->
            var offset = 0
            outputBlocks.forEach { block ->
                for (index in 0 until 8) {
                    output[offset] = ((block ushr (index * 8)) and 0xffL).toByte()
                    offset += 1
                }
            }
        }
    }

    private fun createSubKeys(
        input: Long,
        mode: CryptMode,
    ): LongArray {
        var transformed = bitTransform(ARRAY_PC1, 56, input)
        val subKeys = LongArray(16)
        for (index in 0 until 16) {
            val shift = ARRAY_LS[index]
            val mask = ARRAY_LS_MASK[shift]
            val shiftedLeft = (transformed and mask) shl (28 - shift)
            val shiftedRight = (transformed and mask.inv()) ushr shift
            transformed = shiftedLeft or shiftedRight
            subKeys[index] = bitTransform(ARRAY_PC2, 64, transformed)
        }
        if (mode == CryptMode.DECRYPT) {
            for (index in 0 until 8) {
                val reverseIndex = 15 - index
                val temp = subKeys[index]
                subKeys[index] = subKeys[reverseIndex]
                subKeys[reverseIndex] = temp
            }
        }
        return subKeys
    }

    private fun des64(
        subKeys: LongArray,
        value: Long,
    ): Long {
        val byteBuffer = IntArray(8)
        val initial = bitTransform(ARRAY_IP2, 64, value)
        var left = initial and MASK_32
        var right = (initial ushr 32) and MASK_32

        for (round in 0 until 16) {
            var transformed = bitTransform(ARRAY_E, 64, right) xor subKeys[round]
            for (index in 0 until 8) {
                byteBuffer[index] = ((transformed ushr (index * 8)) and 0xffL).toInt()
            }

            var sBoxOutput = 0L
            for (boxIndex in 7 downTo 0) {
                sBoxOutput = (sBoxOutput shl 4) or (MATRIX_N_S_BOX[boxIndex][byteBuffer[boxIndex]].toLong() and 0x0fL)
            }

            val permuted = bitTransform(ARRAY_P, 32, sBoxOutput)
            val nextLeft = right
            val nextRight = (left xor permuted) and MASK_32
            left = nextLeft
            right = nextRight
        }

        val preOutput = ((left shl 32) and -0x100000000L) or (right and MASK_32)
        return bitTransform(ARRAY_IP1, 64, preOutput)
    }

    private fun bitTransform(
        indexes: IntArray,
        count: Int,
        value: Long,
    ): Long {
        var transformed = 0L
        for (index in 0 until count) {
            val sourceIndex = indexes[index]
            if (sourceIndex < 0) continue
            if (value and ARRAY_MASK[sourceIndex] == 0L) continue
            transformed = transformed or ARRAY_MASK[index]
        }
        return transformed
    }

    private fun xorEncrypt(
        data: ByteArray,
        key: ByteArray,
    ): ByteArray {
        return ByteArray(data.size) { index ->
            (data[index].toInt() xor key[index % key.size].toInt()).toByte()
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        return try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count <= 0 && inflater.needsInput()) break
                if (count > 0) {
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            inflater.end()
            output.close()
        }
    }

    private fun ByteArray.indexOfSubArray(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) return -1
        for (start in 0..(size - target.size)) {
            var matches = true
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private enum class CryptMode {
        ENCRYPT,
        DECRYPT,
    }

    private val lineRegex = Regex("""^\[(\d{2}:\d{2}\.\d{3})](.*)$""")
    private val wordRegex = Regex("""<(-?\d+),(-?\d+)>([^<]*)""")
    private val chineseRegex = Regex("""[\u4e00-\u9fa5]""")

    private const val MASK_32 = 0xffffffffL
    private val SONG_KEY = "ylzsxkwm".toByteArray(Charsets.UTF_8)
    private val LYRIC_KEY = "yeelion".toByteArray(Charsets.UTF_8)
    private val ARRAY_LS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val ARRAY_LS_MASK = longArrayOf(0L, 0x100001L, 0x300003L)
    private val ARRAY_E = intArrayOf(31, 0, 1, 2, 3, 4, -1, -1, 3, 4, 5, 6, 7, 8, -1, -1, 7, 8, 9, 10, 11, 12, -1, -1, 11, 12, 13, 14, 15, 16, -1, -1, 15, 16, 17, 18, 19, 20, -1, -1, 19, 20, 21, 22, 23, 24, -1, -1, 23, 24, 25, 26, 27, 28, -1, -1, 27, 28, 29, 30, 31, 30, -1, -1)
    private val ARRAY_IP1 = intArrayOf(39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24)
    private val ARRAY_IP2 = intArrayOf(57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3, 61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7, 56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6)
    private val ARRAY_MASK = LongArray(64) { 1L shl it }
    private val ARRAY_P = intArrayOf(15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9, 1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24)
    private val ARRAY_PC1 = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)
    private val ARRAY_PC2 = intArrayOf(13, 16, 10, 23, 0, 4, -1, -1, 2, 27, 14, 5, 20, 9, -1, -1, 22, 18, 11, 3, 25, 7, -1, -1, 15, 6, 26, 19, 12, 1, -1, -1, 40, 51, 30, 36, 46, 54, -1, -1, 29, 39, 50, 44, 32, 47, -1, -1, 43, 48, 38, 55, 33, 52, -1, -1, 45, 41, 49, 35, 28, 31, -1, -1)
    private val MATRIX_N_S_BOX = arrayOf(
        intArrayOf(14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10, 1, 8, 12, 7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3, 6, 15, 12, 6, 11, 2, 9, 5, 0, 4, 2, 11, 14, 1, 7, 8, 13),
        intArrayOf(15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3, 14, 11, 13, 6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4, 11, 14, 13, 8, 0, 6, 2, 15, 9, 5, 7, 1, 10, 12, 14, 2, 5, 9),
        intArrayOf(10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10, 14, 9, 7, 12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15, 2, 7, 10, 8, 15, 4, 9, 11, 5, 9, 0, 14, 3, 10, 7, 1, 12),
        intArrayOf(7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2, 3, 0, 5, 14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14, 2, 8, 15, 7, 4, 15, 1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14),
        intArrayOf(2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3, 1, 11, 15, 5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8, 5, 11, 8, 0, 15, 7, 14, 9, 4, 12, 7, 10, 9, 1, 13, 6, 3),
        intArrayOf(12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13, 15, 5, 4, 10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3, 8, 15, 3, 1, 14, 12, 5, 11, 0, 2, 12, 14, 7, 5, 10, 8, 13),
        intArrayOf(4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5, 14, 13, 7, 8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10, 8, 3, 0, 11, 3, 5, 9, 4, 15, 2, 7, 8, 12, 15, 10, 7, 6, 12),
        intArrayOf(13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3, 4, 1, 14, 13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9, 6, 15, 1, 5, 12, 3, 10, 14, 5, 8, 7, 11, 0, 4, 13, 2, 11),
    )
}
