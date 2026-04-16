package eu.kanade.tachiyomi.ui.manga

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaScreenNavigationTest {

    @Test
    fun `source root entry keeps merged rendering`() {
        sourceMangaBypassesMerge(mangaId = 10L, visibleTargetId = 10L) shouldBe false
    }

    @Test
    fun `source child entry bypasses merged rendering`() {
        sourceMangaBypassesMerge(mangaId = 11L, visibleTargetId = 10L) shouldBe true
    }
}
