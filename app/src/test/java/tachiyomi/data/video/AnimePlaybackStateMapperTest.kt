package tachiyomi.data.anime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnimePlaybackStateMapperTest {

    @Test
    fun `mapState maps playback row fields`() {
        val state = AnimePlaybackStateMapper.mapState(
            id = 1L,
            profileId = 2L,
            episodeId = 3L,
            positionMs = 4_000L,
            durationMs = 30_000L,
            completed = true,
            lastWatchedAt = 5_000L,
        )

        state.episodeId shouldBe 3L
        state.positionMs shouldBe 4_000L
        state.durationMs shouldBe 30_000L
        state.completed shouldBe true
        state.lastWatchedAt shouldBe 5_000L
    }
}
