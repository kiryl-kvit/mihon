package eu.kanade.presentation.browse.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.MangaPreviewSizeUi
import eu.kanade.presentation.manga.components.SharedMangaPreviewSection
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun BrowseMangaPreviewSheet(
    mangaId: Long,
    previewSize: MangaPreviewSizeUi,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasRequestedPreview by rememberSaveable(mangaId) { mutableStateOf(false) }
    val screenModel = object : Screen {
        override val key: ScreenKey = "browse-preview-screen-$mangaId"

        @Composable
        override fun Content() {
            error("Not used")
        }
    }.rememberScreenModel(tag = mangaId.toString()) {
        MangaScreenModel(
            context = context,
            lifecycle = lifecycleOwner.lifecycle,
            mangaId = mangaId,
            isFromSource = true,
        )
    }

    val state by screenModel.state.collectAsStateWithLifecycle()
    val previewState by screenModel.previewState.collectAsStateWithLifecycle()

    LaunchedEffect(state, hasRequestedPreview, previewState.chapterId) {
        val currentState = state as? MangaScreenModel.State.Success ?: return@LaunchedEffect
        if (!hasRequestedPreview && !currentState.isRefreshingData && previewState.chapterId == null) {
            hasRequestedPreview = true
            screenModel.setPreviewExpanded(true)
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        when (val currentState = state) {
            MangaScreenModel.State.Loading -> LoadingScreen()
            is MangaScreenModel.State.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = MaterialTheme.padding.small),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Text(
                        text = currentState.manga.presentationTitle(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                    SharedMangaPreviewSection(
                        state = previewState,
                        size = previewSize,
                        onExpandedChange = screenModel::setPreviewExpanded,
                        onRetry = screenModel::retryPreview,
                        onPageLoad = screenModel::loadPreviewPage,
                        onPageClick = { chapterId, pageIndex ->
                            scope.launch {
                                val chapter = currentState.chapters.firstOrNull {
                                    it.chapter.id == chapterId
                                }?.chapter ?: return@launch
                                openChapter(context, screenModel, chapter, pageIndex)
                            }
                        },
                    )
                }
            }
        }
    }
}

private suspend fun openChapter(
    context: Context,
    screenModel: MangaScreenModel,
    chapter: Chapter,
    pageIndex: Int? = null,
) {
    val visibleMangaId = screenModel.getVisibleMangaId(chapter.mangaId)
    context.startActivity(ReaderActivity.newIntent(context, visibleMangaId, chapter.id, pageIndex))
}
