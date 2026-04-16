package eu.kanade.tachiyomi.ui.anime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimeScreenNavigationTest {

    @Test
    fun `source root entry keeps merged rendering`() {
        sourceAnimeBypassesMerge(animeId = 10L, visibleTargetId = 10L) shouldBe false
    }

    @Test
    fun `source child entry bypasses merged rendering`() {
        sourceAnimeBypassesMerge(animeId = 11L, visibleTargetId = 10L) shouldBe true
    }
}
