package eu.kanade.presentation.browse.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaPreviewContent
import eu.kanade.presentation.manga.components.MangaPreviewSizeUi
import eu.kanade.tachiyomi.ui.manga.MangaScreenModel
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.coroutines.launch
import mihon.feature.profiles.core.ProfileManager
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.presentationTitle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun BrowseMangaPreviewSheet(
    mangaId: Long,
    previewSize: MangaPreviewSizeUi,
    onLibraryAction: (Manga) -> Unit,
    onMergeAction: (Manga) -> Unit,
    onOpenManga: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val profileManager = remember { Injekt.get<ProfileManager>() }
    val activeProfile by profileManager.activeProfile.collectAsStateWithLifecycle()
    var previousProfileId by remember(mangaId) { mutableStateOf(activeProfile?.id) }
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

    LaunchedEffect(activeProfile?.id) {
        val currentProfileId = activeProfile?.id
        val lastProfileId = previousProfileId
        previousProfileId = currentProfileId

        if (currentProfileId != null && lastProfileId != null && currentProfileId != lastProfileId) {
            screenModel.setPreviewExpanded(false)
            onDismissRequest()
        }
    }

    LaunchedEffect(state, hasRequestedPreview, previewState.chapterId) {
        if (!hasRequestedPreview && state is MangaScreenModel.State.Success && previewState.chapterId == null) {
            hasRequestedPreview = true
            screenModel.setPreviewExpanded(true)
        }
    }

    AdaptiveSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxSize(),
        enableImplicitDismiss = false,
    ) {
        when (val currentState = state) {
            MangaScreenModel.State.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is MangaScreenModel.State.Success -> {
                BrowseMangaPreviewDialogContent(
                    title = currentState.manga.presentationTitle(),
                    state = currentState,
                    previewState = previewState,
                    previewSize = previewSize,
                    snackbarHostState = screenModel.snackbarHostState,
                    onDismissRequest = onDismissRequest,
                    onLibraryAction = onLibraryAction,
                    onMergeAction = onMergeAction,
                    onOpenManga = {
                        onDismissRequest()
                        onOpenManga(currentState.manga.id)
                    },
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

@Composable
private fun BrowseMangaPreviewDialogContent(
    title: String,
    state: MangaScreenModel.State.Success,
    previewState: MangaScreenModel.MangaPreviewState,
    previewSize: MangaPreviewSizeUi,
    snackbarHostState: SnackbarHostState,
    onDismissRequest: () -> Unit,
    onLibraryAction: (Manga) -> Unit,
    onMergeAction: (Manga) -> Unit,
    onOpenManga: () -> Unit,
    onRetry: () -> Unit,
    onPageLoad: (Int) -> Unit,
    onPageClick: (Long, Int) -> Unit,
) {
    val displayedPreviewState = if (
        previewState.chapterId == null && previewState.pages.isEmpty() && state.isRefreshingData
    ) {
        previewState.copy(
            isLoading = true,
            error = null,
        )
    } else {
        previewState
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            AppBar(
                titleContent = {
                    BrowseMangaPreviewTitle(title = title)
                },
                navigateUp = onDismissRequest,
            )
        },
        bottomBar = {
            BrowseMangaPreviewBottomBar(
                favorite = state.manga.favorite,
                onOpenManga = onOpenManga,
                onLibraryAction = { onLibraryAction(state.manga) },
                onMergeAction = { onMergeAction(state.manga) },
            )
        },
    ) { contentPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .systemBarsPadding()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            MangaPreviewContent(
                state = displayedPreviewState,
                size = previewSize,
                onRetry = onRetry,
                onPageLoad = onPageLoad,
                onPageClick = onPageClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState()),
                centerStates = true,
                loadingContent = {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(MR.strings.transition_pages_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun BrowseMangaPreviewTitle(title: String) {
    val context = LocalContext.current

    Text(
        text = title,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoIndication(
                onLongClick = {
                    context.copyToClipboard(title, title)
                },
                onClick = {},
            )
            .basicMarquee(
                repeatDelayMillis = 2_000,
            ),
    )
}

@Composable
private fun BrowseMangaPreviewBottomBar(
    favorite: Boolean,
    onOpenManga: () -> Unit,
    onLibraryAction: () -> Unit,
    onMergeAction: () -> Unit,
) {
    val spacing = MaterialTheme.padding.small
    val openLabel = stringResource(MR.strings.action_open)
    val libraryLabel = stringResource(if (favorite) MR.strings.remove_from_library else MR.strings.add_to_library)
    val mergeLabel = stringResource(MR.strings.action_add_to_merge)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        HorizontalDivider()
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            val useCompactLayout = maxWidth < 480.dp

            if (useCompactLayout) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    FilledTonalButton(
                        onClick = onOpenManga,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PreviewBottomBarActionContent(
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            label = openLabel,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        Button(
                            onClick = onLibraryAction,
                            modifier = Modifier.weight(1f),
                        ) {
                            PreviewBottomBarActionContent(
                                icon = if (favorite) Icons.Outlined.Delete else Icons.Outlined.FavoriteBorder,
                                label = libraryLabel,
                            )
                        }
                        FilledTonalButton(
                            onClick = onMergeAction,
                            modifier = Modifier.weight(1f),
                        ) {
                            PreviewBottomBarActionContent(
                                icon = Icons.AutoMirrored.Outlined.CallSplit,
                                label = mergeLabel,
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    FilledTonalButton(
                        onClick = onOpenManga,
                        modifier = Modifier.weight(1f),
                    ) {
                        PreviewBottomBarActionContent(
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            label = openLabel,
                        )
                    }
                    Button(
                        onClick = onLibraryAction,
                        modifier = Modifier.weight(1f),
                    ) {
                        PreviewBottomBarActionContent(
                            icon = if (favorite) Icons.Outlined.Delete else Icons.Outlined.FavoriteBorder,
                            label = libraryLabel,
                        )
                    }
                    FilledTonalButton(
                        onClick = onMergeAction,
                        modifier = Modifier.weight(1f),
                    ) {
                        PreviewBottomBarActionContent(
                            icon = Icons.AutoMirrored.Outlined.CallSplit,
                            label = mergeLabel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewBottomBarActionContent(icon: ImageVector, label: String) {
    Icon(
        imageVector = icon,
        contentDescription = null,
    )
    Spacer(modifier = Modifier.width(MaterialTheme.padding.small))
    Text(text = label)
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
