package eu.kanade.tachiyomi.ui.video.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.source.model.VideoPlaybackOption
import eu.kanade.tachiyomi.source.model.VideoPlaybackSelection
import eu.kanade.tachiyomi.ui.video.player.VideoAdaptiveQualityPreference
import eu.kanade.tachiyomi.ui.video.player.defaultSubtitleSelection
import eu.kanade.tachiyomi.ui.video.player.externalSubtitleOptions
import eu.kanade.tachiyomi.ui.video.player.resolvePreviewSubtitleSelection
import eu.kanade.tachiyomi.ui.video.player.subtitleSelectionKey
import eu.kanade.tachiyomi.ui.video.player.VideoPlaybackUiState
import eu.kanade.tachiyomi.ui.video.player.VideoPlayerSubtitleSelection
import eu.kanade.tachiyomi.ui.video.player.withSelectedDub
import eu.kanade.tachiyomi.ui.video.player.withSelectedSourceQuality
import eu.kanade.tachiyomi.ui.video.player.withSelectedStream
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun VideoPlayerSettingsSheet(
    playback: VideoPlaybackUiState,
    onDismissRequest: () -> Unit,
    onApplySourceSelection: (VideoPlaybackSelection) -> Unit,
    onPreviewSourceSelection: (VideoPlaybackSelection) -> Unit,
    onSelectAdaptiveQuality: (VideoAdaptiveQualityPreference) -> Unit,
    onSelectSubtitle: (VideoPlayerSubtitleSelection) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val originalSelection = remember(playback.sourceSelection, playback.preferredSourceQualityKey) {
        playback.sourceSelection.copy(
            sourceQualityKey = playback.preferredSourceQualityKey ?: playback.sourceSelection.sourceQualityKey,
        )
    }
    var draftSelection by remember(originalSelection) { mutableStateOf(originalSelection) }
    var draftSubtitleSelection by remember(playback.currentSubtitle, originalSelection) {
        mutableStateOf(playback.currentSubtitle)
    }
    var draftSubtitleTouched by remember(originalSelection) { mutableStateOf(false) }
    val draftDubMatchesActive = draftSelection.dubKey == playback.sourceSelection.dubKey
    val previewSelection = playback.preview.selection
    val sourceQualityOptions = if (draftDubMatchesActive) {
        playback.playbackData.sourceQualities
    } else {
        playback.preview.playbackData?.sourceQualities.orEmpty()
    }
    val qualityOptionsLoading = !draftDubMatchesActive &&
        playback.isPreviewLoading &&
        previewSelection?.dubKey == draftSelection.dubKey &&
        sourceQualityOptions.isEmpty()
    val previewSubtitles = if (draftDubMatchesActive) {
        playback.subtitles
    } else {
        playback.preview.subtitles.orEmpty()
    }
    val subtitleOptions = if (draftDubMatchesActive) {
        (externalSubtitleOptions(playback.subtitles) + playback.subtitleOptions.filter {
            it.selection is VideoPlayerSubtitleSelection.Embedded
        }).distinctBy { it.key }
    } else {
        externalSubtitleOptions(previewSubtitles)
    }
    val subtitleOptionsLoading = !draftDubMatchesActive &&
        playback.isPreviewLoading &&
        previewSelection?.dubKey == draftSelection.dubKey &&
        playback.preview.subtitles == null
    val selectedSubtitle = draftSubtitleSelection
    val hasPendingSourceChanges = draftSelection != originalSelection
    val hasPendingSubtitleChanges = draftSubtitleSelection != playback.currentSubtitle
    val hasPendingChanges = hasPendingSourceChanges || hasPendingSubtitleChanges
    val streamOptionsEnabled = draftDubMatchesActive &&
        draftSelection.sourceQualityKey == playback.sourceSelection.sourceQualityKey

    LaunchedEffect(draftSelection.dubKey) {
        if (draftDubMatchesActive) {
            draftSubtitleSelection = playback.currentSubtitle
            draftSubtitleTouched = false
            onPreviewSourceSelection(playback.sourceSelection)
        } else {
            draftSubtitleSelection = resolvePreviewSubtitleSelection(playback.currentSubtitle, previewSubtitles)
            draftSubtitleTouched = false
            onPreviewSourceSelection(draftSelection)
        }
    }

    LaunchedEffect(draftDubMatchesActive, previewSubtitles, playback.currentSubtitle) {
        if (draftDubMatchesActive) {
            draftSubtitleSelection = playback.currentSubtitle
            draftSubtitleTouched = false
        } else if (!draftSubtitleTouched) {
            draftSubtitleSelection = resolvePreviewSubtitleSelection(playback.currentSubtitle, previewSubtitles)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (playback.playbackData.dubs.isNotEmpty()) {
                item {
                    PlaybackOptionRow(
                        options = playback.playbackData.dubs,
                        titleRes = MR.strings.anime_playback_dub,
                        selectedKey = draftSelection.dubKey,
                        onSelect = { draftSelection = draftSelection.withSelectedDub(it, originalSelection) },
                    )
                }
            }

            if (playback.streamOptions.size > 1) {
                item {
                    PlaybackOptionRow(
                        options = playback.streamOptions,
                        titleRes = MR.strings.anime_playback_stream,
                        selectedKey = draftSelection.streamKey,
                        enabled = streamOptionsEnabled,
                        onSelect = { draftSelection = draftSelection.withSelectedStream(it) },
                    )
                }
            }

            if (qualityOptionsLoading || sourceQualityOptions.isNotEmpty()) {
                item {
                    if (qualityOptionsLoading) {
                        LoadingPlaybackOptionRow(titleRes = MR.strings.anime_playback_source_quality)
                    } else {
                        PlaybackOptionRow(
                            options = sourceQualityOptions,
                            titleRes = MR.strings.anime_playback_source_quality,
                            selectedKey = draftSelection.sourceQualityKey,
                            onSelect = {
                                draftSelection = draftSelection.withSelectedSourceQuality(it, originalSelection)
                            },
                        )
                    }
                }
            }

            if (playback.showsAdaptiveQualitySelector) {
                item {
                    SettingsChipRow(MR.strings.anime_playback_quality) {
                        playback.adaptiveQualities.forEach { option ->
                            FilterChip(
                                selected = option.preference == playback.currentAdaptiveQuality,
                                onClick = { onSelectAdaptiveQuality(option.preference) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }

            if (subtitleOptionsLoading || subtitleOptions.size > 1) {
                item {
                    if (subtitleOptionsLoading) {
                        LoadingPlaybackOptionRow(titleRes = MR.strings.anime_playback_subtitles)
                    } else {
                        PlaybackOptionRow(
                            options = subtitleOptions.map {
                                VideoPlaybackOption(
                                    key = it.key,
                                    label = it.label,
                                )
                            },
                            titleRes = MR.strings.anime_playback_subtitles,
                            selectedKey = subtitleSelectionKey(selectedSubtitle),
                            onSelect = { key ->
                                val selection = subtitleOptions
                                    .firstOrNull { option -> option.key == key }
                                    ?.selection
                                    ?: if (draftDubMatchesActive) {
                                        playback.currentSubtitle
                                    } else {
                                        defaultSubtitleSelection(previewSubtitles)
                                    }
                                draftSubtitleSelection = selection
                                draftSubtitleTouched = true
                            },
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Button(
                        enabled = hasPendingChanges,
                        onClick = {
                            onSelectSubtitle(
                                if (draftDubMatchesActive) {
                                    draftSubtitleSelection
                                } else {
                                    when (draftSubtitleSelection) {
                                        is VideoPlayerSubtitleSelection.Embedded -> defaultSubtitleSelection(previewSubtitles)
                                        else -> draftSubtitleSelection
                                    }
                                },
                            )
                            onApplySourceSelection(draftSelection)
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_apply))
                    }
                }
            }
        }
    }
}
