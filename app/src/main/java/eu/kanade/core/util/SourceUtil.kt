package eu.kanade.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.source.service.VideoSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ifSourcesLoaded(): Boolean {
    return remember { Injekt.get<SourceManager>().isInitialized }.collectAsState().value
}

@Composable
fun ifVideoSourcesLoaded(): Boolean {
    return remember { Injekt.get<VideoSourceManager>().isInitialized }.collectAsState().value
}
