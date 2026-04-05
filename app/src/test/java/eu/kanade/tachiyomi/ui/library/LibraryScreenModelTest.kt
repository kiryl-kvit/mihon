package eu.kanade.tachiyomi.ui.library

import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroupType
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.effectiveLibrarySort
import tachiyomi.domain.manga.model.Manga
import kotlin.random.Random

class LibraryScreenModelTest {

    @Test
    fun `grouped library pages update when global sort changes without category updates`() = runTest {
        val libraryData = MutableStateFlow(
            LibraryScreenModel.LibraryData(
                isInitialized = true,
                showSystemCategory = true,
                categories = listOf(Category(id = 0L, name = "", order = 0L, flags = 0L)),
                favorites = listOf(
                    libraryItem(id = 1L, title = "B", dateAdded = 2L),
                    libraryItem(id = 2L, title = "A", dateAdded = 1L),
                ),
            ),
        )
        val groupType = MutableStateFlow(LibraryGroupType.Category)
        val sortingMode = MutableStateFlow(LibrarySort.default)
        val randomSortSeed = MutableStateFlow(0)
        val emissions = mutableListOf<Pair<LibraryGroupType, List<LibraryPage>>>()

        val job = launch {
            observeGroupedLibraryPages(
                libraryData = libraryData,
                groupType = groupType,
                sortingMode = sortingMode,
                randomSortSeed = randomSortSeed,
                applyGrouping = { data, currentGroupType ->
                    data.favorites.applyGrouping(data.categories, data.showSystemCategory, currentGroupType)
                },
                applySort = { pages, data, currentGroupType, currentSort, currentSeed ->
                    pages.applySortForTest(
                        favoritesById = data.favoritesById,
                        groupType = currentGroupType,
                        globalSort = currentSort,
                        randomSortSeed = currentSeed,
                    )
                },
            ).take(2).toList(emissions)
        }

        advanceUntilIdle()

        emissions shouldHaveSize 1
        emissions.single().second.single().itemIds shouldBe listOf(2L, 1L)

        sortingMode.value = LibrarySort(LibrarySort.Type.DateAdded, LibrarySort.Direction.Descending)
        advanceUntilIdle()

        emissions shouldHaveSize 2
        emissions.last().second.single().itemIds shouldBe listOf(1L, 2L)

        job.cancel()
    }
}

private fun List<LibraryItem>.applyGrouping(
    categories: List<Category>,
    showSystemCategory: Boolean,
    groupType: LibraryGroupType,
): List<LibraryPage> {
    val visibleCategories = categories.filter { showSystemCategory || !it.isSystemCategory }
    return when (groupType) {
        LibraryGroupType.Category -> {
            visibleCategories.map { category ->
                LibraryPage(
                    id = "category:${category.id}",
                    primaryTab = LibraryPageTab(
                        id = "category:${category.id}",
                        title = category.name,
                        category = category,
                    ),
                    category = category,
                    itemIds = filter { category.id in it.libraryManga.categories }.map(LibraryItem::id),
                )
            }
        }
        else -> error("Test only covers category grouping")
    }
}

private fun List<LibraryPage>.applySortForTest(
    favoritesById: Map<Long, LibraryItem>,
    groupType: LibraryGroupType,
    globalSort: LibrarySort,
    randomSortSeed: Int,
): List<LibraryPage> {
    return map { page ->
        val sort = if (groupType == LibraryGroupType.Category) {
            page.category.effectiveLibrarySort(globalSort)
        } else {
            globalSort
        }

        if (sort.type == LibrarySort.Type.Random) {
            return@map page.copy(itemIds = page.itemIds.shuffled(Random(randomSortSeed)))
        }

        val comparator = Comparator<LibraryItem> { manga1, manga2 ->
            when (sort.type) {
                LibrarySort.Type.Alphabetical -> manga1.libraryManga.manga.title.compareTo(
                    manga2.libraryManga.manga.title,
                )
                LibrarySort.Type.DateAdded -> manga1.libraryManga.manga.dateAdded.compareTo(
                    manga2.libraryManga.manga.dateAdded,
                )
                else -> 0
            }
        }.let { if (sort.isAscending) it else it.reversed() }

        page.copy(itemIds = page.itemIds.mapNotNull(favoritesById::get).sortedWith(comparator).map(LibraryItem::id))
    }
}

private fun libraryItem(id: Long, title: String, dateAdded: Long): LibraryItem {
    return LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(
                id = id,
                source = 1L,
                favorite = true,
                title = title,
                dateAdded = dateAdded,
                updateStrategy = UpdateStrategy.ALWAYS_UPDATE,
            ),
            categories = listOf(0L),
            totalChapters = 0L,
            readCount = 0L,
            bookmarkCount = 0L,
            latestUpload = 0L,
            chapterFetchedAt = 0L,
            lastRead = 0L,
        ),
        sourceName = "Source",
    )
}
