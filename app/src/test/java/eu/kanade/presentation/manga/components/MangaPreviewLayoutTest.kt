package eu.kanade.presentation.manga.components

import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaPreviewLayoutTest {

    @Test
    fun `preview sizes use a spacing aware unified sizing model`() {
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.SMALL, 320.dp) shouldBe 3
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.MEDIUM, 320.dp) shouldBe 2
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.LARGE, 320.dp) shouldBe 2
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.EXTRA_LARGE, 320.dp) shouldBe 1
    }

    @Test
    fun `browse sheet width keeps medium and large visually distinct`() {
        val browseSheetContentWidth = 428.dp

        mangaPreviewGridColumnCount(MangaPreviewSizeUi.SMALL, browseSheetContentWidth) shouldBe 4
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.MEDIUM, browseSheetContentWidth) shouldBe 3
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.LARGE, browseSheetContentWidth) shouldBe 2
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.EXTRA_LARGE, browseSheetContentWidth) shouldBe 1
    }

    @Test
    fun `extra large preview remains a focused mode on wider layouts`() {
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.EXTRA_LARGE, 560.dp) shouldBe 2
        mangaPreviewGridColumnCount(MangaPreviewSizeUi.EXTRA_LARGE, 840.dp) shouldBe 3
    }

    @Test
    fun `preview state treats short chapters as loaded content`() {
        val previewState = MangaScreenModel.MangaPreviewState(
            chapterId = 1L,
            pages = listOf(
                MangaScreenModel.PreviewPage(ReaderPage(0)),
                MangaScreenModel.PreviewPage(ReaderPage(1)),
                MangaScreenModel.PreviewPage(ReaderPage(2)),
            ),
            pageCount = 5,
        )

        previewState.hasLoadedContent shouldBe true
    }
}
