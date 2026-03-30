package eu.kanade.tachiyomi.util.chapter

import android.content.Context
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.manga.ChapterList
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType

class ChapterGetNextUnreadTest {

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        every { context.getString(any()) } returns ""
        Injekt.addSingleton(fullType<BasePreferences>(), BasePreferences(context, InMemoryPreferenceStore()))
    }

    @Test
    fun `returns oldest unread chapter for descending sorted chapter items`() {
        val manga = Manga.create().copy(id = 1L)
        val source = mockk<Source>()
        val chapters = listOf(
            chapterItem(id = 105, manga = manga, source = source, sourceOrder = 0, read = false),
            chapterItem(id = 104, manga = manga, source = source, sourceOrder = 1, read = false),
            chapterItem(id = 103, manga = manga, source = source, sourceOrder = 2, read = true),
        )

        chapters.getNextUnread(manga)?.id shouldBe 104L
    }

    private fun chapterItem(
        id: Long,
        manga: Manga,
        source: Source,
        sourceOrder: Long,
        read: Boolean,
    ): ChapterList.Item {
        return ChapterList.Item(
            chapter = Chapter.create().copy(
                id = id,
                mangaId = manga.id,
                sourceOrder = sourceOrder,
                read = read,
                name = "Chapter $id",
                url = "/chapter/$id",
            ),
            manga = manga,
            source = source,
            downloadState = Download.State.NOT_DOWNLOADED,
            downloadProgress = 0,
        )
    }
}
