package eu.kanade.tachiyomi.ui.video.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.SourceOptionsDialog
import eu.kanade.presentation.browse.SourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.video.browse.globalsearch.VideoGlobalSearchScreen
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.domain.source.interactor.GetRemoteVideo
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.videoSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { VideoSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(VideoGlobalSearchScreen()) },
            ),
        ),
        content = { contentPadding, snackbarHostState ->
            SourcesScreen(
                state = state.listState,
                contentPadding = contentPadding,
                onClickItem = { source, listing ->
                    val listingQuery = when (listing) {
                        eu.kanade.domain.source.interactor.SourceListListing.Popular ->
                            GetRemoteVideo.QUERY_POPULAR
                        eu.kanade.domain.source.interactor.SourceListListing.Latest ->
                            GetRemoteVideo.QUERY_LATEST
                    }
                    navigator.push(VideoBrowseSourceScreen(source.id, listingQuery))
                },
                onClickPin = screenModel::togglePin,
                onLongClickItem = screenModel::showSourceDialog,
            )

            state.dialog?.let { dialog ->
                val source = dialog.source
                SourceOptionsDialog(
                    source = source,
                    onClickPin = {
                        screenModel.togglePin(source)
                        screenModel.closeDialog()
                    },
                    onClickDisable = {
                        screenModel.toggleSource(source)
                        screenModel.closeDialog()
                    },
                    onDismiss = screenModel::closeDialog,
                )
            }

            val internalErrString = stringResource(MR.strings.internal_error)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        VideoSourcesScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                    }
                }
            }
        },
    )
}
