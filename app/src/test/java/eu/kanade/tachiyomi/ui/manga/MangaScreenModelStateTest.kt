package eu.kanade.tachiyomi.ui.manga

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource

class MangaScreenModelStateTest {

    @Test
    fun `showMergeNotice is true for a member entry opened outside the merged root`() {
        val state = successState(
            manga = manga(id = 2L),
            memberIds = persistentListOf(2L),
            mergeTargetId = 1L,
            mergeGroupMemberIds = persistentListOf(1L, 2L),
        )

        state.isPartOfMerge shouldBe true
        state.isMerged shouldBe false
        state.showMergeNotice shouldBe true
    }

    @Test
    fun `showMergeNotice is false for the merged root entry`() {
        val state = successState(
            manga = manga(id = 1L),
            memberIds = persistentListOf(1L, 2L),
            mergeTargetId = 1L,
            mergeGroupMemberIds = persistentListOf(1L, 2L),
        )

        state.isPartOfMerge shouldBe true
        state.isMerged shouldBe true
        state.showMergeNotice shouldBe false
    }

    @Test
    fun `showMergeNotice is false for a standalone entry`() {
        val state = successState(
            manga = manga(id = 3L),
            memberIds = persistentListOf(3L),
            mergeTargetId = 3L,
            mergeGroupMemberIds = persistentListOf(3L),
        )

        state.isPartOfMerge shouldBe false
        state.isMerged shouldBe false
        state.showMergeNotice shouldBe false
    }

    private fun successState(
        manga: Manga,
        memberIds: kotlinx.collections.immutable.ImmutableList<Long>,
        mergeTargetId: Long,
        mergeGroupMemberIds: kotlinx.collections.immutable.ImmutableList<Long>,
    ): MangaScreenModel.State.Success {
        return MangaScreenModel.State.Success(
            manga = manga,
            source = StubSource(id = manga.source, lang = "en", name = "Test"),
            sourceName = "Test",
            memberIds = memberIds,
            memberTitleById = memberIds.associateWith { "Entry $it" },
            mergedMemberTitles = memberIds.map { "Entry $it" }.toPersistentList(),
            mergeTargetId = mergeTargetId,
            mergeGroupMemberIds = mergeGroupMemberIds,
            isFromSource = false,
            chapters = emptyList(),
            availableScanlators = emptySet(),
            excludedScanlators = emptySet(),
        )
    }

    private fun manga(id: Long): Manga {
        return Manga.create().copy(
            id = id,
            source = 1L,
            title = "Entry $id",
            initialized = true,
            updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
        )
    }
}
