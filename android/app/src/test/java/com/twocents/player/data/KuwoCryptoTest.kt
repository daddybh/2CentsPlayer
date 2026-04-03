package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class KuwoCryptoTest {
    @Test
    fun encryptQuery_matchesKnownOfficialValue() {
        val query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=mp3&rid=550531860"

        val encrypted = KuwoCrypto.encryptQuery(query)

        assertEquals(
            "3HxQnWXTNdQ6RbicYxLOyHAu64fVpKoBz43BshH4RFaGBPBi+8dZdGuvz4Hu9TAfA75CH9prKR/wLP/IiYIvJoWxgCvU/gETNIqiGvqcuuscRcbgESVmpm7oNjCqzuWEIZJuWAS1Zw6ZU780O6IfHnbSS74FGIR3",
            encrypted,
        )
    }

    @Test
    fun buildLyricsParams_matchesKnownOfficialValue() {
        val encoded = KuwoCrypto.buildLyricsParams("550531860", includeLyricX = true)

        assertEquals(
            "DBYAHlReXEpRUEAeCgxVEgAORRgLG0MXCRgaCwoRAB5UAwEaBAkEBhwaXxcAHVReSAsMAVEkOj0wJjpZXF9bSlRdWllJAgsGHVFY",
            encoded,
        )
    }

    @Test
    fun decodeLyricResponse_decodesLyricxPayload() {
        val responseBytes = Base64.getDecoder().decode(
            "dHA9Y29udGVudA0KcGF0aD0xDQpzY29yZT01DQpwcm92aWRlcj1hDQpscmNfbGVuZ3RoPTM3DQpjYW5kX2xyY19jb3VudD0wDQpscmN4PTENCnNob3c9MQ0KDQp4nPPMCQsLy8lJDXQFMkI8krISg0uMI40j841CDYPDwtIi3Eqcwg1ywvz9cquMopwLSgJtbQHWgxF2",
        )

        val decoded = KuwoCrypto.decodeLyricResponse(responseBytes, includeLyricX = true)

        assertEquals(
            "[00:01.000]测试歌词\n[00:02.500]第二行",
            decoded,
        )
    }
}
