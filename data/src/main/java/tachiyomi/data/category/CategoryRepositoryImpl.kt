package tachiyomi.data.category

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import tachiyomi.data.ActiveProfileProvider
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository

class CategoryRepositoryImpl(
    private val handler: DatabaseHandler,
    private val profileProvider: ActiveProfileProvider,
) : CategoryRepository {

    override suspend fun get(id: Long): Category? {
        return handler.awaitOneOrNull {
            categoriesQueries.getCategory(id, profileProvider.activeProfileId, ::mapCategory)
        }
    }

    override suspend fun getAll(): List<Category> {
        return handler.awaitList {
            categoriesQueries.getCategories(profileProvider.activeProfileId, ::mapCategory)
        }
    }

    override fun getAllAsFlow(): Flow<List<Category>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList { categoriesQueries.getCategories(profileId, ::mapCategory) }
        }
    }

    override suspend fun getCategoriesByMangaId(mangaId: Long): List<Category> {
        return handler.awaitList {
            categoriesQueries.getCategoriesByMangaId(profileProvider.activeProfileId, mangaId, ::mapCategory)
        }
    }

    override fun getCategoriesByMangaIdAsFlow(mangaId: Long): Flow<List<Category>> {
        return profileProvider.activeProfileIdFlow.flatMapLatest { profileId ->
            handler.subscribeToList {
                categoriesQueries.getCategoriesByMangaId(profileId, mangaId, ::mapCategory)
            }
        }
    }

    override suspend fun insert(category: Category) {
        handler.await {
            categoriesQueries.insert(
                profileId = profileProvider.activeProfileId,
                name = category.name,
                order = category.order,
                flags = category.flags,
            )
        }
    }

    override suspend fun updatePartial(update: CategoryUpdate) {
        handler.await {
            updatePartialBlocking(update)
        }
    }

    override suspend fun updatePartial(updates: List<CategoryUpdate>) {
        handler.await(inTransaction = true) {
            for (update in updates) {
                updatePartialBlocking(update)
            }
        }
    }

    private fun Database.updatePartialBlocking(update: CategoryUpdate) {
        categoriesQueries.update(
            name = update.name,
            order = update.order,
            flags = update.flags,
            categoryId = update.id,
            profileId = profileProvider.activeProfileId,
        )
    }

    override suspend fun updateAllFlags(flags: Long?) {
        handler.await {
            categoriesQueries.updateAllFlags(flags, profileProvider.activeProfileId)
        }
    }

    override suspend fun delete(categoryId: Long) {
        handler.await {
            categoriesQueries.delete(
                profileId = profileProvider.activeProfileId,
                categoryId = categoryId,
            )
        }
    }

    private fun mapCategory(
        id: Long,
        name: String,
        order: Long,
        flags: Long,
    ): Category {
        return Category(
            id = id,
            name = name,
            order = order,
            flags = flags,
        )
    }
}
