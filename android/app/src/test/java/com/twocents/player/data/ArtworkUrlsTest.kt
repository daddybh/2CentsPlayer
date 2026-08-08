package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkUrlsTest {
    @Test
    fun optimizedArtworkUrl_limitsNeteaseArtworkToSharedDisplaySize() {
        assertEquals(
            "https://p3.music.126.net/cover/album.jpg?param=512y512",
            "https://p3.music.126.net/cover/album.jpg".optimizedArtworkUrl(),
        )
    }

    @Test
    fun optimizedArtworkUrl_replacesExistingNeteaseImageTransform() {
        assertEquals(
            "https://p4.music.126.net/cover/album.jpg?param=320y320",
            "https://p4.music.126.net/cover/album.jpg?param=2000y2000".optimizedArtworkUrl(320),
        )
    }

    @Test
    fun optimizedArtworkUrl_leavesOtherArtworkProvidersUntouched() {
        assertEquals(
            "https://cover.example/album.jpg?quality=90",
            "https://cover.example/album.jpg?quality=90".optimizedArtworkUrl(),
        )
    }
}
